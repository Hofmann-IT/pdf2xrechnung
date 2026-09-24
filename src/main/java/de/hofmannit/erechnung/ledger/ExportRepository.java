package de.hofmannit.erechnung.ledger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.hofmannit.erechnung.ledger.Rows.ExportLogRow;
import de.hofmannit.erechnung.ledger.Rows.ExportSettingsRow;
import de.hofmannit.erechnung.ledger.Rows.InvoiceExportFieldRow;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/**
 * Zugriff auf die V2-Tabellen {@code export_settings}, {@code invoice_export_field} und
 * {@code export_log}. Alle drei sind append-only; „aktuell" ist stets der jüngste Datensatz
 * (höchste id) je Mandant bzw. Quelldokument.
 */
@Repository
public class ExportRepository {

    private final JdbcTemplate jdbc;

    public ExportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ export_settings

    public ExportSettingsRow saveSettings(ExportSettingsRow s) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO export_settings (tenant_id, created_at, created_by, note, datev_enabled, consultant_number, client_number,
                      fiscal_year_start, account_length, chart_of_accounts, debtor_strategy, collective_debtor_account,
                      customer_accounts_json, revenue_accounts_json, origin, exported_by, dictation_shortcut, lock_records,
                      booking_text_template)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, s.tenantId());
            ps.setString(2, s.createdAt().toString());
            ps.setString(3, s.createdBy());
            ps.setString(4, s.note());
            ps.setInt(5, s.datevEnabled() ? 1 : 0);
            ps.setString(6, s.consultantNumber());
            ps.setString(7, s.clientNumber());
            ps.setString(8, s.fiscalYearStart());
            ps.setInt(9, s.accountLength());
            ps.setString(10, s.chartOfAccounts());
            ps.setString(11, s.debtorStrategy());
            ps.setString(12, s.collectiveDebtorAccount());
            ps.setString(13, s.customerAccountsJson());
            ps.setString(14, s.revenueAccountsJson());
            ps.setString(15, s.origin());
            ps.setString(16, s.exportedBy());
            ps.setString(17, s.dictationShortcut());
            ps.setInt(18, s.lockRecords() ? 1 : 0);
            ps.setString(19, s.bookingTextTemplate());
            return ps;
        });
        return new ExportSettingsRow(id, s.tenantId(), s.createdAt(), s.createdBy(), s.note(), s.datevEnabled(), s.consultantNumber(),
                s.clientNumber(), s.fiscalYearStart(), s.accountLength(), s.chartOfAccounts(), s.debtorStrategy(),
                s.collectiveDebtorAccount(), s.customerAccountsJson(), s.revenueAccountsJson(), s.origin(), s.exportedBy(),
                s.dictationShortcut(), s.lockRecords(), s.bookingTextTemplate());
    }

    /** Jüngste Einstellungen des Mandanten oder leer, wenn nie in der Oberfläche gespeichert. */
    public Optional<ExportSettingsRow> latestSettings(String tenantId) {
        return jdbc.query("SELECT * FROM export_settings WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", SETTINGS, tenantId)
                .stream().findFirst();
    }

    public List<ExportSettingsRow> settingsHistory(String tenantId) {
        return jdbc.query("SELECT * FROM export_settings WHERE tenant_id = ? ORDER BY id DESC", SETTINGS, tenantId);
    }

    // ------------------------------------------------------------------ invoice_export_field

    public InvoiceExportFieldRow saveInvoiceFields(InvoiceExportFieldRow f) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO invoice_export_field (source_document_id, created_at, created_by, service_date, tax_period_date,
                      due_date, buyer_vat_id, note)
                    VALUES (?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, f.sourceDocumentId());
            ps.setString(2, f.createdAt().toString());
            ps.setString(3, f.createdBy());
            ps.setString(4, f.serviceDate());
            ps.setString(5, f.taxPeriodDate());
            ps.setString(6, f.dueDate());
            ps.setString(7, f.buyerVatId());
            ps.setString(8, f.note());
            return ps;
        });
        return new InvoiceExportFieldRow(id, f.sourceDocumentId(), f.createdAt(), f.createdBy(), f.serviceDate(), f.taxPeriodDate(),
                f.dueDate(), f.buyerVatId(), f.note());
    }

    /** Jüngste Ergänzung zu einem Quelldokument (überdauert Reprocess-Runs). */
    public Optional<InvoiceExportFieldRow> latestInvoiceFields(long sourceDocumentId) {
        return jdbc.query("SELECT * FROM invoice_export_field WHERE source_document_id = ? ORDER BY id DESC LIMIT 1", FIELD, sourceDocumentId)
                .stream().findFirst();
    }

    public List<InvoiceExportFieldRow> invoiceFieldHistory(long sourceDocumentId) {
        return jdbc.query("SELECT * FROM invoice_export_field WHERE source_document_id = ? ORDER BY id DESC", FIELD, sourceDocumentId);
    }

    // ------------------------------------------------------------------ export_log

    public ExportLogRow logExport(ExportLogRow l) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO export_log (tenant_id, variant, date_from, date_to, file_name, sha256, size_bytes, record_count,
                      invoice_count, skipped_count, created_at, created_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, l.tenantId());
            ps.setString(2, l.variant());
            ps.setString(3, l.dateFrom());
            ps.setString(4, l.dateTo());
            ps.setString(5, l.fileName());
            ps.setString(6, l.sha256());
            ps.setLong(7, l.sizeBytes());
            ps.setInt(8, l.recordCount());
            ps.setInt(9, l.invoiceCount());
            ps.setInt(10, l.skippedCount());
            ps.setString(11, l.createdAt().toString());
            ps.setString(12, l.createdBy());
            return ps;
        });
        return new ExportLogRow(id, l.tenantId(), l.variant(), l.dateFrom(), l.dateTo(), l.fileName(), l.sha256(), l.sizeBytes(),
                l.recordCount(), l.invoiceCount(), l.skippedCount(), l.createdAt(), l.createdBy());
    }

    public List<ExportLogRow> exportLog(String tenantId, int limit) {
        return jdbc.query("SELECT * FROM export_log WHERE tenant_id = ? ORDER BY id DESC LIMIT ?", LOG, tenantId, limit);
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private long insert(org.springframework.jdbc.core.PreparedStatementCreator creator) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(creator, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("Kein generierter Schlüssel erhalten");
        }
        return key.longValue();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String s = rs.getString(column);
        return s == null ? null : Instant.parse(s);
    }

    private static final RowMapper<ExportSettingsRow> SETTINGS = (rs, i) -> new ExportSettingsRow(
            rs.getLong("id"), rs.getString("tenant_id"), instant(rs, "created_at"), rs.getString("created_by"), rs.getString("note"),
            rs.getInt("datev_enabled") == 1, rs.getString("consultant_number"), rs.getString("client_number"),
            rs.getString("fiscal_year_start"), rs.getInt("account_length"), rs.getString("chart_of_accounts"),
            rs.getString("debtor_strategy"), rs.getString("collective_debtor_account"), rs.getString("customer_accounts_json"),
            rs.getString("revenue_accounts_json"), rs.getString("origin"), rs.getString("exported_by"), rs.getString("dictation_shortcut"),
            rs.getInt("lock_records") == 1, rs.getString("booking_text_template"));

    private static final RowMapper<InvoiceExportFieldRow> FIELD = (rs, i) -> new InvoiceExportFieldRow(
            rs.getLong("id"), rs.getLong("source_document_id"), instant(rs, "created_at"), rs.getString("created_by"),
            rs.getString("service_date"), rs.getString("tax_period_date"), rs.getString("due_date"), rs.getString("buyer_vat_id"),
            rs.getString("note"));

    private static final RowMapper<ExportLogRow> LOG = (rs, i) -> new ExportLogRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("variant"), rs.getString("date_from"), rs.getString("date_to"),
            rs.getString("file_name"), rs.getString("sha256"), rs.getLong("size_bytes"), rs.getInt("record_count"),
            rs.getInt("invoice_count"), rs.getInt("skipped_count"), instant(rs, "created_at"), rs.getString("created_by"));
}
