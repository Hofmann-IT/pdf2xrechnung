package de.hofmannit.erechnung.export;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.TenantProperties.Belegtransfer;
import de.hofmannit.erechnung.export.ExportSettingsService.Effective;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ArtifactRow;
import de.hofmannit.erechnung.ledger.Rows.BelegtransferRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.model.ArtifactType;
import de.hofmannit.erechnung.security.Sha256;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Übergabe der ZUGFeRD-PDF an DATEV Belegtransfer (ADR 0011): Kopie aus dem Archiv in das
 * konfigurierte lokale Verzeichnis, das der DATEV-Client überwacht und selbst hochlädt. Diese
 * Anwendung baut keine Netzwerkverbindung auf. Es wird nie überschrieben; jede Übergabe wird
 * mit Ergebnis in {@code belegtransfer_transfer} festgehalten. Fehler unterbrechen die Pipeline nicht.
 */
@Service
public class BelegtransferService {

    private static final Logger log = LoggerFactory.getLogger(BelegtransferService.class);
    /** Grenze laut DATEV Belegtransfer (Dok.-Nr. 1020025): 20 MB je Datei. */
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    public enum Outcome { COPIED, SKIPPED, FAILED }

    private final LedgerRepository ledger;
    private final ExportRepository exportRepository;
    private final ExportSettingsService settings;
    private final DirectoryLayout layout;
    private final Clock clock;

    public BelegtransferService(LedgerRepository ledger, ExportRepository exportRepository, ExportSettingsService settings,
                                DirectoryLayout layout, Clock clock) {
        this.ledger = ledger;
        this.exportRepository = exportRepository;
        this.settings = settings;
        this.layout = layout;
        this.clock = clock;
    }

    /** Automatische Übergabe nach erfolgreicher Erstverarbeitung; ohne aktivierte Konfiguration passiert nichts. */
    public Optional<BelegtransferRow> afterSuccessfulRun(long runId) {
        try {
            ProcessingRunRow run = ledger.findRun(runId).orElseThrow();
            SourceDocumentRow source = ledger.findSourceById(run.sourceDocumentId()).orElseThrow();
            Effective eff = settings.effective(source.tenantId());
            if (!eff.belegtransferEnabled()) {
                return Optional.empty();
            }
            return Optional.of(transfer(run, eff.belegtransfer(), "system"));
        } catch (RuntimeException e) {
            log.error("Belegtransfer für Run {} nicht möglich", runId, e);
            return Optional.empty();
        }
    }

    /** Manuelle (erneute) Übergabe als Benutzeraktion. */
    public BelegtransferRow transferManually(long runId, String actor) throws ExportException {
        if (actor == null || actor.isBlank()) {
            throw new ExportException("Benutzername ist erforderlich");
        }
        ProcessingRunRow run = ledger.findRun(runId).orElseThrow(() -> new ExportException("Run " + runId + " existiert nicht"));
        SourceDocumentRow source = ledger.findSourceById(run.sourceDocumentId()).orElseThrow();
        Effective eff = settings.effective(source.tenantId());
        if (!eff.belegtransferEnabled()) {
            throw new ExportException("Belegtransfer ist für Mandant " + source.tenantId() + " nicht aktiviert (Export-Einstellungen)");
        }
        if (run.result() != RunResult.SUCCESS) {
            throw new ExportException("Nur erfolgreich verarbeitete Runs können übergeben werden (Ergebnis: " + run.result() + ")");
        }
        return transfer(run, eff.belegtransfer(), actor.trim());
    }

    private BelegtransferRow transfer(ProcessingRunRow run, Belegtransfer cfg, String actor) {
        List<ArtifactRow> artifacts = ledger.artifacts(run.id());
        ArtifactRow zugferd = artifacts.stream().filter(a -> a.type() == ArtifactType.ZUGFERD_PDF).findFirst().orElse(null);
        if (zugferd == null) {
            return record(run.id(), null, null, null, Outcome.SKIPPED, "Keine ZUGFeRD-PDF in diesem Run (Profil erzeugt kein ZUGFeRD)", actor);
        }
        Path directory = Path.of(cfg.directory().trim());
        if (!Files.isDirectory(directory)) {
            return record(run.id(), zugferd.id(), null, zugferd.sha256(), Outcome.FAILED,
                    "Belegtransfer-Verzeichnis existiert nicht oder ist kein Verzeichnis: " + directory, actor);
        }
        Path source = layout.archiveRoot().resolve(zugferd.path()).normalize();
        if (!source.startsWith(layout.archiveRoot()) || !Files.isRegularFile(source)) {
            return record(run.id(), zugferd.id(), null, zugferd.sha256(), Outcome.FAILED, "Archivdatei nicht vorhanden: " + zugferd.path(), actor);
        }
        if (zugferd.sizeBytes() > MAX_FILE_BYTES) {
            return record(run.id(), zugferd.id(), null, zugferd.sha256(), Outcome.FAILED,
                    "Datei größer als 20 MB; DATEV Belegtransfer überträgt höchstens 20 MB je Datei", actor);
        }
        try {
            String fileName = zugferd.fileName();
            Path target = directory.resolve(fileName);
            if (Files.exists(target)) {
                if (Sha256.ofFile(target).equals(zugferd.sha256())) {
                    return record(run.id(), zugferd.id(), target.toString(), zugferd.sha256(), Outcome.SKIPPED,
                            "Identische Datei liegt bereits im Belegtransfer-Verzeichnis", actor);
                }
                // Anderer Inhalt unter gleichem Namen: nie überschreiben, eindeutigen Namen wählen
                target = directory.resolve(withSuffix(fileName, "_" + zugferd.sha256().substring(0, 8)));
                if (Files.exists(target)) {
                    if (Sha256.ofFile(target).equals(zugferd.sha256())) {
                        return record(run.id(), zugferd.id(), target.toString(), zugferd.sha256(), Outcome.SKIPPED,
                                "Identische Datei liegt bereits im Belegtransfer-Verzeichnis", actor);
                    }
                    return record(run.id(), zugferd.id(), target.toString(), zugferd.sha256(), Outcome.FAILED,
                            "Zieldatei existiert mit anderem Inhalt und wird nicht überschrieben: " + target, actor);
                }
            }
            // Erst unter temporärem Namen kopieren, dann umbenennen, damit der DATEV-Client keine halbe Datei liest
            Path temp = directory.resolve("." + target.getFileName() + ".part");
            Files.copy(source, temp);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomic) {
                Files.move(temp, target);
            }
            log.info("Belegtransfer: {} → {}", zugferd.path(), target);
            return record(run.id(), zugferd.id(), target.toString(), zugferd.sha256(), Outcome.COPIED, "Kopiert nach " + target, actor);
        } catch (IOException e) {
            return record(run.id(), zugferd.id(), null, zugferd.sha256(), Outcome.FAILED, "Kopieren fehlgeschlagen: " + e.getMessage(), actor);
        }
    }

    private BelegtransferRow record(long runId, Long artifactId, String targetPath, String sha256, Outcome outcome, String message, String actor) {
        if (outcome != Outcome.COPIED) {
            log.warn("Belegtransfer für Run {}: {} – {}", runId, outcome, message);
        }
        return exportRepository.saveTransfer(new BelegtransferRow(0, runId, artifactId, targetPath, sha256, outcome.name(), message, actor, clock.instant()));
    }

    static String withSuffix(String fileName, String suffix) {
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? fileName + suffix : fileName.substring(0, dot) + suffix + fileName.substring(dot);
    }
}
