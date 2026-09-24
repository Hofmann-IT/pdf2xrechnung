package de.hofmannit.erechnung.plausibility;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.PlausibilityCheck;

/**
 * Eine festgestellte Abweichung oder ein fehlender Pflichtwert.
 *
 * @param check     betroffene Prüfung oder {@code null} bei fehlenden Pflichtfeldern
 * @param message   lesbare Begründung (für manual-review und UI)
 * @param expected  erwarteter Wert (kanonisch) oder {@code null}
 * @param actual    tatsächlicher Wert oder {@code null}
 */
public record PlausibilityIssue(PlausibilityCheck check, String message, String expected, String actual) {
}
