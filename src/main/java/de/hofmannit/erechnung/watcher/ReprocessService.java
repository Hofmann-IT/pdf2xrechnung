package de.hofmannit.erechnung.watcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.hofmannit.erechnung.archive.FileStore;
import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ArtifactRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.StatusProjection;
import de.hofmannit.erechnung.model.ArtifactType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manueller Reprocess (Vorgabe Abschnitt 16, ADR 0004): kontrollierte Ausnahme von der
 * Idempotenz. Erzeugt einen neuen Run mit {@code MANUAL_REPROCESS}, Verweis auf den Vorgänger,
 * Benutzer, Begründung, Zeitstempel, Profilname/-hash und Anwendungsversion. Überschreibt nichts
 * und versendet niemals automatisch.
 *
 * <p>Statusregeln: FAILED/REVIEW/REJECTED nach expliziter Benutzeraktion; VALID/ARCHIVED/
 * DISPATCH_* nur mit zusätzlicher Bestätigung ({@code confirm}).
 */
@Service
public class ReprocessService {

    private static final Logger log = LoggerFactory.getLogger(ReprocessService.class);
    private static final Set<InvoiceStatus> NEEDS_CONFIRMATION = EnumSet.of(InvoiceStatus.VALID, InvoiceStatus.ARCHIVED,
            InvoiceStatus.DISPATCH_PENDING, InvoiceStatus.DISPATCHED, InvoiceStatus.DISPATCH_FAILED);

    private final LedgerRepository ledger;
    private final ProfileRegistry registry;
    private final DirectoryLayout layout;
    private final ProcessingPipeline pipeline;
    private final Clock clock;
    private final ObjectMapper json;

    public ReprocessService(LedgerRepository ledger, ProfileRegistry registry, DirectoryLayout layout, ProcessingPipeline pipeline,
                            Clock clock, ObjectMapper json) {
        this.ledger = ledger;
        this.registry = registry;
        this.layout = layout;
        this.pipeline = pipeline;
        this.clock = clock;
        this.json = json;
    }

    /** Reprocess nicht zulässig (Vorbedingung verletzt oder Bestätigung fehlt). */
    public static class ReprocessException extends Exception {
        private final boolean confirmationRequired;

        public ReprocessException(String message, boolean confirmationRequired) {
            super(message);
            this.confirmationRequired = confirmationRequired;
        }

        public boolean isConfirmationRequired() {
            return confirmationRequired;
        }
    }

    /**
     * @param tenantId    Mandant
     * @param sha256      vollständiger SHA-256 oder eindeutiges Präfix (mindestens 8 Zeichen)
     * @param requestedBy Benutzer
     * @param reason      Begründung
     * @param confirmed   zusätzliche Bestätigung für bereits validierte/archivierte/versendete Rechnungen
     */
    public RunOutcome reprocess(String tenantId, String sha256, String requestedBy, String reason, boolean confirmed)
            throws ReprocessException, IOException {
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new ReprocessException("Benutzer ist erforderlich", false);
        }
        if (reason == null || reason.isBlank()) {
            throw new ReprocessException("Begründung ist erforderlich", false);
        }
        Tenant tenant = registry.tenant(tenantId).orElseThrow(() -> new ReprocessException("Unbekannter Mandant: " + tenantId, false));
        SourceDocumentRow source = resolveSource(tenantId, sha256);
        if (ledger.findOpenRun(source.id()).isPresent()) {
            throw new ReprocessException("Für dieses Dokument läuft bereits ein Run", false);
        }
        ProcessingRunRow latest = ledger.findLatestRun(source.id())
                .orElseThrow(() -> new ReprocessException("Quelldokument hat noch keinen Run", false));
        InvoiceStatus status = StatusProjection.derive(ledger.events(latest.id()));
        if (NEEDS_CONFIRMATION.contains(status) && !confirmed) {
            throw new ReprocessException("Run " + latest.correlationId() + " hat Status " + status
                    + "; Reprocess erfordert eine ausdrückliche Bestätigung", true);
        }
        Path original = locateOriginal(latest, source);
        Path processingDir = Files.createDirectories(layout.processing(tenant));
        Path target = processingDir.resolve(source.sha256().substring(0, 8) + "_" + source.originalFilename());
        if (Files.exists(target)) {
            throw new ReprocessException("Datei liegt bereits in processing/: " + target.getFileName(), false);
        }
        FileStore.copyNoOverwrite(original, target);
        ledger.appendEvent(latest.id(), EventType.REPROCESS_REQUESTED, clock.instant(), requestedBy,
                "Reprocess angefordert: " + reason, details(Map.of("requestedBy", requestedBy, "reason", reason, "status", status.name(), "confirmed", confirmed)));
        log.info("Reprocess für {} (Run {}, Status {}) durch {}: {}", source.originalFilename(), latest.correlationId(), status, requestedBy, reason);
        ProcessingJob job = new ProcessingJob(tenant, target, source.originalFilename(), source.sha256(),
                de.hofmannit.erechnung.ledger.RunTrigger.MANUAL_REPROCESS, latest.id(), requestedBy, reason);
        RunOutcome outcome = pipeline.process(job);
        ledger.appendEvent(outcome.runId(), EventType.REPROCESS_COMPLETED, clock.instant(), requestedBy,
                "Reprocess abgeschlossen mit " + outcome.result() + ": " + outcome.message(),
                details(Map.of("parentRunId", latest.id(), "result", outcome.result().name())));
        return outcome;
    }

    private SourceDocumentRow resolveSource(String tenantId, String sha256) throws ReprocessException {
        if (sha256 == null || sha256.length() < 8) {
            throw new ReprocessException("SHA-256 oder Präfix (mindestens 8 Zeichen) erforderlich", false);
        }
        List<SourceDocumentRow> candidates = ledger.findSourcesByShaPrefix(tenantId, sha256.toLowerCase());
        if (candidates.isEmpty()) {
            throw new ReprocessException("Kein Quelldokument mit SHA-256 " + sha256 + " für Mandant " + tenantId, false);
        }
        if (candidates.size() > 1) {
            throw new ReprocessException("SHA-256-Präfix " + sha256 + " ist nicht eindeutig (" + candidates.size() + " Treffer)", false);
        }
        return candidates.get(0);
    }

    /** Original aus dem Archiv des jüngsten Runs (schreibgeschützte Kopie, wird nur gelesen). */
    private Path locateOriginal(ProcessingRunRow latest, SourceDocumentRow source) throws ReprocessException {
        Optional<ArtifactRow> archived = ledger.artifacts(latest.id()).stream().filter(a -> a.type() == ArtifactType.SOURCE_PDF).findFirst();
        if (archived.isEmpty()) {
            // ältere Runs desselben Dokuments durchsuchen
            for (ProcessingRunRow r : ledger.findRuns(source.id())) {
                archived = ledger.artifacts(r.id()).stream().filter(a -> a.type() == ArtifactType.SOURCE_PDF).findFirst();
                if (archived.isPresent()) {
                    break;
                }
            }
        }
        Path p = archived.map(a -> layout.archiveRoot().resolve(a.path()))
                .orElseThrow(() -> new ReprocessException("Kein archiviertes Original für " + source.originalFilename(), false));
        if (!Files.isRegularFile(p)) {
            throw new ReprocessException("Archiviertes Original fehlt: " + p, false);
        }
        return p;
    }

    private String details(Map<String, ?> map) {
        try {
            return json.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return String.valueOf(map);
        }
    }
}
