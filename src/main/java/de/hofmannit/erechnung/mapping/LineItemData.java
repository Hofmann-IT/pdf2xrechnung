package de.hofmannit.erechnung.mapping;

import java.util.Map;
import java.util.Optional;

import de.hofmannit.erechnung.model.BusinessTerm;

/**
 * Eine gemappte Rechnungsposition (BG-25) mit Nachweis je Spalte.
 *
 * @param lineNumber 1-basierte laufende Nummer der Position im Dokument
 * @param fields     Spaltenwerte je Business Term
 */
public record LineItemData(int lineNumber, Map<BusinessTerm, FieldEvidence> fields) {

    public LineItemData {
        fields = Map.copyOf(fields);
    }

    public Optional<String> value(BusinessTerm bt) {
        FieldEvidence e = fields.get(bt);
        return e == null || e.value() == null ? Optional.empty() : Optional.of(e.value());
    }
}
