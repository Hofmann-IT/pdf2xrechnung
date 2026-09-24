package de.hofmannit.erechnung.mapping;

import java.util.List;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.BusinessCase;
import de.hofmannit.erechnung.model.DocumentType;

/**
 * Ergebnis der Klassifizierung (Vorgabe Abschnitt 6).
 *
 * @param documentType  erkannter Typ oder {@code null} bei Nicht-Rechnung
 * @param businessCase  erkannter Geschäftsfall oder {@code null} bei Nicht-Rechnung
 * @param pageCount     Seitenzahl
 * @param reasons       nachvollziehbare Begründung (getroffene Indikatoren bzw. Ablehnungsgrund)
 */
public record ClassificationResult(DocumentType documentType, BusinessCase businessCase, int pageCount, List<String> reasons) {

    public ClassificationResult {
        reasons = List.copyOf(reasons);
    }

    public boolean isInvoice() {
        return documentType != null;
    }

    public boolean isMultiPage() {
        return pageCount > 1;
    }
}
