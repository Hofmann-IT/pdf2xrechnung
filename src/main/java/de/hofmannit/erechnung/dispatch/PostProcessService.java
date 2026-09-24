package de.hofmannit.erechnung.dispatch;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.dispatch.EmailDispatcher.SentMail;
import de.hofmannit.erechnung.dispatch.LocalCommandRunner.CommandResult;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ArtifactRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.ledger.StatusProjection;
import de.hofmannit.erechnung.model.ArtifactType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Postprozess je Profil (Vorgabe Abschnitte 22 und 23): E-Mail-Versand und lokales Kommando.
 *
 * <ul>
 *   <li>Versand nur für Runs mit Ergebnis SUCCESS und Status VALID/ARCHIVED/DISPATCH_*; nie bei
 *       REVIEW, FAILED oder REJECTED.</li>
 *   <li>Jeder Versuch erzeugt {@code DISPATCH_ATTEMPTED} und danach {@code DISPATCH_SUCCEEDED}
 *       oder {@code DISPATCH_FAILED}; frühere Versandinformationen werden nie überschrieben.</li>
 *   <li>Ein erneuter Versand ist eine eigene Benutzeraktion ({@link #dispatchManually}).</li>
 * </ul>
 */
@Service
public class PostProcessService {

    private static final Logger log = LoggerFactory.getLogger(PostProcessService.class);

    private final LedgerRepository ledger;
    private final ProfileRegistry profiles;
    private final DirectoryLayout layout;
    private final EmailDispatcher email;
    private final LocalCommandRunner commands;
    private final Clock clock;
    private final ObjectMapper json;

    public PostProcessService(LedgerRepository ledger, ProfileRegistry profiles, DirectoryLayout layout, EmailDispatcher email,
                              LocalCommandRunner commands, Clock clock, ObjectMapper json) {
        this.ledger = ledger;
        this.profiles = profiles;
        this.layout = layout;
        this.email = email;
        this.commands = commands;
        this.clock = clock;
        this.json = json;
    }

    /** Ergebnis eines Versandversuchs. */
    public record DispatchOutcome(boolean success, String message) {
    }

    /**
     * Automatischer Postprozess nach erfolgreichem Run (Aufruf durch die Pipeline). Führt nur aus,
     * was das Profil aktiviert hat. Fehler werden als Events festgehalten und nie als Ausnahme
     * an die Pipeline zurückgegeben.
     */
    public void afterSuccessfulRun(long runId) {
        try {
            ProcessingRunRow run = ledger.findRun(runId).orElseThrow();
            LoadedProfile profile = profiles.profile(run.profileName()).orElseThrow();
            ProfileDefinition.PostProcess pp = profile.definition().postProcess();
            if (pp == null) {
                return;
            }
            if (pp.email() != null && pp.email().enabled()) {
                dispatch(run, profile, "system", false);
            }
            if (pp.command() != null && pp.command().enabled()) {
                runCommand(run, profile);
            }
        } catch (DispatchException | RuntimeException e) {
            log.error("Postprozess für Run {} fehlgeschlagen", runId, e);
        }
    }

    /** Manueller (erneuter) Versand als eigene Benutzeraktion. */
    public DispatchOutcome dispatchManually(long runId, String actor) throws DispatchException {
        ProcessingRunRow run = ledger.findRun(runId).orElseThrow(() -> new DispatchException("Run " + runId + " existiert nicht"));
        LoadedProfile profile = profiles.profile(run.profileName())
                .orElseThrow(() -> new DispatchException("Profil " + run.profileName() + " ist nicht mehr vorhanden"));
        if (profile.definition().postProcess() == null || profile.definition().postProcess().email() == null
                || !profile.definition().postProcess().email().enabled()) {
            throw new DispatchException("E-Mail-Versand ist im Profil " + profile.name() + " nicht aktiviert");
        }
        return dispatch(run, profile, actor, true);
    }

    private DispatchOutcome dispatch(ProcessingRunRow run, LoadedProfile profile, String actor, boolean manual) throws DispatchException {
        InvoiceStatus status = StatusProjection.derive(ledger.events(run.id()));
        if (run.result() != RunResult.SUCCESS || !DISPATCHABLE.contains(status)) {
            String msg = "Versand nicht zulässig: Run-Ergebnis " + run.result() + ", Status " + status;
            if (manual) {
                throw new DispatchException(msg);
            }
            log.warn("{} (Run {})", msg, run.correlationId());
            return new DispatchOutcome(false, msg);
        }
        ProfileDefinition.Email cfg = profile.definition().postProcess().email();
        Map<String, String> values = templateValues(run, profile);
        Map<String, LocalDate> dates = templateDates(run);
        String subject;
        String body;
        List<Path> attachments;
        try {
            subject = TextTemplate.render(cfg.subjectTemplate() == null ? "Rechnung {invoiceNumber}" : cfg.subjectTemplate(), values, dates);
            body = TextTemplate.render(cfg.bodyTemplate() == null ? "" : cfg.bodyTemplate(), values, dates);
            attachments = attachments(run, cfg.attachments());
        } catch (IllegalArgumentException e) {
            throw new DispatchException("Versandkonfiguration unvollständig: " + e.getMessage(), e);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("manual", manual);
        details.put("actor", actor);
        details.put("to", cfg.to());
        details.put("cc", cfg.cc());
        details.put("subject", subject);
        details.put("attachments", attachments.stream().map(p -> p.getFileName().toString()).toList());
        event(run.id(), EventType.DISPATCH_ATTEMPTED, actor, (manual ? "Erneuter Versand" : "Versand") + " an " + cfg.to(), details);
        try {
            SentMail sent = email.send(cfg.to(), cfg.cc(), subject, body, attachments);
            Map<String, Object> ok = new LinkedHashMap<>(details);
            ok.put("messageId", sent.messageId());
            event(run.id(), EventType.DISPATCH_SUCCEEDED, actor, "Versendet an " + sent.to() + " (Message-ID " + sent.messageId() + ")", ok);
            log.info("Run {} versendet an {}", run.correlationId(), sent.to());
            return new DispatchOutcome(true, "Versendet an " + sent.to());
        } catch (DispatchException e) {
            Map<String, Object> failed = new LinkedHashMap<>(details);
            failed.put("error", e.getMessage());
            event(run.id(), EventType.DISPATCH_FAILED, actor, e.getMessage(), failed);
            log.warn("Run {} Versand fehlgeschlagen: {}", run.correlationId(), e.getMessage());
            if (manual) {
                throw e;
            }
            return new DispatchOutcome(false, e.getMessage());
        }
    }

    private static final java.util.Set<InvoiceStatus> DISPATCHABLE = java.util.EnumSet.of(
            InvoiceStatus.VALID, InvoiceStatus.ARCHIVED, InvoiceStatus.DISPATCH_PENDING, InvoiceStatus.DISPATCHED, InvoiceStatus.DISPATCH_FAILED);

    /**
     * Lokales Kommando: Executable und Argumente getrennt, Platzhalter je Argument ersetzt,
     * niemals über eine Shell. Das Ergebnis wird protokolliert; ein eigener Event-Typ dafür ist
     * im Schema nicht vorgesehen (offener Punkt Phase 4, Schemaänderung erfordert Freigabe).
     */
    public Optional<CommandResult> runCommand(ProcessingRunRow run, LoadedProfile profile) {
        ProfileDefinition.Command cfg = profile.definition().postProcess().command();
        try {
            Map<String, String> values = templateValues(run, profile);
            Map<String, LocalDate> dates = templateDates(run);
            List<String> args = new ArrayList<>();
            for (String a : cfg.arguments()) {
                args.add(TextTemplate.render(a, values, dates));
            }
            Path workDir = cfg.workingDirectory() == null || cfg.workingDirectory().isBlank() ? null : Path.of(cfg.workingDirectory());
            CommandResult result = commands.run(cfg.executable(), args, workDir, cfg.timeout());
            log.info("Postprozess-Kommando für Run {}: exit={} timeout={}", run.correlationId(), result.exitCode(), result.timedOut());
            return Optional.of(result);
        } catch (IOException | IllegalArgumentException e) {
            log.error("Postprozess-Kommando für Run {} nicht ausführbar: {}", run.correlationId(), e.getMessage());
            return Optional.empty();
        }
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private Map<String, String> templateValues(ProcessingRunRow run, LoadedProfile profile) {
        Optional<LedgerEntryRow> entry = ledger.ledgerEntry(run.id());
        Map<String, String> values = new HashMap<>();
        values.put("invoiceNumber", entry.map(LedgerEntryRow::invoiceNumber).orElse(null));
        values.put("customerName", entry.map(LedgerEntryRow::customerName).orElse(null));
        values.put("tenantId", entry.map(LedgerEntryRow::tenantId).orElse(null));
        values.put("runNumber", String.format(Locale.ROOT, "%03d", run.runNumber()));
        values.put("correlationId", run.correlationId());
        Map<ArtifactType, Path> artifacts = artifactPaths(run.id());
        values.put("sourcePdf", path(artifacts.get(ArtifactType.SOURCE_PDF)));
        values.put("zugferdPdf", path(artifacts.get(ArtifactType.ZUGFERD_PDF)));
        values.put("ciiXml", path(artifacts.get(ArtifactType.XRECHNUNG_CII)));
        values.put("ublXml", path(artifacts.get(ArtifactType.XRECHNUNG_UBL)));
        Path any = artifacts.get(ArtifactType.SOURCE_PDF);
        values.put("archiveDir", any == null ? null : any.getParent().toString());
        return values;
    }

    private Map<String, LocalDate> templateDates(ProcessingRunRow run) {
        Map<String, LocalDate> dates = new HashMap<>();
        Optional<String> date = ledger.ledgerEntry(run.id()).map(LedgerEntryRow::invoiceDate);
        dates.put("invoiceDate", date.map(LocalDate::parse).orElse(null));
        return dates;
    }

    private Map<ArtifactType, Path> artifactPaths(long runId) {
        Map<ArtifactType, Path> result = new LinkedHashMap<>();
        for (ArtifactRow a : ledger.artifacts(runId)) {
            result.putIfAbsent(a.type(), layout.archiveRoot().resolve(a.path()));
        }
        return result;
    }

    private List<Path> attachments(ProcessingRunRow run, List<ArtifactType> types) throws DispatchException {
        Map<ArtifactType, Path> artifacts = artifactPaths(run.id());
        List<Path> result = new ArrayList<>();
        for (ArtifactType t : types) {
            Path p = artifacts.get(t);
            if (p == null) {
                throw new DispatchException("Anhang " + t + " ist für Run " + run.correlationId() + " nicht vorhanden");
            }
            result.add(p);
        }
        return result;
    }

    private static String path(Path p) {
        return p == null ? null : p.toString();
    }

    private void event(long runId, EventType type, String actor, String message, Map<String, ?> details) {
        String detailsJson;
        try {
            detailsJson = json.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            detailsJson = String.valueOf(details);
        }
        ledger.appendEvent(runId, type, clock.instant(), actor, message, detailsJson);
    }
}
