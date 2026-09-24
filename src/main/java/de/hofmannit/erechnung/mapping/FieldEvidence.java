package de.hofmannit.erechnung.mapping;

import java.util.Optional;

import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.MappingRuleType;

/**
 * Nachvollziehbarkeits-Datensatz für ein gemapptes Business-Term-Feld
 * (Vorgabe Abschnitt 8: Wert, Regel, Seite, Position, Quelltext, Transformation, Status).
 *
 * @param businessTerm    der Business Term
 * @param lineNumber      Positionsnummer bei positionsbezogenen Terms, sonst {@code null}
 * @param value           der extrahierte, transformierte Wert (kanonische Darstellung)
 * @param ruleId          Kennung der Mapping-Regel aus dem Profil
 * @param ruleType        Regelart
 * @param page            1-basierte Seite oder {@code null} (z. B. Fixed Value)
 * @param boundingBox     Fundstelle oder {@code null}
 * @param sourceText      Roh-Quelltext vor Transformation
 * @param transformation  Beschreibung der angewandten Transformation(en)
 * @param status          Validierungsstatus des Feldes
 * @param message         optionale Fehler-/Warnmeldung
 */
public record FieldEvidence(
        BusinessTerm businessTerm,
        Integer lineNumber,
        String value,
        String ruleId,
        MappingRuleType ruleType,
        Integer page,
        BoundingBox boundingBox,
        String sourceText,
        String transformation,
        FieldStatus status,
        String message) {

    public Optional<BoundingBox> boundingBoxOptional() {
        return Optional.ofNullable(boundingBox);
    }

    /** Bounding Box in PDF-Punkten, Ursprung links oben. */
    public record BoundingBox(double x, double y, double width, double height) {
    }

    /** Validierungsstatus eines gemappten Feldes. */
    public enum FieldStatus {
        /** Wert gefunden und formal gültig. */
        OK,
        /** Wert gefunden, aber formal auffällig (z. B. Datum nicht plausibel). */
        WARNING,
        /** Pflichtfeld nicht gefunden oder formal ungültig. */
        ERROR,
        /** Optionales Feld nicht gefunden. */
        NOT_FOUND
    }
}
