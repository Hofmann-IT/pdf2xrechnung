package de.hofmannit.erechnung.ledger;

/**
 * Endergebnis eines Processing-Runs. Wird genau einmal beim Abschluss des Runs gesetzt
 * (Datenbank-Trigger verhindern jede spätere Änderung).
 */
public enum RunResult {
    /** Erzeugung und alle verpflichtenden Validierungen erfolgreich, Artefakte abgelegt. */
    SUCCESS,
    /** Plausibilitätsabweichung, Datei in {@code manual-review/}. */
    REVIEW,
    /** Erzeugung/Validierung fehlgeschlagen, Datei in {@code failed/}. */
    FAILED,
    /** Als Nicht-Rechnung klassifiziert, Datei in {@code rejected/}. */
    REJECTED,
    /** Quelldokument bereits verarbeitet (Duplikat durch den Watcher). */
    DUPLICATE
}
