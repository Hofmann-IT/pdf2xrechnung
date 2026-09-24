package de.hofmannit.erechnung.ledger;

import java.util.List;

import de.hofmannit.erechnung.ledger.Rows.EventRow;

/**
 * Leitet den angezeigten Status (Vorgabe Abschnitt 19) aus der Event-Historie eines Runs ab.
 * Der Status wird nie gespeichert.
 *
 * <p>Regeln: Das letzte statusrelevante Event bestimmt den Status. Versand-Events überlagern
 * ARCHIVED; ein fehlgeschlagener Versand nach erfolgreichem Versand bleibt DISPATCH_FAILED
 * (die Historie zeigt beide).
 */
public final class StatusProjection {

    private StatusProjection() {
    }

    public static InvoiceStatus derive(List<EventRow> events) {
        InvoiceStatus status = InvoiceStatus.PROCESSING;
        for (EventRow e : events) {
            switch (e.type()) {
                case PROCESSING_STARTED, REPROCESS_STARTED, EXTRACTION_COMPLETED, PLAUSIBILITY_PASSED, GENERATION_COMPLETED,
                     REPROCESS_REQUESTED, DUPLICATE_DETECTED, REPROCESS_COMPLETED -> {
                    // kein Statuswechsel
                }
                case REVIEW_REQUIRED -> status = InvoiceStatus.REVIEW;
                case VALIDATION_FAILED, PROCESSING_FAILED -> status = InvoiceStatus.FAILED;
                case VALIDATION_SUCCEEDED -> status = InvoiceStatus.VALID;
                case ARCHIVED -> status = InvoiceStatus.ARCHIVED;
                case DISPATCH_ATTEMPTED -> status = InvoiceStatus.DISPATCH_PENDING;
                case DISPATCH_SUCCEEDED -> status = InvoiceStatus.DISPATCHED;
                case DISPATCH_FAILED -> status = InvoiceStatus.DISPATCH_FAILED;
                case REJECTED -> status = InvoiceStatus.REJECTED;
            }
        }
        return status;
    }
}
