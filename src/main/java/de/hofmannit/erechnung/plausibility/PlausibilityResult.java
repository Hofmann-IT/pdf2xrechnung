package de.hofmannit.erechnung.plausibility;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Ergebnis der Plausibilitätsprüfung.
 *
 * @param issues    Abweichungen; leer = bestanden
 * @param taxLines  Steueraufschlüsselung je Steuersatz (aus Positionen bzw. Kopfdaten abgeleitet)
 */
public record PlausibilityResult(List<PlausibilityIssue> issues, List<TaxLine> taxLines) {

    public PlausibilityResult {
        issues = List.copyOf(issues);
        taxLines = List.copyOf(taxLines);
    }

    public boolean passed() {
        return issues.isEmpty();
    }

    /** Netto und Steuer je Steuersatz/Steuerkategorie (BT-116, BT-117, BT-118, BT-119). */
    public record TaxLine(String categoryCode, BigDecimal rate, BigDecimal taxableAmount, BigDecimal taxAmount) {
    }

    /** Zusammenfassung als Text für manual-review-Begründung. */
    public String describe() {
        if (issues.isEmpty()) {
            return "Plausibilität bestanden";
        }
        StringBuilder sb = new StringBuilder();
        for (PlausibilityIssue i : issues) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(i.check() == null ? "" : i.check() + ": ").append(i.message());
        }
        return sb.toString();
    }

    public static PlausibilityResult of(List<PlausibilityIssue> issues, Map<BigDecimal, TaxLine> lines) {
        return new PlausibilityResult(issues, lines.values().stream().toList());
    }
}
