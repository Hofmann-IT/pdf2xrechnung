package de.hofmannit.erechnung.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import de.hofmannit.erechnung.ledger.Rows.EventRow;

import org.junit.jupiter.api.Test;

class StatusProjectionTest {

    @Test
    void derivesStatusFromHistory() {
        assertThat(StatusProjection.derive(List.of())).isEqualTo(InvoiceStatus.PROCESSING);
        assertThat(StatusProjection.derive(events(EventType.PROCESSING_STARTED, EventType.EXTRACTION_COMPLETED))).isEqualTo(InvoiceStatus.PROCESSING);
        assertThat(StatusProjection.derive(events(EventType.PROCESSING_STARTED, EventType.EXTRACTION_COMPLETED, EventType.REVIEW_REQUIRED))).isEqualTo(InvoiceStatus.REVIEW);
        assertThat(StatusProjection.derive(events(EventType.PROCESSING_STARTED, EventType.GENERATION_COMPLETED, EventType.VALIDATION_FAILED))).isEqualTo(InvoiceStatus.FAILED);
        assertThat(StatusProjection.derive(events(EventType.PROCESSING_STARTED, EventType.VALIDATION_SUCCEEDED))).isEqualTo(InvoiceStatus.VALID);
        assertThat(StatusProjection.derive(events(EventType.VALIDATION_SUCCEEDED, EventType.ARCHIVED))).isEqualTo(InvoiceStatus.ARCHIVED);
        assertThat(StatusProjection.derive(events(EventType.ARCHIVED, EventType.DISPATCH_ATTEMPTED))).isEqualTo(InvoiceStatus.DISPATCH_PENDING);
        assertThat(StatusProjection.derive(events(EventType.ARCHIVED, EventType.DISPATCH_ATTEMPTED, EventType.DISPATCH_SUCCEEDED))).isEqualTo(InvoiceStatus.DISPATCHED);
        assertThat(StatusProjection.derive(events(EventType.ARCHIVED, EventType.DISPATCH_ATTEMPTED, EventType.DISPATCH_FAILED))).isEqualTo(InvoiceStatus.DISPATCH_FAILED);
        assertThat(StatusProjection.derive(events(EventType.PROCESSING_STARTED, EventType.REJECTED))).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(StatusProjection.derive(events(EventType.ARCHIVED, EventType.DUPLICATE_DETECTED))).isEqualTo(InvoiceStatus.ARCHIVED);
    }

    private static List<EventRow> events(EventType... types) {
        List<EventRow> rows = new ArrayList<>();
        long id = 1;
        for (EventType t : types) {
            rows.add(new EventRow(id++, 1, t, Instant.EPOCH, "system", null, null));
        }
        return rows;
    }
}
