package de.hofmannit.erechnung.inboundvalidation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.inboundvalidation.DocumentInspector.Inspection;
import de.hofmannit.erechnung.security.Sha256;
import de.hofmannit.erechnung.validation.KositValidator;
import de.hofmannit.erechnung.validation.MustangValidator;
import de.hofmannit.erechnung.validation.ValidationOutcome;
import de.hofmannit.erechnung.validation.ValidationReport;
import de.hofmannit.erechnung.validation.ValidatorKind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "E-Rechnung prüfen" (Vorgabe Abschnitte 1B und 13, ADR 0005): gezielte Validierung einer
 * empfangenen E-Rechnung, vollständig getrennt von der Ausgangs-Pipeline.
 *
 * <ol>
 *   <li>SHA-256 berechnen</li>
 *   <li>Dateityp und Dokumenttyp anhand des Inhalts bestimmen; bei PDF eingebettete XML lesen</li>
 *   <li>Profil/Standard aus BT-24/BT-23 bestimmen, soweit eindeutig</li>
 *   <li>Validatoren nach Anwendbarkeitsmatrix ausführen (ADR 0002)</li>
 *   <li>Ergebnis, Reports und Zusammenfassung erzeugen; optional ablegen</li>
 * </ol>
 *
 * Das Original wird nie verändert (Hash vor und nach der Prüfung identisch), nie konvertiert,
 * nie in die Ausgangs-Pipeline übergeben. Ohne {@code store-reports} bleibt nichts zurück.
 */
@Service
public class InboundValidationService {

    private static final Logger log = LoggerFactory.getLogger(InboundValidationService.class);

    private final DocumentInspector inspector;
    private final KositValidator kosit;
    private final MustangValidator mustang;
    private final InboundValidationRepository repository;
    private final AppProperties properties;
    private final Clock clock;

    public InboundValidationService(DocumentInspector inspector, KositValidator kosit, MustangValidator mustang,
                                    InboundValidationRepository repository, AppProperties properties, Clock clock) {
        this.inspector = inspector;
        this.kosit = kosit;
        this.mustang = mustang;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Prüft eine hochgeladene Datei.
     *
     * @param originalFilename Dateiname aus dem Upload (nur zur Anzeige; Typerkennung erfolgt inhaltlich)
     * @param content          unveränderter Inhalt
     * @param requestedBy      Benutzer für das Prüfprotokoll (optional)
     */
    public InboundValidationResult validate(String originalFilename, byte[] content, String requestedBy) throws IOException {
        Instant now = clock.instant();
        String sha = Sha256.ofBytes(content);
        String safeName = originalFilename == null || originalFilename.isBlank() ? "upload" : Path.of(originalFilename).getFileName().toString();
        Inspection inspection = inspector.inspect(content);

        String profileName = ProfileCatalog.describe(inspection.customizationId()).orElse(
                inspection.customizationId() == null ? "nicht eindeutig bestimmbar (keine Spezifikationskennung BT-24)"
                        : "nicht eindeutig bestimmbar (unbekannte Kennung)");
        boolean profileKnown = ProfileCatalog.describe(inspection.customizationId()).isPresent();
        String xrVersion = ProfileCatalog.xrechnungVersion(inspection.customizationId()).orElse(null);

        Path tmp = Files.createTempDirectory(Files.createDirectories(properties.directories().data().resolve("tmp")), "inbound-");
        try {
            List<ValidationReport> reports = new ArrayList<>();
            String message = inspection.message();
            ValidationOutcome overall;
            switch (inspection.type()) {
                case CII_XML, UBL_XML -> {
                    Path xml = Files.write(tmp.resolve("document.xml"), content);
                    boolean xrechnung = ProfileCatalog.isXRechnung(inspection.customizationId());
                    reports.add(kosit.validate(inspection.syntax(), xrechnung, xml));
                    reports.add(mustang.validate(inspection.syntax(), !xrechnung, xml));
                    overall = overall(reports);
                }
                case ZUGFERD_PDF -> {
                    Path pdf = Files.write(tmp.resolve("document.pdf"), content);
                    Path xml = Files.write(tmp.resolve("embedded.xml"), inspection.xml());
                    boolean xrechnung = ProfileCatalog.isXRechnung(inspection.customizationId());
                    reports.add(mustang.validate("ZUGFERD_PDF", true, pdf));
                    reports.add(kosit.validate("ZUGFERD_PDF/" + inspection.syntax(), xrechnung, xml));
                    overall = overall(reports);
                }
                default -> overall = ValidationOutcome.ERROR;
            }
            if (Sha256.ofBytes(content).equals(sha) == false) {
                throw new IllegalStateException("Original wurde während der Prüfung verändert");
            }
            InboundValidationResult result = new InboundValidationResult(safeName, content.length, sha, inspection.type(),
                    inspection.syntax(), inspection.customizationId(), inspection.processId(), profileName, profileKnown, xrVersion,
                    inspection.hasXml(), inspection.embeddedFilename(), inspection.attachmentNames(), overall, reports, now, message, null);
            log.info("E-Rechnung geprüft: {} ({}) → {} [{}]", safeName, inspection.type(), overall, sha.substring(0, 8));
            if (properties.inboundValidation().storeReports()) {
                Path dir = store(result, content);
                result = result.withStoredDirectory(dir);
                repository.record(result, dir.toString(), requestedBy);
            }
            return result;
        } finally {
            deleteRecursively(tmp);
        }
    }

    /** Gesamtergebnis: verpflichtende Validatoren müssen VALID sein; ERROR eines Pflichtvalidators = nicht prüfbar. */
    static ValidationOutcome overall(List<ValidationReport> reports) {
        boolean anyMandatory = reports.stream().anyMatch(ValidationReport::mandatory);
        if (!anyMandatory) {
            return ValidationOutcome.ERROR;
        }
        for (ValidationReport r : reports) {
            if (r.mandatory() && r.outcome() == ValidationOutcome.ERROR) {
                return ValidationOutcome.ERROR;
            }
        }
        for (ValidationReport r : reports) {
            if (r.mandatory() && r.outcome() != ValidationOutcome.VALID) {
                return ValidationOutcome.INVALID;
            }
        }
        return ValidationOutcome.VALID;
    }

    /**
     * Ablage unter {@code inbound-validation/{jahr}/{monat}/{sha256-prefix}/check-NNN/}:
     * Original und Reports getrennt benannt, nie überschreiben, schreibgeschützt.
     */
    private Path store(InboundValidationResult r, byte[] content) throws IOException {
        LocalDate date = LocalDate.ofInstant(r.validatedAt(), clock.getZone());
        Path base = properties.directories().inboundValidation().toAbsolutePath().normalize()
                .resolve(String.format(Locale.ROOT, "%04d", date.getYear()))
                .resolve(String.format(Locale.ROOT, "%02d", date.getMonthValue()))
                .resolve(r.sha256().substring(0, 8));
        Files.createDirectories(base);
        Path dir = null;
        for (int n = 1; n < 1000; n++) {
            Path candidate = base.resolve(String.format(Locale.ROOT, "check-%03d", n));
            if (!Files.exists(candidate)) {
                dir = Files.createDirectory(candidate);
                break;
            }
        }
        if (dir == null) {
            throw new IOException("Kein freies Prüfverzeichnis unter " + base);
        }
        String ext = switch (r.documentType()) {
            case ZUGFERD_PDF, PDF_WITHOUT_XML -> ".pdf";
            case CII_XML, UBL_XML, UNKNOWN_XML, MALFORMED_XML -> ".xml";
            case UNSUPPORTED -> ".bin";
        };
        writeReadOnly(dir.resolve("original" + ext), content);
        writeReadOnly(dir.resolve("summary.txt"), InboundReportWriter.summaryText(r));
        writeReadOnly(dir.resolve("report.html"), InboundReportWriter.html(r));
        for (ValidationReport rep : r.reports()) {
            String name = "report-" + rep.validator().name().toLowerCase(Locale.ROOT);
            if (rep.reportXml() != null) {
                writeReadOnly(dir.resolve(name + ".xml"), rep.reportXml());
            }
            if (rep.reportHtml() != null) {
                writeReadOnly(dir.resolve(name + ".html"), rep.reportHtml());
            }
        }
        writeReadOnly(dir.resolve("original.sha256"), (r.sha256() + "  original" + ext + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return dir;
    }

    private static void writeReadOnly(Path target, byte[] content) throws IOException {
        Files.write(target, content, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE);
        try {
            target.toFile().setReadOnly();
        } catch (SecurityException e) {
            log.warn("Schreibschutz konnte nicht gesetzt werden: {}", target);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Temporäre Datei konnte nicht gelöscht werden: {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("Temporäres Verzeichnis konnte nicht gelöscht werden: {}", dir);
        }
    }

    static ValidatorKind kind(ValidationReport r) {
        return r.validator();
    }
}
