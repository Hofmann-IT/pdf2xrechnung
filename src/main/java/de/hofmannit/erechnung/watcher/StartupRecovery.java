package de.hofmannit.erechnung.watcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

import de.hofmannit.erechnung.archive.FileStore;
import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.security.Sha256;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wiederanlauf nach Neustart (Vorgabe Abschnitt 36, ADR 0004): Dateien in {@code processing/}
 * werden erneut geprüft. Ein offener Run wird mit {@code PROCESSING_FAILED} abgeschlossen und
 * ein neuer Run mit Trigger {@code RESTART_RECOVERY} und Verweis auf den Vorgänger gestartet.
 */
@Component
public class StartupRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRecovery.class);

    private final DirectoryLayout layout;
    private final ProfileRegistry registry;
    private final LedgerRepository ledger;
    private final ProcessingPipeline pipeline;
    private final TenantExecutors executors;
    private final Clock clock;

    public StartupRecovery(DirectoryLayout layout, ProfileRegistry registry, LedgerRepository ledger, ProcessingPipeline pipeline,
                           TenantExecutors executors, Clock clock) {
        this.layout = layout;
        this.registry = registry;
        this.ledger = ledger;
        this.pipeline = pipeline;
        this.executors = executors;
        this.clock = clock;
    }

    /** Liefert die Anzahl wieder angestoßener Dateien. */
    public int recover() {
        int count = 0;
        for (Tenant tenant : registry.tenants()) {
            if (!tenant.enabled()) {
                continue;
            }
            Path dir = layout.processing(tenant);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> s = Files.list(dir)) {
                files = s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .sorted().toList();
            } catch (IOException e) {
                log.error("processing/ von Mandant {} nicht lesbar: {}", tenant.id(), e.toString());
                continue;
            }
            for (Path file : files) {
                try {
                    if (recoverFile(tenant, file)) {
                        count++;
                    }
                } catch (IOException e) {
                    log.error("Wiederanlauf für {} fehlgeschlagen: {}", file, e.toString());
                }
            }
        }
        if (count > 0) {
            log.info("Wiederanlauf: {} Datei(en) aus processing/ erneut angestoßen", count);
        }
        return count;
    }

    private boolean recoverFile(Tenant tenant, Path file) throws IOException {
        String sha = Sha256.ofFile(file);
        String originalName = originalName(file.getFileName().toString(), sha);
        Optional<SourceDocumentRow> source = ledger.findSource(tenant.id(), sha);
        if (source.isEmpty()) {
            log.info("Wiederanlauf: {} ohne Datenbankspur, wird wie neu verarbeitet", file.getFileName());
            ProcessingJob job = ProcessingJob.auto(tenant, file, originalName, sha);
            executors.submit(tenant.id(), () -> pipeline.process(job));
            return true;
        }
        Optional<ProcessingRunRow> open = ledger.findOpenRun(source.get().id());
        if (open.isPresent()) {
            ProcessingRunRow aborted = open.get();
            ledger.appendEvent(aborted.id(), EventType.PROCESSING_FAILED, clock.instant(), "system",
                    "Abgebrochen durch Neustart der Anwendung; Wiederanlauf folgt", null);
            ledger.finishRun(aborted.id(), clock.instant(), RunResult.FAILED);
            ProcessingJob job = ProcessingJob.recovery(tenant, file, originalName, sha, aborted.id());
            log.info("Wiederanlauf: {} (Run {} abgebrochen, neuer Run RESTART_RECOVERY)", file.getFileName(), aborted.runNumber());
            executors.submit(tenant.id(), () -> pipeline.process(job));
            return true;
        }
        // Datei liegt in processing/, aber alle Runs sind abgeschlossen: verwaiste Datei sichern, nichts überschreiben.
        Path failedDir = Files.createDirectories(layout.failed(tenant).resolve("verwaist"));
        Path target = FileStore.moveNoOverwrite(file, failedDir.resolve(file.getFileName().toString()));
        FileStore.writeNew(failedDir.resolve(file.getFileName().toString() + ".txt"),
                ("Datei lag beim Neustart in processing/, alle Runs des Quelldokuments (SHA-256 " + sha + ") sind bereits abgeschlossen.\n")
                        .getBytes(StandardCharsets.UTF_8));
        log.warn("Wiederanlauf: verwaiste Datei nach {} verschoben", target);
        return false;
    }

    /** processing/-Dateien heißen {@code <sha8>_<original>}; den Originalnamen zurückgewinnen. */
    static String originalName(String processingName, String sha) {
        String prefix = sha.substring(0, 8) + "_";
        return processingName.startsWith(prefix) ? processingName.substring(prefix.length()) : processingName;
    }
}
