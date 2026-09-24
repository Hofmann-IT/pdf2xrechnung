package de.hofmannit.erechnung.ledger;

/**
 * In der Web-UI angezeigter Status (Vorgabe Abschnitt 19). Der Status ist eine Projektion
 * der Event-Historie und wird nicht gespeichert.
 */
public enum InvoiceStatus {
    PROCESSING,
    REVIEW,
    FAILED,
    VALID,
    ARCHIVED,
    DISPATCH_PENDING,
    DISPATCHED,
    DISPATCH_FAILED,
    /** Ergänzend: als Nicht-Rechnung abgewiesen. */
    REJECTED
}
