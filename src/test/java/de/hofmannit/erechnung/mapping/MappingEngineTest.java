package de.hofmannit.erechnung.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;

import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.configuration.profile.ProfileLoader;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.PdfTextExtractor;
import de.hofmannit.erechnung.mapping.FieldEvidence.FieldStatus;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.DocumentType;
import de.hofmannit.erechnung.model.MappingRuleType;
import de.hofmannit.erechnung.testsupport.TestInvoicePdf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Extraktion + Klassifizierung + Mapping gegen die selbst erzeugte Test-PDF und das
 * Standardprofil: jede Regelart wird mit Fundstelle nachgewiesen.
 */
class MappingEngineTest {

    static ProfileDefinition profile;
    static Map<String, String> fixedValues = Map.of(
            "seller-name", "Hofmann IT", "seller-street", "Musterstraße 1", "seller-post-code", "12345",
            "seller-city", "Musterstadt", "seller-country-code", "DE", "seller-vat-id", "DE123456789",
            "payment-means-type-code", "58", "payment-account-iban", "DE02 1203 0000 0000 2020 51", "invoice-currency", "EUR");

    @BeforeAll
    static void loadProfile() throws Exception {
        LoadedProfile loaded = new ProfileLoader().load(Path.of("profiles", "standard.yaml"));
        profile = loaded.definition();
    }

    @Test
    void mapsStandardInvoiceWithAllRuleTypes(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("rechnung.pdf");
        TestInvoicePdf.standardInvoice().writeTo(pdf);

        ExtractedDocument doc = new PdfTextExtractor().extract(pdf);
        assertThat(doc.pageCount()).isEqualTo(2);
        assertThat(doc.hasTextLayer()).isTrue();

        ClassificationResult classification = new Classifier().classify(doc, profile);
        assertThat(classification.isInvoice()).isTrue();
        assertThat(classification.documentType()).isEqualTo(DocumentType.INVOICE);
        assertThat(classification.businessCase().id()).isEqualTo("DOMESTIC_STANDARD");
        assertThat(classification.isMultiPage()).isTrue();

        InvoiceData data = new MappingEngine().map(doc, profile, classification, fixedValues);

        // anchor
        FieldEvidence bt1 = data.fields().get(BusinessTerm.BT_1);
        assertThat(bt1.value()).isEqualTo("RE-2026-4711");
        assertThat(bt1.ruleType()).isEqualTo(MappingRuleType.ANCHOR);
        assertThat(bt1.page()).isEqualTo(1);
        assertThat(bt1.boundingBox()).isNotNull();
        assertThat(bt1.boundingBox().x()).isBetween(440.0, 460.0);
        assertThat(bt1.status()).isEqualTo(FieldStatus.OK);

        // regex + Transformation date
        assertThat(data.value(BusinessTerm.BT_2)).contains("2026-09-24");
        assertThat(data.fields().get(BusinessTerm.BT_2).ruleType()).isEqualTo(MappingRuleType.REGEX);
        assertThat(data.value(BusinessTerm.BT_9)).contains("2026-10-08");
        assertThat(data.value(BusinessTerm.BT_72)).contains("2026-09-24");
        assertThat(data.value(BusinessTerm.BT_10)).contains("04011000-12345-67");
        assertThat(data.value(BusinessTerm.BT_13)).contains("B-2026-0815");
        assertThat(data.value(BusinessTerm.BT_49)).contains("buchhaltung@example.invalid");
        assertThat(data.value(BusinessTerm.BT_20)).contains("Zahlbar innerhalb von 14 Tagen ohne Abzug, zahlbar bis 08.10.2026");

        // region
        FieldEvidence bt44 = data.fields().get(BusinessTerm.BT_44);
        assertThat(bt44.value()).isEqualTo("Beispiel GmbH");
        assertThat(bt44.ruleType()).isEqualTo(MappingRuleType.REGION);
        assertThat(data.value(BusinessTerm.BT_50)).contains("Kundenweg 2");
        assertThat(data.value(BusinessTerm.BT_53)).contains("54321");
        assertThat(data.value(BusinessTerm.BT_52)).contains("Kundenstadt");

        // Summen (regex + decimal:de)
        assertThat(data.value(BusinessTerm.BT_106)).contains("1560.00");
        assertThat(data.value(BusinessTerm.BT_109)).contains("1560.00");
        assertThat(data.value(BusinessTerm.BT_110)).contains("296.40");
        assertThat(data.value(BusinessTerm.BT_112)).contains("1856.40");
        assertThat(data.value(BusinessTerm.BT_115)).contains("1856.40");
        assertThat(data.value(BusinessTerm.BT_116)).contains("1560.00");
        assertThat(data.value(BusinessTerm.BT_117)).contains("296.40");
        assertThat(data.value(BusinessTerm.BT_119)).contains("19");

        // fixed
        FieldEvidence bt27 = data.fields().get(BusinessTerm.BT_27);
        assertThat(bt27.value()).isEqualTo("Hofmann IT");
        assertThat(bt27.ruleType()).isEqualTo(MappingRuleType.FIXED);
        assertThat(bt27.page()).isNull();
        assertThat(data.value(BusinessTerm.BT_84)).contains("DE02120300000000202051");
        assertThat(data.fields().get(BusinessTerm.BT_86).status()).isEqualTo(FieldStatus.NOT_FOUND);

        // table: 3 Positionen über zwei Seiten, mehrzeilige Bezeichnung
        assertThat(data.lines()).hasSize(3);
        LineItemData first = data.lines().get(0);
        assertThat(first.value(BusinessTerm.BT_126)).contains("1");
        assertThat(first.value(BusinessTerm.BT_153)).contains("IT-Beratung\nSeptember 2026");
        assertThat(first.value(BusinessTerm.BT_129)).contains("8.00");
        assertThat(first.value(BusinessTerm.BT_130)).contains("HUR");
        assertThat(first.value(BusinessTerm.BT_146)).contains("120.00");
        assertThat(first.value(BusinessTerm.BT_152)).contains("19");
        assertThat(first.value(BusinessTerm.BT_131)).contains("960.00");
        assertThat(first.fields().get(BusinessTerm.BT_131).page()).isEqualTo(1);
        LineItemData third = data.lines().get(2);
        assertThat(third.value(BusinessTerm.BT_153)).contains("Support-Pauschale");
        assertThat(third.value(BusinessTerm.BT_131)).contains("100.00");
        assertThat(third.fields().get(BusinessTerm.BT_131).page()).isEqualTo(2);
    }

    @Test
    void classifiesQuoteAsNonInvoice(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("angebot.pdf");
        TestInvoicePdf.standardInvoice().title("Angebot").singlePage().writeTo(pdf);
        ExtractedDocument doc = new PdfTextExtractor().extract(pdf);
        ClassificationResult c = new Classifier().classify(doc, profile);
        assertThat(c.isInvoice()).isFalse();
        assertThat(c.reasons()).anySatisfy(r -> assertThat(r).contains("Nicht-Rechnungs-Indikator"));
    }
}
