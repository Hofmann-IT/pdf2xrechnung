package de.hofmannit.erechnung.plausibility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.PlausibilityCheck;
import de.hofmannit.erechnung.mapping.FieldEvidence;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.LineItemData;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.plausibility.PlausibilityResult.TaxLine;

import org.springframework.stereotype.Component;

/**
 * Rechnerische Plausibilitätsprüfung vor der Erzeugung (Vorgabe Abschnitt 9).
 *
 * <p>Alle Vergleiche mit absoluter Toleranz (Standard 0,01). Fehlende Pflichtfelder aus dem
 * Mapping werden ebenfalls als Abweichung gemeldet, damit ein REVIEW immer eine lesbare
 * Begründung trägt. Es werden keine fachlichen Werte verändert oder ergänzt; fehlende optionale
 * Summen (z. B. BT-107/BT-108) gelten als 0, was im Nachweis vermerkt wird.
 */
@Component
public class PlausibilityChecker {

    public PlausibilityResult check(InvoiceData data, ProfileDefinition.Plausibility config) {
        BigDecimal tolerance = config == null || config.tolerance() == null ? PlausibilityTolerance.DEFAULT : config.tolerance();
        List<PlausibilityCheck> checks = config == null || config.checks().isEmpty()
                ? List.of(PlausibilityCheck.values()) : config.checks();
        List<PlausibilityIssue> issues = new ArrayList<>();

        // Pflichtfelder / formale Fehler aus dem Mapping
        for (FieldEvidence e : data.allEvidence()) {
            if (e.status() == FieldEvidence.FieldStatus.ERROR) {
                String where = e.lineNumber() == null ? "" : " (Position " + e.lineNumber() + ")";
                issues.add(new PlausibilityIssue(null, e.businessTerm().id() + " " + e.businessTerm().germanLabel() + where
                        + ": " + e.message() + (e.sourceText() == null ? "" : " [Quelltext: '" + e.sourceText() + "']"), null, null));
            }
        }
        if (data.lines().isEmpty()) {
            issues.add(new PlausibilityIssue(null, "Keine Rechnungspositionen erkannt", null, null));
        }

        // Steueraufschlüsselung aus Positionen
        Map<BigDecimal, TaxLine> taxLines = new TreeMap<>();
        BigDecimal lineSum = BigDecimal.ZERO;
        boolean linesComplete = true;
        for (LineItemData line : data.lines()) {
            Optional<BigDecimal> net = line.value(BusinessTerm.BT_131).map(BigDecimal::new);
            if (net.isEmpty()) {
                linesComplete = false;
                continue;
            }
            lineSum = lineSum.add(net.get());
            if (checks.contains(PlausibilityCheck.LINE_AMOUNT)) {
                Optional<BigDecimal> price = line.value(BusinessTerm.BT_146).map(BigDecimal::new);
                Optional<BigDecimal> qty = line.value(BusinessTerm.BT_129).map(BigDecimal::new);
                BigDecimal basis = line.value(BusinessTerm.BT_149).map(BigDecimal::new).orElse(BigDecimal.ONE);
                if (price.isPresent() && qty.isPresent() && basis.signum() != 0) {
                    BigDecimal expected = price.get().multiply(qty.get()).divide(basis, 2, RoundingMode.HALF_UP);
                    compare(issues, PlausibilityCheck.LINE_AMOUNT, "Position " + line.lineNumber() + ": BT-146 × BT-129 ≠ BT-131",
                            expected, net, tolerance);
                }
            }
            BigDecimal rate = line.value(BusinessTerm.BT_152).map(BigDecimal::new)
                    .or(() -> data.decimal(BusinessTerm.BT_119))
                    .orElse(BigDecimal.ZERO);
            rate = rate.stripTrailingZeros();
            if (rate.scale() < 0) {
                rate = rate.setScale(0);
            }
            TaxLine existing = taxLines.get(rate);
            BigDecimal taxable = (existing == null ? BigDecimal.ZERO : existing.taxableAmount()).add(net.get());
            taxLines.put(rate, new TaxLine(data.vatCategoryCode(), rate, taxable, null));
        }
        // Steuerbetrag je Satz berechnen (kaufmännisch gerundet)
        for (Map.Entry<BigDecimal, TaxLine> en : taxLines.entrySet()) {
            TaxLine tl = en.getValue();
            BigDecimal tax = tl.taxableAmount().multiply(tl.rate()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            en.setValue(new TaxLine(tl.categoryCode(), tl.rate(), tl.taxableAmount().setScale(2, RoundingMode.HALF_UP), tax));
        }

        Optional<BigDecimal> bt106 = data.decimal(BusinessTerm.BT_106);
        Optional<BigDecimal> bt109 = data.decimal(BusinessTerm.BT_109);
        Optional<BigDecimal> bt110 = data.decimal(BusinessTerm.BT_110);
        Optional<BigDecimal> bt112 = data.decimal(BusinessTerm.BT_112);
        Optional<BigDecimal> bt115 = data.decimal(BusinessTerm.BT_115);
        BigDecimal bt107 = data.decimal(BusinessTerm.BT_107).orElse(BigDecimal.ZERO);
        BigDecimal bt108 = data.decimal(BusinessTerm.BT_108).orElse(BigDecimal.ZERO);
        BigDecimal bt113 = data.decimal(BusinessTerm.BT_113).orElse(BigDecimal.ZERO);
        BigDecimal bt114 = data.decimal(BusinessTerm.BT_114).orElse(BigDecimal.ZERO);

        if (checks.contains(PlausibilityCheck.LINE_SUM) && linesComplete && !data.lines().isEmpty()) {
            compare(issues, PlausibilityCheck.LINE_SUM, "Summe der Positionsnettobeträge ≠ BT-106", lineSum, bt106, tolerance);
        }
        if (checks.contains(PlausibilityCheck.NET_TOTAL) && bt106.isPresent()) {
            compare(issues, PlausibilityCheck.NET_TOTAL, "BT-106 − BT-107 + BT-108 ≠ BT-109",
                    bt106.get().subtract(bt107).add(bt108), bt109, tolerance);
        }
        BigDecimal taxableSum = taxLines.values().stream().map(TaxLine::taxableAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxSum = taxLines.values().stream().map(TaxLine::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (checks.contains(PlausibilityCheck.TAX_BASIS_PER_RATE) && linesComplete && !taxLines.isEmpty()) {
            // Dokumentnachlässe/-zuschläge werden hier nicht auf Steuersätze verteilt; bei Vorhandensein
            // wird die Prüfung nur gegen BT-106 ausgeführt (Nachlässe sind in Phase 2 nicht abgebildet).
            compare(issues, PlausibilityCheck.TAX_BASIS_PER_RATE, "Summe der Steuerbasen je Satz ≠ BT-106", taxableSum, bt106, tolerance);
        }
        if (checks.contains(PlausibilityCheck.TAX_AMOUNT_PER_RATE)) {
            // Je Steuersatz: extrahierter Steuerbetrag (BT-117) gegen berechneten, sofern vorhanden.
            Optional<BigDecimal> bt117 = data.decimal(BusinessTerm.BT_117);
            Optional<BigDecimal> bt116 = data.decimal(BusinessTerm.BT_116);
            Optional<BigDecimal> bt119 = data.decimal(BusinessTerm.BT_119);
            if (bt116.isPresent() && bt119.isPresent() && bt117.isPresent()) {
                BigDecimal expected = bt116.get().multiply(bt119.get()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
                compare(issues, PlausibilityCheck.TAX_AMOUNT_PER_RATE, "BT-116 × BT-119 ≠ BT-117", expected, bt117, tolerance);
            }
        }
        if (checks.contains(PlausibilityCheck.TOTAL_TAX) && linesComplete && !taxLines.isEmpty()) {
            compare(issues, PlausibilityCheck.TOTAL_TAX, "Summe der Steuerbeträge je Satz ≠ BT-110", taxSum, bt110, tolerance);
        }
        if (checks.contains(PlausibilityCheck.GROSS_TOTAL) && bt109.isPresent() && bt110.isPresent()) {
            compare(issues, PlausibilityCheck.GROSS_TOTAL, "BT-109 + BT-110 ≠ BT-112", bt109.get().add(bt110.get()), bt112, tolerance);
        }
        if (checks.contains(PlausibilityCheck.PAYABLE_AMOUNT) && bt112.isPresent()) {
            compare(issues, PlausibilityCheck.PAYABLE_AMOUNT, "BT-112 − BT-113 + BT-114 ≠ BT-115",
                    bt112.get().subtract(bt113).add(bt114), bt115, tolerance);
        }
        return PlausibilityResult.of(issues, taxLines);
    }

    private static void compare(List<PlausibilityIssue> issues, PlausibilityCheck check, String label,
                                BigDecimal expected, Optional<BigDecimal> actual, BigDecimal tolerance) {
        if (actual.isEmpty()) {
            issues.add(new PlausibilityIssue(check, label + " – Vergleichswert nicht extrahiert", expected.toPlainString(), null));
            return;
        }
        BigDecimal diff = expected.subtract(actual.get()).abs();
        if (diff.compareTo(tolerance) > 0) {
            issues.add(new PlausibilityIssue(check, label + " (Abweichung " + diff.toPlainString() + ")",
                    expected.toPlainString(), actual.get().toPlainString()));
        }
    }
}
