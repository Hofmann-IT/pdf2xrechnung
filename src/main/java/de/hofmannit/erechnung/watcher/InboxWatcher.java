package de.hofmannit.erechnung.watcher;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import de.hofmannit.erechnung.archive.FileStore;
import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.RuntimeConfig;
import de.hofmannit.erechnung.configuration.RuntimeSettings;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.security.Sha256;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Überwacht {@code inbox/} je Mandant (Vorgabe Abschnitt 5.1) und übergibt vollständig
 * geschriebene PDFs an die Pipeline.
 *
 * <ul>
 *   <li>Eine Datei gilt als vollständig, wenn Größe und Änderungszeit über
 *       {@code stable-checks} Prüfintervalle unverändert sind und sie lesend geöffnet werden kann.
 *       Ein plattformneutraler exklusiver Öffnungstest existiert in Java/NIO nicht; unter Windows
 *       schlägt das lesende Öffnen einer noch geschriebenen Datei in der Regel fehl, unter Linux
 *       greift die Stabilitätsprüfung.</li>
 *   <li>Idempotenz: Ein bereits bekanntes Quelldokument (tenant + SHA-256) wird nie erneut
 *       verarbeitet, sondern als Duplikat protokolliert und nach {@code rejected/duplikate/}
 *       verschoben (Abschnitt 5.2).</li>
 *   <li>Beim Start läuft zuerst der Wiederanlauf für {@code processing/} (Abschnitt 36).</li>
 * </ul>
 */
@Component
public class InboxWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(InboxWatcher.class);

    private final RuntimeSettings settings;
    private volatile RuntimeConfig.Watcher config;
    private final DirectoryLayout layout;
    private final ProfileRegistry registry;
    private final LedgerRepository ledger;
    private final ProcessingPipeline pipeline;
    private final TenantExecutors executors;
    private final StartupRecovery recovery;
    private final Clock clock;

    private final Map<Path, Snapshot> tracked = new HashMap<>();
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public InboxWatcher(RuntimeSettings settings, DirectoryLayout layout, ProfileRegistry registry, LedgerRepository ledger,
                        ProcessingPipeline pipeline, TenantExecutors executors, StartupRecovery recovery, Clock clock) {
        this.settings = settings;
        this.config = settings.current().watcher();
        this.layout = layout;
        this.registry = registry;
        this.ledger = ledger;
        this.pipeline = pipeline;
        this.executors = executors;
        this.recovery = recovery;
        this.clock = clock;
    }

    @Override
    public void start() {
        config = settings.current().watcher();
        recovery.recover();
        settings.addListener(this::onSettingsChanged);
        schedule();
        running = true;
    }

    /** Startet die Überwachung mit den aktuellen Einstellungen (neu), sofern aktiviert. */
    private synchronized void schedule() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (!config.enabled()) {
            log.info("Inbox-Watcher ist deaktiviert (app.watcher.enabled=false)");
            return;
        }
        long millis = Math.max(250, config.pollInterval().toMillis());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "inbox-watcher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::safePoll, millis, millis, TimeUnit.MILLISECONDS);
        log.info("Inbox-Watcher gestartet: Intervall {} ms, {} stabile Prüfungen, Mandanten {}", millis, config.stableChecks(),
                registry.tenants().stream().filter(Tenant::enabled).map(Tenant::id).toList());
    }

    /** Änderung aus der Verwaltung (ADR 0012): Intervall, Stabilitätsprüfung oder Aktivierung sofort übernehmen. */
    private void onSettingsChanged(RuntimeConfig changed) {
        RuntimeConfig.Watcher next = changed.watcher();
        boolean reschedule = running && (next.enabled() != config.enabled() || !next.pollInterval().equals(config.pollInterval()));
        config = next;
        if (reschedule) {
            schedule();
        }
    }

    /** Aktuell wirksames Prüfintervall in Millisekunden, 0 wenn die Überwachung nicht läuft. */
    public long activeIntervalMillis() {
        return scheduler == null || !config.enabled() ? 0 : Math.max(250, config.pollInterval().toMillis());
    }

    @Override
    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        executors.shutdown();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void safePoll() {
        try {
            poll();
        } catch (RuntimeException e) {
            log.error("Fehler beim Prüfen der Inbox", e);
        }
    }

    /** Ein Prüfdurchlauf über alle aktiven Mandanten (auch für Tests direkt aufrufbar). */
    public synchronized void poll() {
        Set<Path> seen = new HashSet<>();
        for (Tenant tenant : registry.tenants()) {
            if (!tenant.enabled()) {
                continue;
            }
            Path inbox = layout.inbox(tenant);
            try {
                Files.createDirectories(inbox);
            } catch (IOException e) {
                log.error("Inbox {} kann nicht angelegt werden: {}", inbox, e.toString());
                continue;
            }
            List<Path> pdfs;
            try (Stream<Path> s = Files.list(inbox)) {
                pdfs = s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .sorted().toList();
            } catch (IOException e) {
                log.error("Inbox {} nicht lesbar: {}", inbox, e.toString());
                continue;
            }
            for (Path pdf : pdfs) {
                seen.add(pdf);
                if (isStable(pdf)) {
                    tracked.remove(pdf);
                    claim(tenant, pdf);
                }
            }
        }
        tracked.keySet().retainAll(seen);
    }

    private boolean isStable(Path pdf) {
        try {
            long size = Files.size(pdf);
            FileTime modified = Files.getLastModifiedTime(pdf);
            Snapshot previous = tracked.get(pdf);
            Snapshot current;
            if (previous != null && previous.size == size && previous.modified.equals(modified)) {
                current = new Snapshot(size, modified, previous.stableCount + 1);
            } else {
                current = new Snapshot(size, modified, 0);
            }
            tracked.put(pdf, current);
            if (size == 0 || current.stableCount < config.stableChecks()) {
                return false;
            }
            return isReadable(pdf);
        } catch (IOException e) {
            tracked.remove(pdf);
            return false;
        }
    }

    /** Vollständig lesbar: Datei lässt sich öffnen und bis zum Ende lesen. */
    private static boolean isReadable(Path pdf) {
        try (SeekableByteChannel ch = Files.newByteChannel(pdf, StandardOpenOption.READ)) {
            ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
            while (ch.read(buf) > 0) {
                buf.clear();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void claim(Tenant tenant, Path pdf) {
        String originalName = pdf.getFileName().toString();
        try {
            String sha = Sha256.ofFile(pdf);
            Optional<SourceDocumentRow> existing = ledger.findSource(tenant.id(), sha);
            if (existing.isPresent() && !ledger.findRuns(existing.get().id()).isEmpty()) {
                handleDuplicate(tenant, pdf, sha, existing.get());
                return;
            }
            Path processingDir = Files.createDirectories(layout.processing(tenant));
            Path target = processingDir.resolve(sha.substring(0, 8) + "_" + originalName);
            if (Files.exists(target)) {
                log.warn("Datei {} liegt bereits in processing/ und wird übersprungen", target.getFileName());
                return;
            }
            FileStore.moveNoOverwrite(pdf, target);
            ProcessingJob job = ProcessingJob.auto(tenant, target, originalName, sha);
            log.info("Übernommen aus Inbox: {} (Mandant {}, SHA-256 {})", originalName, tenant.id(), sha.substring(0, 8));
            executors.submit(tenant.id(), () -> pipeline.process(job));
        } catch (IOException e) {
            log.error("Datei {} konnte nicht übernommen werden: {}", originalName, e.toString());
        }
    }

    private void handleDuplicate(Tenant tenant, Path pdf, String sha, SourceDocumentRow source) throws IOException {
        Optional<ProcessingRunRow> latest = ledger.findLatestRun(source.id());
        String originalName = pdf.getFileName().toString();
        String message = "Duplikat in Inbox erkannt: " + originalName + " entspricht Quelldokument " + source.originalFilename()
                + " (erstmals gesehen " + source.firstSeenAt() + ")";
        latest.ifPresent(run -> ledger.appendEvent(run.id(), EventType.DUPLICATE_DETECTED, clock.instant(), "system", message,
                "{\"file\":\"" + originalName.replace("\"", "'") + "\"}"));
        Path dupDir = Files.createDirectories(layout.rejected(tenant).resolve("duplikate"));
        String base = sha.substring(0, 8) + "_" + clock.instant().toEpochMilli() + "_" + originalName;
        FileStore.moveNoOverwrite(pdf, dupDir.resolve(base));
        FileStore.writeNew(dupDir.resolve(base + ".txt"), (message + "\nUrsprünglicher Run: "
                + latest.map(r -> r.correlationId()).orElse("unbekannt") + "\n").getBytes(StandardCharsets.UTF_8));
        log.info("{} → rejected/duplikate", message);
    }

    private record Snapshot(long size, FileTime modified, int stableCount) {
    }
}
