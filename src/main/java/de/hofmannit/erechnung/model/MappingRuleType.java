package de.hofmannit.erechnung.model;

/**
 * Die unterstützten Mapping-Regelarten (Vorgabe Abschnitt 7). Die YAML-Schreibweise
 * im Profil ist der Enum-Name in Kleinbuchstaben ({@code anchor}, {@code regex}, {@code region},
 * {@code table}, {@code fixed}); Spring bindet beide Schreibweisen.
 */
public enum MappingRuleType {
    /** Label/Textanker plus relative Position. */
    ANCHOR,
    /** Regulärer Ausdruck über den Seitentext. */
    REGEX,
    /** Definierter Bereich auf der Seite. */
    REGION,
    /** Spaltenbasiertes Tabellen-Mapping für Rechnungspositionen. */
    TABLE,
    /** Fester Wert aus {@code config/tenant.yaml}. */
    FIXED
}
