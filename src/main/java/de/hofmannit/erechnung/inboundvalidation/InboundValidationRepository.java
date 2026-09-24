package de.hofmannit.erechnung.inboundvalidation;

import java.time.Instant;

import de.hofmannit.erechnung.validation.ValidationReport;
import de.hofmannit.erechnung.validation.ValidatorKind;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Append-only Prüfprotokoll der Eingangs-Validierung (Tabelle {@code inbound_validation},
 * Trigger verhindern UPDATE/DELETE). Eigene Tabelle ohne Bezug zur Ausgangs-Pipeline.
 */
@Repository
public class InboundValidationRepository {

    private final JdbcTemplate jdbc;

    public InboundValidationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(InboundValidationResult r, String storedDirectory, String validatedBy) {
        ValidationReport kosit = r.report(ValidatorKind.KOSIT);
        ValidationReport mustang = r.report(ValidatorKind.MUSTANG);
        jdbc.update("""
                INSERT INTO inbound_validation (sha256, original_filename, size_bytes, document_type, detected_profile, detected_version,
                  ruleset, overall_outcome, kosit_outcome, mustang_outcome, error_count, warning_count, stored_directory, validated_at, validated_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                r.sha256(), r.originalFilename(), r.sizeBytes(), r.documentType().name(), r.profileName(), r.xrechnungVersion(),
                String.join("; ", r.rulesets()), r.overall().name(),
                kosit == null ? null : kosit.outcome().name(), mustang == null ? null : mustang.outcome().name(),
                r.errors().size(), r.warnings().size(), storedDirectory, Instant.from(r.validatedAt()).toString(), validatedBy);
    }

    public int count(String sha256) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM inbound_validation WHERE sha256 = ?", Integer.class, sha256);
        return n == null ? 0 : n;
    }
}
