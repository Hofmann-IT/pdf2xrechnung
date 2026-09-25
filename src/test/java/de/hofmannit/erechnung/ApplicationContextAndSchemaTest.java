package de.hofmannit.erechnung;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.ApplicationVersion;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Startet den vollständigen Anwendungskontext gegen eine temporäre SQLite-Datenbank und prüft
 * das Flyway-Basisschema inklusive der Append-only-Trigger. Es werden ausschließlich
 * temporäre Verzeichnisse verwendet (Vorgabe Abschnitt 41).
 *
 * <p>Das Verzeichnis liegt bewusst unter {@code target/} statt in einem JUnit-{@code @TempDir}:
 * Der von Spring gecachte Kontext hält die SQLite-Datei bis zum JVM-Ende offen, sodass JUnit
 * das Verzeichnis unter Windows nicht löschen könnte. {@code mvn clean} räumt es auf.
 */
@SpringBootTest
class ApplicationContextAndSchemaTest {

    static final Path tempDir = createBuildTempDir();

    private static Path createBuildTempDir() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "context-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void temporaryDirectories(DynamicPropertyRegistry registry) {
        registry.add("app.directories.data", () -> tempDir.resolve("data").toString());
        registry.add("app.directories.inbox", () -> tempDir.resolve("inbox").toString());
        registry.add("app.directories.processing", () -> tempDir.resolve("processing").toString());
        registry.add("app.directories.output", () -> tempDir.resolve("output").toString());
        registry.add("app.directories.failed", () -> tempDir.resolve("failed").toString());
        registry.add("app.directories.manual-review", () -> tempDir.resolve("manual-review").toString());
        registry.add("app.directories.rejected", () -> tempDir.resolve("rejected").toString());
        registry.add("app.directories.archive", () -> tempDir.resolve("archive").toString());
        registry.add("app.directories.inbound-validation", () -> tempDir.resolve("inbound-validation").toString());
        registry.add("app.logging.directory", () -> tempDir.resolve("logs").toString());
        registry.add("app.watcher.enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AppProperties appProperties;

    @Autowired
    ProfileRegistry profileRegistry;

    @Autowired
    ApplicationVersion applicationVersion;

    @Test
    void contextLoadsWithProjectConfigurationAndProfiles() {
        assertThat(appProperties.directories().data()).isEqualTo(tempDir.resolve("data"));
        assertThat(appProperties.watcher().stableChecks()).isEqualTo(2);
        assertThat(appProperties.smtp().enabled()).isFalse();
        assertThat(profileRegistry.profiles()).containsKey("standard");
        assertThat(profileRegistry.tenants()).extracting(t -> t.id()).containsExactly("hofmann-it");
        assertThat(profileRegistry.tenant("hofmann-it").orElseThrow().fixedValues())
                .containsEntry("seller-name", "Hofmann IT")
                .containsEntry("payment-means-type-code", "58");
        assertThat(applicationVersion.value()).isNotBlank();
    }

    @Test
    void flywayAppliedBaseSchema() {
        Integer version = jdbc.queryForObject(
                "SELECT MAX(version) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(version).isEqualTo(4);

        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", String.class);
        assertThat(tables).contains("source_document", "processing_run", "ledger_entry", "ledger_tax_line",
                "processing_event", "artifact", "validation_result", "inbound_validation",
                "export_settings", "invoice_export_field", "export_log", "belegtransfer_transfer", "app_settings");
        List<String> settingsColumns = jdbc.query("PRAGMA table_info(export_settings)", (rs, i) -> rs.getString("name"));
        assertThat(settingsColumns).contains("belegtransfer_enabled", "belegtransfer_directory");
        List<String> ledgerColumns = jdbc.query("PRAGMA table_info(ledger_entry)", (rs, i) -> rs.getString("name"));
        assertThat(ledgerColumns).contains("due_date", "delivery_date", "buyer_vat_id", "buyer_id");

        Integer foreignKeys = jdbc.queryForObject("PRAGMA foreign_keys", Integer.class);
        assertThat(foreignKeys).as("PRAGMA foreign_keys").isEqualTo(1);
    }

    @Test
    void sourceDocumentIsUniquePerTenantAndSha() {
        String sha = "a".repeat(64);
        jdbc.update("INSERT INTO source_document (tenant_id, sha256, original_filename, size_bytes, first_seen_at) VALUES (?,?,?,?,?)",
                "t1", sha, "re.pdf", 10, "2026-09-24T10:00:00Z");
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO source_document (tenant_id, sha256, original_filename, size_bytes, first_seen_at) VALUES (?,?,?,?,?)",
                "t1", sha, "re-kopie.pdf", 10, "2026-09-24T10:01:00Z"))
                .isInstanceOf(DataAccessException.class);
        // gleicher Hash bei anderem Mandanten ist erlaubt
        jdbc.update("INSERT INTO source_document (tenant_id, sha256, original_filename, size_bytes, first_seen_at) VALUES (?,?,?,?,?)",
                "t2", sha, "re.pdf", 10, "2026-09-24T10:02:00Z");
    }

    @Test
    void appendOnlyTablesRejectUpdateAndDelete() {
        long sourceId = insertSource("b".repeat(64));
        long runId = insertRun(sourceId, 1);

        jdbc.update("INSERT INTO processing_event (processing_run_id, event_type, occurred_at, actor, message) VALUES (?,?,?,?,?)",
                runId, "PROCESSING_STARTED", "2026-09-24T10:00:00Z", "system", "start");
        long eventId = jdbc.queryForObject("SELECT MAX(id) FROM processing_event", Long.class);

        assertThatThrownBy(() -> jdbc.update("UPDATE processing_event SET message = 'x' WHERE id = ?", eventId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM processing_event WHERE id = ?", eventId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        jdbc.update("INSERT INTO artifact (processing_run_id, artifact_type, path, sha256, size_bytes, created_at) VALUES (?,?,?,?,?,?)",
                runId, "SOURCE_PDF", "archive/2026/09/RE-1/run-001/original.pdf", "c".repeat(64), 10, "2026-09-24T10:00:00Z");
        assertThatThrownBy(() -> jdbc.update("UPDATE artifact SET path = 'other' WHERE processing_run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM artifact WHERE processing_run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        jdbc.update("""
                INSERT INTO ledger_entry (processing_run_id, tenant_id, document_type, invoice_number, invoice_date,
                  customer_name, currency, net_total, tax_total, gross_total, payable_amount, generated_formats,
                  source_sha256, profile_name, profile_hash, application_version, recorded_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                runId, "t1", "INVOICE", "RE-1", "2026-09-24", "Kunde", "EUR", "100.00", "19.00", "119.00", "119.00",
                "[\"XRECHNUNG_CII\"]", "b".repeat(64), "standard", "d".repeat(64), "test", "2026-09-24T10:00:00Z");
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entry SET gross_total = '0.00' WHERE processing_run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entry WHERE processing_run_id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update("DELETE FROM source_document WHERE id = ?", sourceId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void v2ExportTablesAreAppendOnly() {
        long sourceId = insertSource("f".repeat(64));
        jdbc.update("""
                INSERT INTO export_settings (tenant_id, created_at, created_by, datev_enabled, consultant_number, client_number,
                  fiscal_year_start, account_length, chart_of_accounts, debtor_strategy, collective_debtor_account,
                  customer_accounts_json, revenue_accounts_json, origin, exported_by, dictation_shortcut, lock_records, booking_text_template)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                "t1", "2026-09-24T10:00:00Z", "uwe", 1, "1001", "1", "01-01", 4, "03", "COLLECTIVE", "10000", "{}", "{}", "RE", "", "", 1, "Rechnung {invoiceNumber}");
        long settingsId = jdbc.queryForObject("SELECT MAX(id) FROM export_settings", Long.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE export_settings SET client_number = '2' WHERE id = ?", settingsId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM export_settings WHERE id = ?", settingsId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        // Sachkontenlänge außerhalb 4–8 wird von der Datenbank abgewiesen
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO export_settings (tenant_id, created_at, created_by, datev_enabled, fiscal_year_start, account_length,
                  chart_of_accounts, debtor_strategy, customer_accounts_json, revenue_accounts_json, origin, exported_by,
                  dictation_shortcut, lock_records, booking_text_template)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                "t1", "2026-09-24T10:00:00Z", "uwe", 1, "01-01", 9, "03", "COLLECTIVE", "{}", "{}", "RE", "", "", 1, "x"))
                .isInstanceOf(DataAccessException.class);

        // Leistungsdatum (#115) nur zusammen mit #116
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO invoice_export_field (source_document_id, created_at, created_by, service_date) VALUES (?,?,?,?)",
                sourceId, "2026-09-24T10:00:00Z", "uwe", "2026-09-01"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("INSERT INTO invoice_export_field (source_document_id, created_at, created_by, service_date, tax_period_date) VALUES (?,?,?,?,?)",
                sourceId, "2026-09-24T10:00:00Z", "uwe", "2026-09-01", "2026-09-01");
        long fieldId = jdbc.queryForObject("SELECT MAX(id) FROM invoice_export_field", Long.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE invoice_export_field SET due_date = '2026-10-01' WHERE id = ?", fieldId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM invoice_export_field WHERE id = ?", fieldId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

        jdbc.update("""
                INSERT INTO export_log (tenant_id, variant, date_from, date_to, file_name, sha256, size_bytes, record_count, invoice_count,
                  skipped_count, created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                "t1", "CSV", null, null, "x.csv", "a".repeat(64), 10, 1, 1, 0, "2026-09-24T10:00:00Z", "uwe");
        long logId = jdbc.queryForObject("SELECT MAX(id) FROM export_log", Long.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM export_log WHERE id = ?", logId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO export_log (tenant_id, variant, file_name, sha256, size_bytes, record_count, invoice_count, skipped_count,
                  created_at, created_by) VALUES (?,?,?,?,?,?,?,?,?,?)""",
                "t1", "UNBEKANNT", "x.csv", "a".repeat(64), 10, 1, 1, 0, "2026-09-24T10:00:00Z", "uwe"))
                .isInstanceOf(DataAccessException.class);

        // V3: Belegtransfer-Protokoll append-only, Ergebnis nur aus der festen Liste
        long runId = insertRun(sourceId, 1);
        jdbc.update("INSERT INTO belegtransfer_transfer (processing_run_id, outcome, message, actor, created_at) VALUES (?,?,?,?,?)",
                runId, "COPIED", "Kopiert", "system", "2026-09-24T10:00:00Z");
        long transferId = jdbc.queryForObject("SELECT MAX(id) FROM belegtransfer_transfer", Long.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE belegtransfer_transfer SET outcome = 'FAILED' WHERE id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM belegtransfer_transfer WHERE id = ?", transferId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO belegtransfer_transfer (processing_run_id, outcome, message, actor, created_at) VALUES (?,?,?,?,?)",
                runId, "DONE", "x", "system", "2026-09-24T10:00:00Z"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void processingRunCanOnlyBeFinishedOnce() {
        long sourceId = insertSource("e".repeat(64));
        long runId = insertRun(sourceId, 1);

        // Fachliche Spalten sind unveränderlich
        assertThatThrownBy(() -> jdbc.update("UPDATE processing_run SET profile_name = 'x' WHERE id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("unveraenderlich");

        // Einmaliger Abschluss ist erlaubt
        int updated = jdbc.update("UPDATE processing_run SET finished_at = ?, result = ? WHERE id = ?",
                "2026-09-24T10:05:00Z", "SUCCESS", runId);
        assertThat(updated).isEqualTo(1);

        // Danach keine Änderung mehr
        assertThatThrownBy(() -> jdbc.update("UPDATE processing_run SET result = 'FAILED' WHERE id = ?", runId))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("unveraenderlich");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM processing_run WHERE id = ?", runId))
                .isInstanceOf(DataAccessException.class);

        // Reprocess verlangt parent_run_id, requested_by und reason
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO processing_run (source_document_id, run_number, trigger_type, profile_name, profile_hash,
                  application_version, correlation_id, started_at) VALUES (?,?,?,?,?,?,?,?)""",
                sourceId, 2, "MANUAL_REPROCESS", "standard", "d".repeat(64), "test", "eeeeeeee/run-002", "2026-09-24T11:00:00Z"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("""
                INSERT INTO processing_run (source_document_id, run_number, trigger_type, parent_run_id, requested_by, reason,
                  profile_name, profile_hash, application_version, correlation_id, started_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                sourceId, 2, "MANUAL_REPROCESS", runId, "uwe", "Profil korrigiert", "standard", "d".repeat(64), "test",
                "eeeeeeee/run-002", "2026-09-24T11:00:00Z");

        // Run-Nummer je Quelldokument eindeutig
        assertThatThrownBy(() -> insertRun(sourceId, 2)).isInstanceOf(DataAccessException.class);

        // Wiederanlauf: RESTART_RECOVERY verlangt einen Vorgänger, aber keinen Benutzer
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO processing_run (source_document_id, run_number, trigger_type, profile_name, profile_hash,
                  application_version, correlation_id, started_at) VALUES (?,?,?,?,?,?,?,?)""",
                sourceId, 3, "RESTART_RECOVERY", "standard", "d".repeat(64), "test", "eeeeeeee/run-003", "2026-09-24T12:00:00Z"))
                .isInstanceOf(DataAccessException.class);
        jdbc.update("""
                INSERT INTO processing_run (source_document_id, run_number, trigger_type, parent_run_id, profile_name, profile_hash,
                  application_version, correlation_id, started_at) VALUES (?,?,?,?,?,?,?,?,?)""",
                sourceId, 3, "RESTART_RECOVERY", runId, "standard", "d".repeat(64), "test", "eeeeeeee/run-003", "2026-09-24T12:00:00Z");
    }

    private long insertSource(String sha) {
        jdbc.update("INSERT INTO source_document (tenant_id, sha256, original_filename, size_bytes, first_seen_at) VALUES (?,?,?,?,?)",
                "t1", sha, "re.pdf", 10, "2026-09-24T10:00:00Z");
        return jdbc.queryForObject("SELECT id FROM source_document WHERE tenant_id = 't1' AND sha256 = ?", Long.class, sha);
    }

    private long insertRun(long sourceId, int runNumber) {
        jdbc.update("""
                INSERT INTO processing_run (source_document_id, run_number, trigger_type, profile_name, profile_hash,
                  application_version, correlation_id, started_at) VALUES (?,?,?,?,?,?,?,?)""",
                sourceId, runNumber, "AUTO", "standard", "d".repeat(64), "test", "bbbbbbbb/run-00" + runNumber, "2026-09-24T10:00:00Z");
        return jdbc.queryForObject("SELECT id FROM processing_run WHERE source_document_id = ? AND run_number = ?",
                Long.class, sourceId, runNumber);
    }
}
