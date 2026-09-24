package de.hofmannit.erechnung.ledger;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import de.hofmannit.erechnung.ledger.Rows.ArtifactRow;
import de.hofmannit.erechnung.ledger.Rows.EventRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.Rows.ValidationResultRow;
import de.hofmannit.erechnung.model.ArtifactType;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Zugriff auf Quelldokumente, Runs, Ledger, Events, Artefakte und Validierungsergebnisse
 * (Spring JDBC, SQLite). Es gibt genau ein UPDATE: den einmaligen Abschluss eines Runs.
 * Alle anderen Tabellen sind append-only (durch Trigger erzwungen, ADR 0003).
 */
@Repository
public class LedgerRepository {

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ source_document

    public Optional<SourceDocumentRow> findSource(String tenantId, String sha256) {
        List<SourceDocumentRow> rows = jdbc.query(
                "SELECT * FROM source_document WHERE tenant_id = ? AND sha256 = ?", SOURCE, tenantId, sha256);
        return rows.stream().findFirst();
    }

    public Optional<SourceDocumentRow> findSourceById(long id) {
        return jdbc.query("SELECT * FROM source_document WHERE id = ?", SOURCE, id).stream().findFirst();
    }

    public SourceDocumentRow createSource(String tenantId, String sha256, String originalFilename, long sizeBytes, Instant firstSeenAt) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO source_document (tenant_id, sha256, original_filename, size_bytes, first_seen_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, tenantId);
            ps.setString(2, sha256);
            ps.setString(3, originalFilename);
            ps.setLong(4, sizeBytes);
            ps.setString(5, firstSeenAt.toString());
            return ps;
        });
        return new SourceDocumentRow(id, tenantId, sha256, originalFilename, sizeBytes, firstSeenAt);
    }

    // ------------------------------------------------------------------ processing_run

    public List<ProcessingRunRow> findRuns(long sourceDocumentId) {
        return jdbc.query("SELECT * FROM processing_run WHERE source_document_id = ? ORDER BY run_number", RUN, sourceDocumentId);
    }

    public Optional<ProcessingRunRow> findRun(long id) {
        return jdbc.query("SELECT * FROM processing_run WHERE id = ?", RUN, id).stream().findFirst();
    }

    public Optional<ProcessingRunRow> findOpenRun(long sourceDocumentId) {
        return jdbc.query("SELECT * FROM processing_run WHERE source_document_id = ? AND finished_at IS NULL ORDER BY run_number DESC",
                RUN, sourceDocumentId).stream().findFirst();
    }

    public Optional<ProcessingRunRow> findLatestRun(long sourceDocumentId) {
        return jdbc.query("SELECT * FROM processing_run WHERE source_document_id = ? ORDER BY run_number DESC", RUN, sourceDocumentId)
                .stream().findFirst();
    }

    public int nextRunNumber(long sourceDocumentId) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(run_number), 0) FROM processing_run WHERE source_document_id = ?",
                Integer.class, sourceDocumentId);
        return (max == null ? 0 : max) + 1;
    }

    public ProcessingRunRow createRun(long sourceDocumentId, int runNumber, RunTrigger trigger, Long parentRunId, String requestedBy,
                                      String reason, String profileName, String profileHash, String applicationVersion,
                                      String correlationId, Instant startedAt) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO processing_run (source_document_id, run_number, trigger_type, parent_run_id, requested_by, reason,
                      profile_name, profile_hash, application_version, correlation_id, started_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, sourceDocumentId);
            ps.setInt(2, runNumber);
            ps.setString(3, trigger.name());
            if (parentRunId == null) {
                ps.setNull(4, java.sql.Types.INTEGER);
            } else {
                ps.setLong(4, parentRunId);
            }
            ps.setString(5, requestedBy);
            ps.setString(6, reason);
            ps.setString(7, profileName);
            ps.setString(8, profileHash);
            ps.setString(9, applicationVersion);
            ps.setString(10, correlationId);
            ps.setString(11, startedAt.toString());
            return ps;
        });
        return new ProcessingRunRow(id, sourceDocumentId, runNumber, trigger, parentRunId, requestedBy, reason, profileName,
                profileHash, applicationVersion, correlationId, startedAt, null, null);
    }

    /** Einmaliger Abschluss; ein zweiter Aufruf wird vom Datenbank-Trigger abgewiesen. */
    public void finishRun(long runId, Instant finishedAt, RunResult result) {
        int updated = jdbc.update("UPDATE processing_run SET finished_at = ?, result = ? WHERE id = ?",
                finishedAt.toString(), result.name(), runId);
        if (updated != 1) {
            throw new IllegalStateException("Run " + runId + " konnte nicht abgeschlossen werden");
        }
    }

    // ------------------------------------------------------------------ processing_event

    public EventRow appendEvent(long runId, EventType type, Instant occurredAt, String actor, String message, String detailsJson) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO processing_event (processing_run_id, event_type, occurred_at, actor, message, details_json) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runId);
            ps.setString(2, type.name());
            ps.setString(3, occurredAt.toString());
            ps.setString(4, actor == null ? "system" : actor);
            ps.setString(5, message);
            ps.setString(6, detailsJson);
            return ps;
        });
        return new EventRow(id, runId, type, occurredAt, actor, message, detailsJson);
    }

    public List<EventRow> events(long runId) {
        return jdbc.query("SELECT * FROM processing_event WHERE processing_run_id = ? ORDER BY id", EVENT, runId);
    }

    // ------------------------------------------------------------------ artifact

    public ArtifactRow createArtifact(long runId, ArtifactType type, String path, String sha256, long sizeBytes, Instant createdAt) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO artifact (processing_run_id, artifact_type, path, sha256, size_bytes, created_at) VALUES (?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, runId);
            ps.setString(2, type.name());
            ps.setString(3, path);
            ps.setString(4, sha256);
            ps.setLong(5, sizeBytes);
            ps.setString(6, createdAt.toString());
            return ps;
        });
        return new ArtifactRow(id, runId, type, path, sha256, sizeBytes, createdAt);
    }

    public List<ArtifactRow> artifacts(long runId) {
        return jdbc.query("SELECT * FROM artifact WHERE processing_run_id = ? ORDER BY id", ARTIFACT, runId);
    }

    // ------------------------------------------------------------------ ledger_entry

    @Transactional
    public long createLedgerEntry(LedgerEntryRow e, List<LedgerTaxLineRow> taxLines) {
        long id = insert(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO ledger_entry (processing_run_id, tenant_id, document_type, invoice_number, invoice_date, customer_name,
                      currency, net_total, tax_total, gross_total, payable_amount, generated_formats, source_sha256, profile_name,
                      profile_hash, application_version, business_case, recorded_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, e.processingRunId());
            ps.setString(2, e.tenantId());
            ps.setString(3, e.documentType());
            ps.setString(4, e.invoiceNumber());
            ps.setString(5, e.invoiceDate());
            ps.setString(6, e.customerName());
            ps.setString(7, e.currency());
            ps.setString(8, e.netTotal());
            ps.setString(9, e.taxTotal());
            ps.setString(10, e.grossTotal());
            ps.setString(11, e.payableAmount());
            ps.setString(12, e.generatedFormats());
            ps.setString(13, e.sourceSha256());
            ps.setString(14, e.profileName());
            ps.setString(15, e.profileHash());
            ps.setString(16, e.applicationVersion());
            ps.setString(17, e.businessCase());
            ps.setString(18, e.recordedAt().toString());
            return ps;
        });
        for (LedgerTaxLineRow t : taxLines) {
            jdbc.update("INSERT INTO ledger_tax_line (ledger_entry_id, vat_category_code, vat_rate, taxable_amount, tax_amount) VALUES (?,?,?,?,?)",
                    id, t.vatCategoryCode(), t.vatRate(), t.taxableAmount(), t.taxAmount());
        }
        return id;
    }

    public Optional<LedgerEntryRow> ledgerEntry(long runId) {
        return jdbc.query("SELECT * FROM ledger_entry WHERE processing_run_id = ?", LEDGER, runId).stream().findFirst();
    }

    public List<LedgerTaxLineRow> taxLines(long ledgerEntryId) {
        return jdbc.query("SELECT * FROM ledger_tax_line WHERE ledger_entry_id = ? ORDER BY id", TAX, ledgerEntryId);
    }

    // ------------------------------------------------------------------ validation_result

    public long createValidationResult(ValidationResultRow r) {
        return insert(con -> {
            PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO validation_result (processing_run_id, validated_artifact_id, validator, target_format, outcome, ruleset,
                      error_count, warning_count, report_xml_artifact_id, report_html_artifact_id, validated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)""", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, r.processingRunId());
            setNullableLong(ps, 2, r.validatedArtifactId());
            ps.setString(3, r.validator());
            ps.setString(4, r.targetFormat());
            ps.setString(5, r.outcome());
            ps.setString(6, r.ruleset());
            ps.setInt(7, r.errorCount());
            ps.setInt(8, r.warningCount());
            setNullableLong(ps, 9, r.reportXmlArtifactId());
            setNullableLong(ps, 10, r.reportHtmlArtifactId());
            ps.setString(11, r.validatedAt().toString());
            return ps;
        });
    }

    public List<ValidationResultRow> validationResults(long runId) {
        return jdbc.query("SELECT * FROM validation_result WHERE processing_run_id = ? ORDER BY id", VALIDATION, runId);
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

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setLong(index, value);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        String s = rs.getString(column);
        return s == null ? null : Instant.parse(s);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long v = rs.getLong(column);
        return rs.wasNull() ? null : v;
    }

    private static final RowMapper<SourceDocumentRow> SOURCE = (rs, i) -> new SourceDocumentRow(
            rs.getLong("id"), rs.getString("tenant_id"), rs.getString("sha256"), rs.getString("original_filename"),
            rs.getLong("size_bytes"), instant(rs, "first_seen_at"));

    private static final RowMapper<ProcessingRunRow> RUN = (rs, i) -> new ProcessingRunRow(
            rs.getLong("id"), rs.getLong("source_document_id"), rs.getInt("run_number"), RunTrigger.valueOf(rs.getString("trigger_type")),
            nullableLong(rs, "parent_run_id"), rs.getString("requested_by"), rs.getString("reason"), rs.getString("profile_name"),
            rs.getString("profile_hash"), rs.getString("application_version"), rs.getString("correlation_id"),
            instant(rs, "started_at"), instant(rs, "finished_at"),
            rs.getString("result") == null ? null : RunResult.valueOf(rs.getString("result")));

    private static final RowMapper<EventRow> EVENT = (rs, i) -> new EventRow(
            rs.getLong("id"), rs.getLong("processing_run_id"), EventType.valueOf(rs.getString("event_type")),
            instant(rs, "occurred_at"), rs.getString("actor"), rs.getString("message"), rs.getString("details_json"));

    private static final RowMapper<ArtifactRow> ARTIFACT = (rs, i) -> new ArtifactRow(
            rs.getLong("id"), rs.getLong("processing_run_id"), ArtifactType.valueOf(rs.getString("artifact_type")),
            rs.getString("path"), rs.getString("sha256"), rs.getLong("size_bytes"), instant(rs, "created_at"));

    private static final RowMapper<LedgerEntryRow> LEDGER = (rs, i) -> new LedgerEntryRow(
            rs.getLong("id"), rs.getLong("processing_run_id"), rs.getString("tenant_id"), rs.getString("document_type"),
            rs.getString("invoice_number"), rs.getString("invoice_date"), rs.getString("customer_name"), rs.getString("currency"),
            rs.getString("net_total"), rs.getString("tax_total"), rs.getString("gross_total"), rs.getString("payable_amount"),
            rs.getString("generated_formats"), rs.getString("source_sha256"), rs.getString("profile_name"), rs.getString("profile_hash"),
            rs.getString("application_version"), rs.getString("business_case"), instant(rs, "recorded_at"));

    private static final RowMapper<LedgerTaxLineRow> TAX = (rs, i) -> new LedgerTaxLineRow(
            rs.getLong("id"), rs.getLong("ledger_entry_id"), rs.getString("vat_category_code"), rs.getString("vat_rate"),
            rs.getString("taxable_amount"), rs.getString("tax_amount"));

    private static final RowMapper<ValidationResultRow> VALIDATION = (rs, i) -> new ValidationResultRow(
            rs.getLong("id"), rs.getLong("processing_run_id"), nullableLong(rs, "validated_artifact_id"), rs.getString("validator"),
            rs.getString("target_format"), rs.getString("outcome"), rs.getString("ruleset"), rs.getInt("error_count"),
            rs.getInt("warning_count"), nullableLong(rs, "report_xml_artifact_id"), nullableLong(rs, "report_html_artifact_id"),
            instant(rs, "validated_at"));
}
