package de.hofmannit.erechnung.validation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.Transformer;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.security.SecureXml;
import de.hofmannit.erechnung.validation.ValidationFinding.Severity;

import de.kosit.validationtool.api.Check;
import de.kosit.validationtool.api.Configuration;
import de.kosit.validationtool.api.Input;
import de.kosit.validationtool.api.InputFactory;
import de.kosit.validationtool.api.Result;
import de.kosit.validationtool.api.XmlError;
import de.kosit.validationtool.impl.DefaultCheck;
import de.kosit.validationtool.impl.ResolvingMode;
import net.sf.saxon.s9api.Processor;
import org.oclc.purl.dsdl.svrl.FailedAssert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * KoSIT-Validator als lokal eingebundene Bibliothek (ADR 0002). Die Szenariokonfiguration
 * wird beim ersten Aufruf aus dem lokalen Repository geladen; es findet kein Netzwerkzugriff
 * statt ({@link LocalOnlyResolvingStrategy}).
 *
 * <p>Alle verwendeten APIs wurden an {@code de.kosit:validationtool:1.5.0} verifiziert:
 * {@code Configuration.load(URI, URI)}, {@code ConfigurationLoader.setResolvingMode/
 * setResolvingStrategy/build(Processor)}, {@code DefaultCheck(Processor, Configuration...)},
 * {@code InputFactory.read(Path)}, {@code Result}.
 */
@Component
public class KositValidator {

    private static final Logger log = LoggerFactory.getLogger(KositValidator.class);
    private static final String NS_REP = "http://www.xoev.de/de/validator/varl/1";
    private static final String NS_SCEN = "http://www.xoev.de/de/validator/framework/1/scenarios";
    private static final String NS_XHTML = "http://www.w3.org/1999/xhtml";

    private final Path scenarios;
    private final Path repository;
    private final Object lock = new Object();
    private Processor processor;
    private Check check;
    private String configurationName;

    public KositValidator(AppProperties properties) {
        this.scenarios = properties.validation().kositScenarios().toAbsolutePath().normalize();
        this.repository = properties.validation().kositRepository().toAbsolutePath().normalize();
    }

    /** Name und Datum der geladenen Konfiguration (für Protokoll und Systemstatus). */
    public String configurationName() {
        synchronized (lock) {
            return configurationName;
        }
    }

    public boolean isAvailable() {
        return Files.isRegularFile(scenarios) && Files.isDirectory(repository);
    }

    private Check check() {
        synchronized (lock) {
            if (check == null) {
                if (!isAvailable()) {
                    throw new IllegalStateException("KoSIT-Konfiguration nicht gefunden: " + scenarios + " / " + repository);
                }
                processor = new Processor(false);
                Configuration cfg = Configuration.load(scenarios.toUri(), repository.toUri())
                        .setResolvingMode(ResolvingMode.CUSTOM)
                        .setResolvingStrategy(new LocalOnlyResolvingStrategy())
                        .build(processor);
                configurationName = cfg.getName() + " (" + cfg.getDate() + ")";
                check = new DefaultCheck(processor, cfg);
                log.info("KoSIT-Validator geladen: {} mit {} Szenarien", configurationName, cfg.getScenarios().size());
            }
            return check;
        }
    }

    public ValidationReport validate(String target, boolean mandatory, Path xmlFile) {
        Instant now = Instant.now();
        Check c;
        try {
            c = check();
        } catch (RuntimeException e) {
            log.error("KoSIT-Validator nicht verfügbar: {}", e.getMessage());
            return new ValidationReport(ValidatorKind.KOSIT, target, mandatory, ValidationOutcome.ERROR, null, List.of(),
                    null, null, now, "KoSIT-Validator nicht verfügbar: " + e.getMessage());
        }
        try {
            // Inhalt als Bytes übergeben: bei Pfad-Eingaben bleibt in KoSIT 1.5.0 ein Dateihandle offen,
            // was das anschließende Verschieben unter Windows verhindert.
            Input input = InputFactory.read(Files.readAllBytes(xmlFile), xmlFile.getFileName().toString());
            Result result;
            synchronized (lock) {
                result = c.checkInput(input);
            }
            List<ValidationFinding> findings = new ArrayList<>();
            for (XmlError err : result.getSchemaViolations()) {
                Severity sev = err.getSeverity() == XmlError.Severity.SEVERITY_WARNING ? Severity.WARNING : Severity.ERROR;
                String loc = err.getRowNumber() == null ? null : "Zeile " + err.getRowNumber() + ", Spalte " + err.getColumnNumber();
                findings.add(new ValidationFinding(sev, "XSD", err.getMessage(), loc, null, err.getMessage()));
            }
            for (FailedAssert fa : result.getFailedAsserts()) {
                String text = textOf(fa);
                findings.add(new ValidationFinding(severity(fa.getFlag()), fa.getId(), text, fa.getLocation(), null,
                        text + (fa.getTest() == null ? "" : " [test: " + fa.getTest() + "]")));
            }
            for (String pe : result.getProcessingErrors()) {
                findings.add(new ValidationFinding(Severity.ERROR, "PROCESSING", pe, null, null, pe));
            }
            Document report = result.getReportDocument();
            byte[] xml = serialize(report);
            byte[] html = extractHtml(report);
            String ruleset = scenarioName(report);
            ValidationOutcome outcome;
            String message = null;
            if (!result.isProcessingSuccessful()) {
                outcome = ValidationOutcome.ERROR;
                message = "Verarbeitung im KoSIT-Validator fehlgeschlagen: " + String.join("; ", result.getProcessingErrors());
            } else if (ruleset == null) {
                outcome = ValidationOutcome.NOT_APPLICABLE;
                message = "Kein KoSIT-Szenario passt zu diesem Dokument (Fallback)";
            } else {
                outcome = result.isAcceptable() ? ValidationOutcome.VALID : ValidationOutcome.INVALID;
            }
            return new ValidationReport(ValidatorKind.KOSIT, target, mandatory, outcome, ruleset, findings, xml, html, now, message);
        } catch (Exception e) {
            log.error("KoSIT-Validierung fehlgeschlagen für {}: {}", xmlFile.getFileName(), e.toString());
            return new ValidationReport(ValidatorKind.KOSIT, target, mandatory, ValidationOutcome.ERROR, null, List.of(),
                    null, null, now, "KoSIT-Validierung fehlgeschlagen: " + e.getMessage());
        }
    }

    /** SVRL-Text ist gemischter Inhalt (Strings und Elemente); alles als Text zusammenführen. */
    static String textOf(FailedAssert fa) {
        if (fa.getText() == null || fa.getText().getContent() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object o : fa.getText().getContent()) {
            if (o instanceof String s) {
                sb.append(s);
            } else if (o instanceof Node n) {
                sb.append(n.getTextContent());
            } else if (o != null) {
                sb.append(o);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    static Severity severity(String flag) {
        if (flag == null) {
            return Severity.ERROR;
        }
        return switch (flag.toLowerCase()) {
            case "warning" -> Severity.WARNING;
            case "information", "info" -> Severity.INFORMATION;
            default -> Severity.ERROR;
        };
    }

    /**
     * Der Report-DOM des KoSIT-Validators ist nicht zwingend namespace-bewusst; deshalb werden
     * Elemente über den lokalen Namen (mit oder ohne Präfix) gesucht.
     */
    private static String scenarioName(Document report) {
        Element matched = findFirst(report.getDocumentElement(), "scenarioMatched");
        if (matched == null) {
            return null;
        }
        Element name = findFirst(matched, "name");
        String text = name == null ? null : name.getTextContent();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static byte[] extractHtml(Document report) throws Exception {
        Element html = findFirst(report.getDocumentElement(), "html");
        return html == null ? null : serializeNode(html);
    }

    static Element findFirst(Element root, String localName) {
        if (root == null) {
            return null;
        }
        if (matches(root, localName)) {
            return root;
        }
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element found = findFirst((Element) n, localName);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static boolean matches(Element e, String localName) {
        String ln = e.getLocalName();
        if (ln != null) {
            return ln.equals(localName);
        }
        String nn = e.getNodeName();
        return nn.equals(localName) || nn.endsWith(":" + localName);
    }

    private static byte[] serialize(Document doc) throws Exception {
        return serializeNode(doc);
    }

    private static byte[] serializeNode(Node node) throws Exception {
        Transformer t = SecureXml.transformerFactory().newTransformer();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        t.transform(new DOMSource(node), new StreamResult(bos));
        return bos.toByteArray();
    }

    static byte[] readAll(Path p) throws IOException {
        return Files.readAllBytes(p);
    }
}
