package de.hofmannit.erechnung.inboundvalidation;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import de.hofmannit.erechnung.validation.ValidationFinding;
import de.hofmannit.erechnung.validation.ValidationOutcome;
import de.hofmannit.erechnung.validation.ValidationReport;
import de.hofmannit.erechnung.validation.ValidatorKind;

/**
 * Ergebnis der Prüfung einer empfangenen E-Rechnung (Vorgabe Abschnitte 1B, 13, 29).
 *
 * @param originalFilename  hochgeladener Dateiname
 * @param sizeBytes         Größe
 * @param sha256            SHA-256 des unveränderten Originals
 * @param documentType      erkannter Dokumenttyp
 * @param syntax            CII oder UBL, sonst {@code null}
 * @param customizationId   BT-24, sofern vorhanden
 * @param processId         BT-23, sofern vorhanden
 * @param profileName       aufgelöste Bezeichnung oder "nicht eindeutig bestimmbar"
 * @param profileKnown      ob die Kennung eindeutig einem bekannten Standard/Profil zugeordnet wurde
 * @param xrechnungVersion  Version aus der XRechnung-Kennung, sonst {@code null}
 * @param embeddedXmlPresent bei PDF: eingebettete E-Rechnungs-XML vorhanden
 * @param embeddedXmlFilename bei PDF: Dateiname der eingebetteten XML
 * @param attachmentNames   bei PDF: alle eingebetteten Dateinamen
 * @param overall           Gesamtergebnis: VALID, INVALID oder ERROR (nicht prüfbar)
 * @param reports           Ergebnisse je Validator (getrennt)
 * @param validatedAt       Zeitpunkt
 * @param message           Meldung bei ERROR (z. B. "Keine eingebettete XML")
 * @param storedDirectory   Ablageverzeichnis, wenn Prüfprotokolle gespeichert werden, sonst {@code null}
 */
public record InboundValidationResult(
        String originalFilename,
        long sizeBytes,
        String sha256,
        InboundDocumentType documentType,
        String syntax,
        String customizationId,
        String processId,
        String profileName,
        boolean profileKnown,
        String xrechnungVersion,
        boolean embeddedXmlPresent,
        String embeddedXmlFilename,
        List<String> attachmentNames,
        ValidationOutcome overall,
        List<ValidationReport> reports,
        Instant validatedAt,
        String message,
        Path storedDirectory) {

    public InboundValidationResult {
        reports = List.copyOf(reports);
        attachmentNames = List.copyOf(attachmentNames);
    }

    public boolean isValid() {
        return overall == ValidationOutcome.VALID;
    }

    public List<ValidationFinding> errors() {
        return reports.stream().flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == ValidationFinding.Severity.ERROR).toList();
    }

    public List<ValidationFinding> warnings() {
        return reports.stream().flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == ValidationFinding.Severity.WARNING).toList();
    }

    public List<ValidationFinding> informations() {
        return reports.stream().flatMap(r -> r.findings().stream())
                .filter(f -> f.severity() == ValidationFinding.Severity.INFORMATION).toList();
    }

    public ValidationReport report(ValidatorKind kind) {
        return reports.stream().filter(r -> r.validator() == kind).findFirst().orElse(null);
    }

    /** Verwendete Regelwerke aller Validatoren, die gelaufen sind. */
    public List<String> rulesets() {
        return reports.stream().filter(r -> r.ruleset() != null).map(r -> r.validator() + ": " + r.ruleset()).toList();
    }

    public InboundValidationResult withStoredDirectory(Path dir) {
        return new InboundValidationResult(originalFilename, sizeBytes, sha256, documentType, syntax, customizationId, processId,
                profileName, profileKnown, xrechnungVersion, embeddedXmlPresent, embeddedXmlFilename, attachmentNames, overall,
                reports, validatedAt, message, dir);
    }
}
