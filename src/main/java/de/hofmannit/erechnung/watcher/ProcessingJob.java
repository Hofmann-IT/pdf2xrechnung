package de.hofmannit.erechnung.watcher;

import java.nio.file.Path;

import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.ledger.RunTrigger;

/**
 * Auftrag für die Pipeline: eine Datei in {@code processing/} eines Mandanten.
 *
 * @param tenant           Mandant
 * @param file             Datei in processing/ (wird von der Pipeline verschoben)
 * @param originalFilename ursprünglicher Dateiname aus der Inbox
 * @param sha256           SHA-256 der Datei
 * @param trigger          Auslöser
 * @param parentRunId      Vorgänger-Run bei Reprocess/Wiederanlauf
 * @param requestedBy      Benutzer bei manuellem Reprocess
 * @param reason           Begründung bei manuellem Reprocess
 */
public record ProcessingJob(Tenant tenant, Path file, String originalFilename, String sha256, RunTrigger trigger,
                            Long parentRunId, String requestedBy, String reason) {

    public static ProcessingJob auto(Tenant tenant, Path file, String originalFilename, String sha256) {
        return new ProcessingJob(tenant, file, originalFilename, sha256, RunTrigger.AUTO, null, null, null);
    }

    public static ProcessingJob recovery(Tenant tenant, Path file, String originalFilename, String sha256, long parentRunId) {
        return new ProcessingJob(tenant, file, originalFilename, sha256, RunTrigger.RESTART_RECOVERY, parentRunId, null, null);
    }
}
