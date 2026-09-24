package de.hofmannit.erechnung.ledger;

/**
 * Append-only Events eines Processing-Runs (Vorgabe Abschnitt 18). Events werden niemals
 * geändert oder gelöscht.
 */
public enum EventType {
    PROCESSING_STARTED,
    EXTRACTION_COMPLETED,
    PLAUSIBILITY_PASSED,
    REVIEW_REQUIRED,
    GENERATION_COMPLETED,
    VALIDATION_FAILED,
    VALIDATION_SUCCEEDED,
    ARCHIVED,
    DISPATCH_ATTEMPTED,
    DISPATCH_SUCCEEDED,
    DISPATCH_FAILED,
    REPROCESS_REQUESTED,
    REPROCESS_STARTED,
    REPROCESS_COMPLETED,
    /** Ergänzend zur Vorgabe: Klassifizierung als Nicht-Rechnung, Datei nach {@code rejected/}. */
    REJECTED,
    /** Ergänzend zur Vorgabe: erneutes Auftreten eines bereits verarbeiteten Quelldokuments. */
    DUPLICATE_DETECTED,
    /** Ergänzend zur Vorgabe: technischer Abbruch außerhalb der Validierung (z. B. PDF nicht lesbar). */
    PROCESSING_FAILED
}
