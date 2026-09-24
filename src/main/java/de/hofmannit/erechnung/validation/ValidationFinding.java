package de.hofmannit.erechnung.validation;

/**
 * Einzelne Meldung eines Validators (Vorgabe Abschnitt 13 "Für jeden Fehler").
 *
 * @param severity        Schweregrad laut Regelwerk (nicht herabgestuft)
 * @param ruleId          Regel-ID, z. B. {@code BR-DE-1}, oder {@code null}
 * @param description     verständliche Beschreibung (kann der Originalmeldung entsprechen)
 * @param location        XPath / Position, sofern vorhanden
 * @param affectedValue   betroffener Wert, sofern vorhanden
 * @param originalMessage technische Originalmeldung des Validators
 */
public record ValidationFinding(
        Severity severity,
        String ruleId,
        String description,
        String location,
        String affectedValue,
        String originalMessage) {

    public enum Severity {
        ERROR,
        WARNING,
        INFORMATION
    }
}
