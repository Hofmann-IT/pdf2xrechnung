package de.hofmannit.erechnung.inboundvalidation;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import de.hofmannit.erechnung.validation.ValidationFinding;
import de.hofmannit.erechnung.validation.ValidationReport;

/**
 * Erzeugt aus einem {@link InboundValidationResult} die menschenlesbare Zusammenfassung
 * (Text) und einen eigenständigen HTML-Report (Vorgabe Abschnitt 13 "Reports"). Die
 * Original-Reports der Validatoren (XML, KoSIT-HTML) werden unverändert daneben abgelegt.
 */
public final class InboundReportWriter {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);

    private InboundReportWriter() {
    }

    public static byte[] summaryText(InboundValidationResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("E-Rechnung prüfen – Zusammenfassung\n");
        sb.append("====================================\n\n");
        sb.append("Ergebnis:            ").append(r.isValid() ? "GÜLTIG" : r.overall() == de.hofmannit.erechnung.validation.ValidationOutcome.INVALID ? "NICHT GÜLTIG" : "NICHT PRÜFBAR").append('\n');
        sb.append("Datei:               ").append(r.originalFilename()).append(" (").append(r.sizeBytes()).append(" Bytes)\n");
        sb.append("SHA-256:             ").append(r.sha256()).append('\n');
        sb.append("Geprüft am:          ").append(TS.format(r.validatedAt().atZone(ZoneId.systemDefault()))).append('\n');
        sb.append("Dokumenttyp:         ").append(describeType(r.documentType())).append('\n');
        sb.append("Syntax:              ").append(r.syntax() == null ? "–" : r.syntax()).append('\n');
        sb.append("Profil/Standard:     ").append(r.profileName()).append('\n');
        sb.append("Kennung (BT-24):     ").append(r.customizationId() == null ? "–" : r.customizationId()).append('\n');
        sb.append("Prozess (BT-23):     ").append(r.processId() == null ? "–" : r.processId()).append('\n');
        if (r.documentType() == InboundDocumentType.ZUGFERD_PDF || r.documentType() == InboundDocumentType.PDF_WITHOUT_XML) {
            sb.append("Eingebettete XML:    ").append(r.embeddedXmlPresent() ? "ja (" + r.embeddedXmlFilename() + ")" : "nein").append('\n');
            sb.append("Anhänge:             ").append(r.attachmentNames().isEmpty() ? "keine" : String.join(", ", r.attachmentNames())).append('\n');
        }
        if (r.message() != null) {
            sb.append("Hinweis:             ").append(r.message()).append('\n');
        }
        sb.append("\nValidatoren\n-----------\n");
        for (ValidationReport rep : r.reports()) {
            sb.append(rep.validator()).append(rep.mandatory() ? " (verpflichtend)" : " (zusätzlich)").append(": ").append(rep.outcome());
            if (rep.ruleset() != null) {
                sb.append(" – Regelwerk: ").append(rep.ruleset());
            }
            sb.append(" – Fehler: ").append(rep.errorCount()).append(", Warnungen: ").append(rep.warningCount());
            if (rep.message() != null) {
                sb.append(" – ").append(rep.message());
            }
            sb.append('\n');
        }
        appendFindings(sb, "Fehler", r.errors());
        appendFindings(sb, "Warnungen", r.warnings());
        appendFindings(sb, "Hinweise", r.informations());
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendFindings(StringBuilder sb, String title, List<ValidationFinding> findings) {
        sb.append('\n').append(title).append(" (").append(findings.size()).append(")\n");
        sb.append("-".repeat(title.length() + 4)).append('\n');
        if (findings.isEmpty()) {
            sb.append("keine\n");
        }
        for (ValidationFinding f : findings) {
            sb.append("- ");
            if (f.ruleId() != null) {
                sb.append('[').append(f.ruleId()).append("] ");
            }
            sb.append(f.description() == null ? f.originalMessage() : f.description()).append('\n');
            if (f.location() != null) {
                sb.append("    Position: ").append(f.location()).append('\n');
            }
        }
    }

    public static byte[] html(InboundValidationResult r) {
        StringBuilder sb = new StringBuilder();
        boolean valid = r.isValid();
        String status = valid ? "E-Rechnung gültig" : r.overall() == de.hofmannit.erechnung.validation.ValidationOutcome.INVALID
                ? "E-Rechnung nicht gültig" : "E-Rechnung nicht prüfbar";
        String icon = valid ? "&#10003;" : "&#10007;";
        sb.append("<!DOCTYPE html><html lang=\"de\"><head><meta charset=\"utf-8\"><title>Prüfbericht ").append(esc(r.originalFilename()))
                .append("</title><style>body{font-family:Segoe UI,Arial,sans-serif;margin:2rem;color:#102A43;background:#F5F9FC}")
                .append(".status{padding:1rem 1.5rem;border-radius:12px;font-size:1.4rem;font-weight:600;color:#fff;background:")
                .append(valid ? "#0B7A4B" : "#A83232").append("}")
                .append("table{border-collapse:collapse;width:100%;background:#fff;margin:1rem 0}td,th{border:1px solid #d5dee6;padding:.4rem .6rem;text-align:left;vertical-align:top}")
                .append("th{background:#DDF8FB}code{word-break:break-all}h2{margin-top:2rem;color:#0B3A63}</style></head><body>");
        sb.append("<div class=\"status\">").append(icon).append(' ').append(status).append("</div>");
        sb.append("<h2>Dokument</h2><table>");
        row(sb, "Datei", r.originalFilename() + " (" + r.sizeBytes() + " Bytes)");
        row(sb, "SHA-256", "<code>" + esc(r.sha256()) + "</code>");
        row(sb, "Geprüft am", TS.format(r.validatedAt().atZone(ZoneId.systemDefault())));
        row(sb, "Dokumenttyp", describeType(r.documentType()));
        row(sb, "Syntax", r.syntax() == null ? "–" : r.syntax());
        row(sb, "Profil/Standard", r.profileName());
        row(sb, "Kennung (BT-24)", r.customizationId() == null ? "–" : "<code>" + esc(r.customizationId()) + "</code>");
        row(sb, "Prozess (BT-23)", r.processId() == null ? "–" : "<code>" + esc(r.processId()) + "</code>");
        if (r.documentType() == InboundDocumentType.ZUGFERD_PDF || r.documentType() == InboundDocumentType.PDF_WITHOUT_XML) {
            row(sb, "Eingebettete XML", r.embeddedXmlPresent() ? "ja (" + esc(r.embeddedXmlFilename()) + ")" : "nein");
            row(sb, "Anhänge", r.attachmentNames().isEmpty() ? "keine" : esc(String.join(", ", r.attachmentNames())));
        }
        if (r.message() != null) {
            row(sb, "Hinweis", esc(r.message()));
        }
        sb.append("</table><h2>Validatoren</h2><table><tr><th>Validator</th><th>Pflicht</th><th>Ergebnis</th><th>Regelwerk</th><th>Fehler</th><th>Warnungen</th><th>Meldung</th></tr>");
        for (ValidationReport rep : r.reports()) {
            sb.append("<tr><td>").append(rep.validator()).append("</td><td>").append(rep.mandatory() ? "ja" : "nein")
                    .append("</td><td>").append(rep.outcome()).append("</td><td>").append(esc(rep.ruleset() == null ? "–" : rep.ruleset()))
                    .append("</td><td>").append(rep.errorCount()).append("</td><td>").append(rep.warningCount())
                    .append("</td><td>").append(esc(rep.message() == null ? "" : rep.message())).append("</td></tr>");
        }
        sb.append("</table>");
        findingsTable(sb, "Fehler", r.errors());
        findingsTable(sb, "Warnungen", r.warnings());
        findingsTable(sb, "Hinweise", r.informations());
        sb.append("<p><small>Die Prüfung erfolgte vollständig lokal. Das Original wurde nicht verändert.</small></p></body></html>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void findingsTable(StringBuilder sb, String title, List<ValidationFinding> findings) {
        sb.append("<h2>").append(title).append(" (").append(findings.size()).append(")</h2>");
        if (findings.isEmpty()) {
            sb.append("<p>keine</p>");
            return;
        }
        sb.append("<table><tr><th>Schweregrad</th><th>Regel</th><th>Beschreibung</th><th>Position</th><th>Technische Meldung</th></tr>");
        for (ValidationFinding f : findings) {
            sb.append("<tr><td>").append(f.severity()).append("</td><td>").append(esc(f.ruleId() == null ? "–" : f.ruleId()))
                    .append("</td><td>").append(esc(f.description() == null ? "" : f.description()))
                    .append("</td><td><code>").append(esc(f.location() == null ? "–" : f.location()))
                    .append("</code></td><td><code>").append(esc(f.originalMessage() == null ? "" : f.originalMessage())).append("</code></td></tr>");
        }
        sb.append("</table>");
    }

    private static void row(StringBuilder sb, String k, String v) {
        sb.append("<tr><th>").append(k).append("</th><td>").append(v).append("</td></tr>");
    }

    /** Lesbare Bezeichnung des Dokumenttyps (auch für die Oberfläche). */
    public static String describeType(InboundDocumentType t) {
        return switch (t) {
            case CII_XML -> "XML, UN/CEFACT CII (CrossIndustryInvoice)";
            case UBL_XML -> "XML, OASIS UBL (Invoice/CreditNote)";
            case ZUGFERD_PDF -> "PDF mit eingebetteter E-Rechnungs-XML (ZUGFeRD/Factur-X)";
            case PDF_WITHOUT_XML -> "PDF ohne eingebettete E-Rechnungs-XML";
            case UNKNOWN_XML -> "XML, keine bekannte EN-16931-Syntax";
            case MALFORMED_XML -> "XML nicht wohlgeformt";
            case UNSUPPORTED -> "nicht unterstütztes Format";
        };
    }

    static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
