package de.hofmannit.erechnung.inboundvalidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.PdfTextExtractor;
import de.hofmannit.erechnung.generation.EInvoiceGenerator;
import de.hofmannit.erechnung.generation.GeneratedArtifact;
import de.hofmannit.erechnung.mapping.ClassificationResult;
import de.hofmannit.erechnung.mapping.Classifier;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.MappingEngine;
import de.hofmannit.erechnung.model.OutputFormat;
import de.hofmannit.erechnung.security.Sha256;
import de.hofmannit.erechnung.testsupport.TestInvoicePdf;
import de.hofmannit.erechnung.validation.ValidationOutcome;
import de.hofmannit.erechnung.validation.ValidatorKind;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase-3-Pflichttests (Vorgabe): gültige/ungültige CII und UBL, gültiges ZUGFeRD, ZUGFeRD ohne
 * XML, beschädigte XML, nicht unterstütztes Format. Testdokumente werden aus der eigenen
 * Ausgangs-Pipeline (Test-PDF → Mustang) erzeugt; kein externes Testdokument.
 */
@SpringBootTest
class InboundValidationServiceTest {

    static final Path ROOT = createRoot();

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "inbound-").toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        for (String d : List.of("inbox", "processing", "output", "failed", "manual-review", "rejected", "archive", "data", "inbound-validation")) {
            r.add("app.directories." + d, () -> ROOT.resolve(d).toString());
        }
        r.add("app.logging.directory", () -> ROOT.resolve("logs").toString());
        r.add("app.watcher.enabled", () -> "false");
        r.add("app.inbound-validation.store-reports", () -> "true");
    }

    @Autowired InboundValidationService service;
    @Autowired PdfTextExtractor extractor;
    @Autowired Classifier classifier;
    @Autowired MappingEngine mappingEngine;
    @Autowired EInvoiceGenerator generator;
    @Autowired ProfileRegistry registry;
    @Autowired JdbcTemplate jdbc;

    static byte[] sourcePdf;
    static byte[] cii;
    static byte[] ubl;
    static byte[] zugferd;

    @BeforeAll
    static void prepareRoot() throws Exception {
        Files.createDirectories(ROOT);
    }

    private void generateFixtures() throws Exception {
        if (cii != null) {
            return;
        }
        Path work = Files.createDirectories(ROOT.resolve("fixtures"));
        Path pdf = work.resolve("source.pdf");
        TestInvoicePdf.standardInvoice().writeTo(pdf);
        sourcePdf = Files.readAllBytes(pdf);
        LoadedProfile profile = registry.profile("standard").orElseThrow();
        ExtractedDocument doc = extractor.extract(pdf);
        ClassificationResult c = classifier.classify(doc, profile.definition());
        InvoiceData data = mappingEngine.map(doc, profile.definition(), c, registry.tenant("hofmann-it").orElseThrow().fixedValues());
        List<GeneratedArtifact> artifacts = generator.generate(data, profile.definition(), pdf, work, "fixture");
        for (GeneratedArtifact a : artifacts) {
            byte[] bytes = Files.readAllBytes(a.path());
            if (a.format() == OutputFormat.XRECHNUNG_CII) {
                cii = bytes;
            } else if (a.format() == OutputFormat.XRECHNUNG_UBL) {
                ubl = bytes;
            } else {
                zugferd = bytes;
            }
        }
        assertThat(cii).isNotNull();
        assertThat(ubl).isNotNull();
        assertThat(zugferd).isNotNull();
    }

    @Autowired InboundValidationRepository repository;

    @Test
    void validCiiXRechnung() throws Exception {
        generateFixtures();
        int before = repository.count(Sha256.ofBytes(cii));
        InboundValidationResult r = service.validate("eingang-cii.xml", cii, "tester");
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.CII_XML);
        assertThat(r.syntax()).isEqualTo("CII");
        assertThat(r.profileKnown()).isTrue();
        assertThat(r.profileName()).contains("XRechnung");
        assertThat(r.xrechnungVersion()).isEqualTo("3.0");
        assertThat(r.report(ValidatorKind.KOSIT).mandatory()).isTrue();
        assertThat(r.report(ValidatorKind.KOSIT).outcome()).isEqualTo(ValidationOutcome.VALID);
        assertThat(r.report(ValidatorKind.KOSIT).ruleset()).contains("XRechnung");
        assertThat(r.report(ValidatorKind.KOSIT).reportHtml()).isNotNull();
        assertThat(r.report(ValidatorKind.MUSTANG).mandatory()).isFalse();
        assertThat(r.overall()).as(r.errors().toString()).isEqualTo(ValidationOutcome.VALID);
        assertThat(r.errors()).isEmpty();
        assertThat(r.sha256()).isEqualTo(Sha256.ofBytes(cii));

        // Ablage: Original und Reports unterscheidbar, Original unverändert
        assertThat(r.storedDirectory()).isNotNull();
        try (Stream<Path> s = Files.list(r.storedDirectory())) {
            assertThat(s.map(p -> p.getFileName().toString()).toList())
                    .contains("original.xml", "summary.txt", "report.html", "report-kosit.xml", "report-kosit.html", "report-mustang.xml", "original.sha256");
        }
        assertThat(Sha256.ofFile(r.storedDirectory().resolve("original.xml"))).isEqualTo(r.sha256());
        assertThat(r.storedDirectory().toString()).contains("inbound-validation").contains(r.sha256().substring(0, 8))
                .endsWith(String.format("check-%03d", before + 1));
        assertThat(Files.readString(r.storedDirectory().resolve("summary.txt"))).contains("GÜLTIG").contains("KOSIT (verpflichtend): VALID");
        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM inbound_validation WHERE sha256 = ? AND overall_outcome = 'VALID'", Integer.class, r.sha256());
        assertThat(rows).isEqualTo(before + 1);

        // erneute Prüfung: neues check-Verzeichnis, nichts überschrieben
        InboundValidationResult again = service.validate("eingang-cii.xml", cii, "tester");
        assertThat(again.storedDirectory().getFileName().toString()).isEqualTo(String.format("check-%03d", before + 2));
    }

    @Test
    void invalidCiiXRechnungReportsRuleViolation() throws Exception {
        generateFixtures();
        String xml = new String(cii, StandardCharsets.UTF_8);
        // Käuferreferenz (BT-10, Leitweg-ID) entfernen: XRechnung-Regel BR-DE-15 verlangt sie.
        String broken = xml.replaceAll("<ram:BuyerReference>[^<]*</ram:BuyerReference>", "");
        assertThat(broken).isNotEqualTo(xml);
        InboundValidationResult r = service.validate("eingang-cii-fehler.xml", broken.getBytes(StandardCharsets.UTF_8), null);
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.CII_XML);
        assertThat(r.overall()).isEqualTo(ValidationOutcome.INVALID);
        assertThat(r.report(ValidatorKind.KOSIT).outcome()).isEqualTo(ValidationOutcome.INVALID);
        assertThat(r.errors()).anySatisfy(f -> {
            assertThat(f.ruleId()).isEqualTo("BR-DE-15");
            assertThat(f.location()).isNotBlank();
            assertThat(f.description()).contains("BT-10");
        });
        assertThat(Files.readString(r.storedDirectory().resolve("summary.txt"))).contains("NICHT GÜLTIG").contains("BR-DE-15");
    }

    @Test
    void validUblXRechnung() throws Exception {
        generateFixtures();
        InboundValidationResult r = service.validate("eingang.ubl.xml", ubl, null);
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.UBL_XML);
        assertThat(r.syntax()).isEqualTo("UBL");
        assertThat(r.profileName()).contains("XRechnung");
        assertThat(r.report(ValidatorKind.KOSIT).outcome()).isEqualTo(ValidationOutcome.VALID);
        assertThat(r.report(ValidatorKind.KOSIT).ruleset()).contains("UBL");
        assertThat(r.overall()).as(r.errors().toString()).isEqualTo(ValidationOutcome.VALID);
    }

    @Test
    void invalidUblXRechnungReportsSchemaOrRuleError() throws Exception {
        generateFixtures();
        String xml = new String(ubl, StandardCharsets.UTF_8);
        // Rechnungsnummer (BT-1) entfernen: EN-16931-Regel BR-01 verlangt sie.
        String broken = xml.replaceFirst("<cbc:ID>[^<]*</cbc:ID>", "");
        assertThat(broken).isNotEqualTo(xml);
        InboundValidationResult r = service.validate("eingang-ubl-fehler.xml", broken.getBytes(StandardCharsets.UTF_8), null);
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.UBL_XML);
        assertThat(r.overall()).isEqualTo(ValidationOutcome.INVALID);
        assertThat(r.errors()).isNotEmpty();
        // KoSIT bricht nach dem XSD-Fehler ab ("XSD"), Mustang meldet zusätzlich BR-02 (Invoice number BT-1).
        assertThat(r.errors()).anySatisfy(f -> assertThat(f.ruleId()).isIn("XSD", "BR-02"));
        assertThat(r.report(ValidatorKind.KOSIT).outcome()).isEqualTo(ValidationOutcome.INVALID);
    }

    @Test
    void validZugferdPdf() throws Exception {
        generateFixtures();
        InboundValidationResult r = service.validate("eingang-zugferd.pdf", zugferd, null);
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.ZUGFERD_PDF);
        assertThat(r.embeddedXmlPresent()).isTrue();
        assertThat(r.embeddedXmlFilename()).isEqualTo("factur-x.xml");
        assertThat(r.syntax()).isEqualTo("CII");
        assertThat(r.profileName()).contains("EN 16931");
        assertThat(r.report(ValidatorKind.MUSTANG).mandatory()).isTrue();
        assertThat(r.report(ValidatorKind.MUSTANG).outcome()).isEqualTo(ValidationOutcome.VALID);
        assertThat(r.report(ValidatorKind.KOSIT).mandatory()).isFalse();
        assertThat(r.report(ValidatorKind.KOSIT).outcome()).isEqualTo(ValidationOutcome.VALID);
        assertThat(r.overall()).as(r.errors().toString()).isEqualTo(ValidationOutcome.VALID);
        assertThat(Sha256.ofFile(r.storedDirectory().resolve("original.pdf"))).isEqualTo(Sha256.ofBytes(zugferd));
    }

    @Test
    void zugferdWithoutEmbeddedXmlIsNotValidatable() throws Exception {
        generateFixtures();
        InboundValidationResult r = service.validate("nur-pdf.pdf", sourcePdf, null);
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.PDF_WITHOUT_XML);
        assertThat(r.embeddedXmlPresent()).isFalse();
        assertThat(r.overall()).isEqualTo(ValidationOutcome.ERROR);
        assertThat(r.reports()).isEmpty();
        assertThat(r.message()).contains("keine eingebetteten Dateien");
        assertThat(r.profileName()).contains("nicht eindeutig bestimmbar");
    }

    @Test
    void malformedXmlIsRejectedWithoutValidation() throws Exception {
        InboundValidationResult r = service.validate("kaputt.xml", "<Invoice><ID>1</Invoice>".getBytes(StandardCharsets.UTF_8), null);
        assertThat(r.documentType()).isEqualTo(InboundDocumentType.MALFORMED_XML);
        assertThat(r.overall()).isEqualTo(ValidationOutcome.ERROR);
        assertThat(r.message()).contains("nicht wohlgeformt");

        InboundValidationResult xxe = service.validate("xxe.xml",
                "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><Invoice>&e;</Invoice>".getBytes(StandardCharsets.UTF_8), null);
        assertThat(xxe.documentType()).isEqualTo(InboundDocumentType.MALFORMED_XML);
    }

    @Test
    void unsupportedFormatAndUnknownXml() throws Exception {
        InboundValidationResult txt = service.validate("notiz.txt", "Das ist keine Rechnung".getBytes(StandardCharsets.UTF_8), null);
        assertThat(txt.documentType()).isEqualTo(InboundDocumentType.UNSUPPORTED);
        assertThat(txt.overall()).isEqualTo(ValidationOutcome.ERROR);

        InboundValidationResult other = service.validate("irgendwas.xml", "<?xml version=\"1.0\"?><Bestellung><Nr>1</Nr></Bestellung>".getBytes(StandardCharsets.UTF_8), null);
        assertThat(other.documentType()).isEqualTo(InboundDocumentType.UNKNOWN_XML);
        assertThat(other.overall()).isEqualTo(ValidationOutcome.ERROR);
        assertThat(other.message()).contains("Bestellung");

        // Dateiendung ist irrelevant: XML-Inhalt mit .pdf-Endung wird als XML erkannt
        generateFixtures();
        InboundValidationResult mislabeled = service.validate("rechnung.pdf", cii, null);
        assertThat(mislabeled.documentType()).isEqualTo(InboundDocumentType.CII_XML);
    }

    @Test
    void nothingLeaksIntoOutboundPipeline() throws Exception {
        generateFixtures();
        service.validate("eingang-cii.xml", cii, null);
        assertThat(Files.exists(ROOT.resolve("inbox"))).isFalse();
        assertThat(Files.exists(ROOT.resolve("processing"))).isFalse();
        assertThat(Files.exists(ROOT.resolve("output"))).isFalse();
        Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM processing_run", Integer.class);
        assertThat(runs).isZero();
        try (Stream<Path> s = Files.list(ROOT.resolve("data").resolve("tmp"))) {
            assertThat(s.toList()).as("temporäre Prüfverzeichnisse werden gelöscht").isEmpty();
        }
    }
}
