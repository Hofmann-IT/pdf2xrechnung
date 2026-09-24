package de.hofmannit.erechnung.ledger;

import java.time.Instant;

import de.hofmannit.erechnung.model.ArtifactType;

/** Datensätze der Ledger-Tabellen (unveränderlich, 1:1 zum Schema V1/V2). */
public final class Rows {

    private Rows() {
    }

    public record SourceDocumentRow(long id, String tenantId, String sha256, String originalFilename, long sizeBytes, Instant firstSeenAt) {
    }

    public record ProcessingRunRow(long id, long sourceDocumentId, int runNumber, RunTrigger trigger, Long parentRunId,
                                   String requestedBy, String reason, String profileName, String profileHash,
                                   String applicationVersion, String correlationId, Instant startedAt, Instant finishedAt,
                                   RunResult result) {
        public boolean isOpen() {
            return finishedAt == null;
        }
    }

    public record EventRow(long id, long processingRunId, EventType type, Instant occurredAt, String actor, String message, String detailsJson) {
    }

    public record ArtifactRow(long id, long processingRunId, ArtifactType type, String path, String sha256, long sizeBytes, Instant createdAt) {
        public String fileName() {
            int idx = path.lastIndexOf('/');
            return idx < 0 ? path : path.substring(idx + 1);
        }
    }

    /**
     * Ledger-Eintrag. Die Felder {@code dueDate} (BT-9), {@code deliveryDate} (BT-72),
     * {@code buyerVatId} (BT-48) und {@code buyerId} (BT-46) kamen mit V2 hinzu und sind nullable.
     */
    public record LedgerEntryRow(long id, long processingRunId, String tenantId, String documentType, String invoiceNumber,
                                 String invoiceDate, String customerName, String currency, String netTotal, String taxTotal,
                                 String grossTotal, String payableAmount, String generatedFormats, String sourceSha256,
                                 String profileName, String profileHash, String applicationVersion, String businessCase,
                                 String dueDate, String deliveryDate, String buyerVatId, String buyerId,
                                 Instant recordedAt) {
    }

    /** Export-Einstellungen je Mandant (V2, append-only; der jüngste Datensatz gilt). Maps als JSON-Text. */
    public record ExportSettingsRow(long id, String tenantId, Instant createdAt, String createdBy, String note,
                                    boolean datevEnabled, String consultantNumber, String clientNumber, String fiscalYearStart,
                                    int accountLength, String chartOfAccounts, String debtorStrategy, String collectiveDebtorAccount,
                                    String customerAccountsJson, String revenueAccountsJson, String origin, String exportedBy,
                                    String dictationShortcut, boolean lockRecords, String bookingTextTemplate) {
    }

    /** Vom Benutzer je Rechnung (Quelldokument) ergänzte DATEV-Felder (V2, append-only). Daten als ISO-Datum. */
    public record InvoiceExportFieldRow(long id, long sourceDocumentId, Instant createdAt, String createdBy,
                                        String serviceDate, String taxPeriodDate, String dueDate, String buyerVatId, String note) {
    }

    /** Protokoll eines heruntergeladenen Exports (V2, append-only). */
    public record ExportLogRow(long id, String tenantId, String variant, String dateFrom, String dateTo, String fileName,
                               String sha256, long sizeBytes, int recordCount, int invoiceCount, int skippedCount,
                               Instant createdAt, String createdBy) {
    }

    public record LedgerTaxLineRow(long id, long ledgerEntryId, String vatCategoryCode, String vatRate, String taxableAmount, String taxAmount) {
    }

    public record ValidationResultRow(long id, long processingRunId, Long validatedArtifactId, String validator, String targetFormat,
                                      String outcome, String ruleset, int errorCount, int warningCount,
                                      Long reportXmlArtifactId, Long reportHtmlArtifactId, Instant validatedAt) {
    }

    /**
     * Listenzeile für die Oberfläche: Run + Quelldokument + (optional) Ledger-Eintrag.
     * Der Status wird aus den Events projiziert.
     */
    public record InvoiceListRow(ProcessingRunRow run, SourceDocumentRow source, LedgerEntryRow entry, InvoiceStatus status) {
        public InvoiceListRow withStatus(InvoiceStatus s) {
            return new InvoiceListRow(run, source, entry, s);
        }
    }

    /** Filter für die Rechnungsliste; alle Felder optional. */
    public record InvoiceFilter(String tenantId, String search, String status, String format, String dateFrom, String dateTo,
                                String customer, int limit) {
        public static InvoiceFilter none() {
            return new InvoiceFilter(null, null, null, null, null, null, null, 200);
        }
    }
}
