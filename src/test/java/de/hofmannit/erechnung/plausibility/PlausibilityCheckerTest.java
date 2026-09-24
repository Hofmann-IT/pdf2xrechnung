package de.hofmannit.erechnung.plausibility;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.PlausibilityCheck;
import de.hofmannit.erechnung.mapping.FieldEvidence;
import de.hofmannit.erechnung.mapping.FieldEvidence.FieldStatus;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.LineItemData;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.DocumentType;
import de.hofmannit.erechnung.model.MappingRuleType;

import org.junit.jupiter.api.Test;

class PlausibilityCheckerTest {

    private static final ProfileDefinition.Plausibility ALL = new ProfileDefinition.Plausibility(new BigDecimal("0.01"), List.of(PlausibilityCheck.values()));

    @Test
    void consistentInvoicePasses() {
        InvoiceData data = invoice("1560.00", "296.40", "1856.40", "1856.40");
        PlausibilityResult r = new PlausibilityChecker().check(data, ALL);
        assertThat(r.passed()).as(r.describe()).isTrue();
        assertThat(r.taxLines()).singleElement().satisfies(t -> {
            assertThat(t.rate()).isEqualByComparingTo("19");
            assertThat(t.taxableAmount()).isEqualByComparingTo("1560.00");
            assertThat(t.taxAmount()).isEqualByComparingTo("296.40");
        });
    }

    @Test
    void grossMismatchIsReportedWithinTolerance() {
        InvoiceData data = invoice("1560.00", "296.40", "1900.00", "1900.00");
        PlausibilityResult r = new PlausibilityChecker().check(data, ALL);
        assertThat(r.passed()).isFalse();
        assertThat(r.issues()).extracting(PlausibilityIssue::check).contains(PlausibilityCheck.GROSS_TOTAL);
        assertThat(r.describe()).contains("BT-109 + BT-110");

        InvoiceData rounding = invoice("1560.00", "296.40", "1856.41", "1856.41");
        assertThat(new PlausibilityChecker().check(rounding, ALL).passed()).isTrue();
    }

    @Test
    void missingRequiredFieldIsAnIssue() {
        Map<BusinessTerm, FieldEvidence> fields = new LinkedHashMap<>(invoice("1560.00", "296.40", "1856.40", "1856.40").fields());
        fields.put(BusinessTerm.BT_1, new FieldEvidence(BusinessTerm.BT_1, null, null, "r", MappingRuleType.ANCHOR, 1, null, null, "anchor",
                FieldStatus.ERROR, "Pflichtfeld nicht gefunden"));
        InvoiceData data = new InvoiceData(DocumentType.INVOICE, "DOMESTIC_STANDARD", "S", null, null, fields, lines(), 1);
        PlausibilityResult r = new PlausibilityChecker().check(data, ALL);
        assertThat(r.passed()).isFalse();
        assertThat(r.describe()).contains("BT-1");
    }

    private static InvoiceData invoice(String net, String tax, String gross, String payable) {
        Map<BusinessTerm, FieldEvidence> fields = new LinkedHashMap<>();
        fields.put(BusinessTerm.BT_106, ok(BusinessTerm.BT_106, net));
        fields.put(BusinessTerm.BT_109, ok(BusinessTerm.BT_109, net));
        fields.put(BusinessTerm.BT_110, ok(BusinessTerm.BT_110, tax));
        fields.put(BusinessTerm.BT_112, ok(BusinessTerm.BT_112, gross));
        fields.put(BusinessTerm.BT_115, ok(BusinessTerm.BT_115, payable));
        fields.put(BusinessTerm.BT_116, ok(BusinessTerm.BT_116, net));
        fields.put(BusinessTerm.BT_117, ok(BusinessTerm.BT_117, tax));
        fields.put(BusinessTerm.BT_119, ok(BusinessTerm.BT_119, "19"));
        return new InvoiceData(DocumentType.INVOICE, "DOMESTIC_STANDARD", "S", null, null, fields, lines(), 1);
    }

    private static List<LineItemData> lines() {
        return List.of(
                line(1, "8.00", "120.00", "960.00"),
                line(2, "2.00", "250.00", "500.00"),
                line(3, "1.00", "100.00", "100.00"));
    }

    private static LineItemData line(int n, String qty, String price, String total) {
        Map<BusinessTerm, FieldEvidence> f = new LinkedHashMap<>();
        f.put(BusinessTerm.BT_129, ok(BusinessTerm.BT_129, qty));
        f.put(BusinessTerm.BT_146, ok(BusinessTerm.BT_146, price));
        f.put(BusinessTerm.BT_131, ok(BusinessTerm.BT_131, total));
        f.put(BusinessTerm.BT_152, ok(BusinessTerm.BT_152, "19.00"));
        return new LineItemData(n, f);
    }

    private static FieldEvidence ok(BusinessTerm bt, String value) {
        return new FieldEvidence(bt, null, value, "test", MappingRuleType.REGEX, 1, null, value, "test", FieldStatus.OK, null);
    }
}
