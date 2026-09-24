package de.hofmannit.erechnung.validation;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import de.hofmannit.erechnung.security.SecureXml;
import de.hofmannit.erechnung.validation.ValidationFinding.Severity;

import org.mustangproject.validator.ZUGFeRDValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Mustang-Validierung für ZUGFeRD/Factur-X-PDFs (PDF/A-3 über veraPDF, eingebettete XML,
 * Profil-Schematron) und XRechnung-XML (ADR 0002).
 *
 * <p>Verifizierte API ({@code org.mustangproject:validator:2.17.0}):
 * {@code ZUGFeRDValidator.validate(String)}, {@code wasCompletelyValid()}. Der zurückgegebene
 * XML-Report hat die Struktur {@code <validation><pdf>…<summary status/></pdf>
 * <xml><messages><error|warning|notice type location criterion>…</messages><summary status/></xml>
 * <summary status="valid|invalid"/></validation>} (im Probelauf beobachtet).
 */
@Component
public class MustangValidator {

    private static final Logger log = LoggerFactory.getLogger(MustangValidator.class);

    public ValidationReport validate(String target, boolean mandatory, Path file) {
        Instant now = Instant.now();
        try {
            ZUGFeRDValidator validator = new ZUGFeRDValidator();
            String report = validator.validate(file.toString());
            if (report == null || report.isBlank()) {
                return new ValidationReport(ValidatorKind.MUSTANG, target, mandatory, ValidationOutcome.ERROR, null,
                        List.of(), null, null, now, "Mustang lieferte keinen Report");
            }
            byte[] xml = report.getBytes(StandardCharsets.UTF_8);
            Document doc = SecureXml.documentBuilderFactory().newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            List<ValidationFinding> findings = new ArrayList<>();
            NodeList messages = doc.getElementsByTagName("messages");
            for (int i = 0; i < messages.getLength(); i++) {
                NodeList children = messages.item(i).getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    Node n = children.item(j);
                    if (n.getNodeType() != Node.ELEMENT_NODE) {
                        continue;
                    }
                    Element el = (Element) n;
                    Severity sev = switch (el.getTagName()) {
                        case "error", "fatal", "exception" -> Severity.ERROR;
                        case "warning" -> Severity.WARNING;
                        default -> Severity.INFORMATION;
                    };
                    String text = el.getTextContent() == null ? "" : el.getTextContent().trim();
                    String ruleId = extractRuleId(text);
                    String location = el.getAttribute("location");
                    String criterion = el.getAttribute("criterion");
                    findings.add(new ValidationFinding(sev, ruleId, text, location.isEmpty() ? null : location,
                            criterion.isEmpty() ? null : criterion, text + " [type " + el.getAttribute("type") + "]"));
                }
            }
            String status = rootSummaryStatus(doc);
            boolean completelyValid = validator.wasCompletelyValid();
            ValidationOutcome outcome;
            String message = null;
            if (status == null) {
                outcome = ValidationOutcome.ERROR;
                message = "Mustang-Report ohne Gesamtstatus";
            } else if ("valid".equalsIgnoreCase(status) && completelyValid) {
                outcome = ValidationOutcome.VALID;
            } else {
                outcome = ValidationOutcome.INVALID;
                if (!"valid".equalsIgnoreCase(status)) {
                    message = "Mustang-Gesamtstatus: " + status;
                }
            }
            String ruleset = ruleset(doc);
            return new ValidationReport(ValidatorKind.MUSTANG, target, mandatory, outcome, ruleset, findings, xml, null, now, message);
        } catch (Exception e) {
            log.error("Mustang-Validierung fehlgeschlagen für {}: {}", file.getFileName(), e.toString());
            return new ValidationReport(ValidatorKind.MUSTANG, target, mandatory, ValidationOutcome.ERROR, null, List.of(),
                    null, null, now, "Mustang-Validierung fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Gesamtstatus = letztes summary-Element direkt unter dem Wurzelelement. */
    private static String rootSummaryStatus(Document doc) {
        Element root = doc.getDocumentElement();
        NodeList children = root.getChildNodes();
        String status = null;
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && "summary".equals(n.getNodeName())) {
                status = ((Element) n).getAttribute("status");
            }
        }
        return status == null || status.isEmpty() ? null : status;
    }

    private static String ruleset(Document doc) {
        NodeList profile = doc.getElementsByTagName("profile");
        NodeList version = doc.getElementsByTagName("version");
        StringBuilder sb = new StringBuilder("Mustang 2.17.0");
        if (profile.getLength() > 0) {
            sb.append(" Profil ").append(profile.item(0).getTextContent().trim());
        }
        if (version.getLength() > 0) {
            sb.append(" Version ").append(version.item(0).getTextContent().trim());
        }
        return sb.toString();
    }

    /** Extrahiert "[ID BR-DE-19]" aus der Meldung, sofern vorhanden. */
    static String extractRuleId(String text) {
        int idx = text.indexOf("[ID ");
        if (idx < 0) {
            return null;
        }
        int end = text.indexOf(']', idx);
        return end < 0 ? null : text.substring(idx + 4, end).trim();
    }
}
