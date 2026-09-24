package de.hofmannit.erechnung.watcher;

import de.hofmannit.erechnung.ledger.RunResult;

/**
 * Ergebnis eines Pipeline-Laufs.
 *
 * @param runId     Processing-Run
 * @param runNumber Run-Nummer
 * @param result    Endergebnis
 * @param message   lesbare Zusammenfassung (Begründung bei REVIEW/FAILED/REJECTED)
 */
public record RunOutcome(long runId, int runNumber, RunResult result, String message) {
}
