package de.hofmannit.erechnung.validation;

/**
 * Gesamtergebnis eines einzelnen Validators für ein Dokument.
 */
public enum ValidationOutcome {
    /** Keine Fehler (Warnungen möglich). */
    VALID,
    /** Mindestens ein Fehler laut Regelwerk. */
    INVALID,
    /** Validator ist für dieses Format/Profil fachlich oder technisch nicht anwendbar. */
    NOT_APPLICABLE,
    /** Validator konnte nicht ausgeführt werden (technischer Fehler, fehlende Ressourcen). */
    ERROR
}
