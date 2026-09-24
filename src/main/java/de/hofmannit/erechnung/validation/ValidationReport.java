package de.hofmannit.erechnung.validation;

import java.time.Instant;
import java.util.List;

import de.hofmannit.erechnung.model.OutputFormat;

/**
 * Ergebnis eines Validators für ein Dokument (Vorgabe Abschnitte 11–13).
 *
 * @param validator   KoSIT oder Mustang
 * @param target      geprüftes Format
 * @param mandatory   ob der Validator für dieses Format verpflichtend ist
 * @param outcome     Gesamtergebnis
 * @param ruleset     verwendetes Regelwerk (z. B. KoSIT-Szenarioname)
 * @param findings    alle Meldungen (Fehler, Warnungen, Hinweise)
 * @param reportXml   XML-Report (immer, sofern der Validator lief)
 * @param reportHtml  HTML-Report, sofern lokal erzeugbar, sonst {@code null}
 * @param validatedAt Zeitpunkt
 * @param message     technische Meldung bei ERROR/NOT_APPLICABLE
 */
public record ValidationReport(
        ValidatorKind validator,
        OutputFormat target,
        boolean mandatory,
        ValidationOutcome outcome,
        String ruleset,
        List<ValidationFinding> findings,
        byte[] reportXml,
        byte[] reportHtml,
        Instant validatedAt,
        String message) {

    public ValidationReport {
        findings = List.copyOf(findings);
    }

    public long errorCount() {
        return findings.stream().filter(f -> f.severity() == ValidationFinding.Severity.ERROR).count();
    }

    public long warningCount() {
        return findings.stream().filter(f -> f.severity() == ValidationFinding.Severity.WARNING).count();
    }

    /** Ein verpflichtender Validator blockiert, wenn er nicht VALID liefert. */
    public boolean blocks() {
        return mandatory && outcome != ValidationOutcome.VALID;
    }
}
