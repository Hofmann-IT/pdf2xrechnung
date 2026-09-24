package de.hofmannit.erechnung.plausibility;

import java.math.BigDecimal;

/**
 * Numerische Standardtoleranz für Summenprüfungen (Vorgabe Abschnitt 9): 0,01 EUR.
 * Eine abweichende Rundungsregel muss im Profil ausdrücklich definiert werden.
 */
public final class PlausibilityTolerance {

    public static final BigDecimal DEFAULT = new BigDecimal("0.01");

    private PlausibilityTolerance() {
    }
}
