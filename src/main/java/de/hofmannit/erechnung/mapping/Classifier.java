package de.hofmannit.erechnung.mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.BusinessCase;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Indicator;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.ExtractedPage;
import de.hofmannit.erechnung.model.DocumentType;

import org.springframework.stereotype.Component;

/**
 * Klassifiziert ein extrahiertes Dokument anhand der Profilindikatoren (Vorgabe Abschnitt 6).
 * Reihenfolge: Nicht-Rechnung → Gutschrift → Rechnung → sonst Nicht-Rechnung.
 */
@Component
public class Classifier {

    public ClassificationResult classify(ExtractedDocument document, ProfileDefinition profile) {
        ProfileDefinition.Classification c = profile.classification();
        int pages = document.pageCount();
        List<String> reasons = new ArrayList<>();

        if (!document.hasTextLayer()) {
            reasons.add("PDF enthält keine Textebene (vermutlich Scan); Extraktion nicht möglich");
            return new ClassificationResult(null, null, pages, reasons);
        }

        Optional<String> nonInvoice = firstMatch(document, c.nonInvoiceIndicators());
        if (nonInvoice.isPresent()) {
            reasons.add("Nicht-Rechnungs-Indikator getroffen: " + nonInvoice.get());
            return new ClassificationResult(null, null, pages, reasons);
        }

        DocumentType type = null;
        Optional<String> credit = firstMatch(document, c.creditNoteIndicators());
        if (credit.isPresent()) {
            type = DocumentType.CREDIT_NOTE;
            reasons.add("Gutschrift-Indikator getroffen: " + credit.get());
        } else {
            Optional<String> invoice = firstMatch(document, c.invoiceIndicators());
            if (invoice.isPresent()) {
                type = DocumentType.INVOICE;
                reasons.add("Rechnungs-Indikator getroffen: " + invoice.get());
            }
        }
        if (type == null) {
            reasons.add("Kein Rechnungs- oder Gutschrift-Indikator des Profils '" + profile.profile().name() + "' getroffen");
            return new ClassificationResult(null, null, pages, reasons);
        }

        BusinessCase selected = null;
        for (BusinessCase bc : c.businessCases()) {
            if (bc.defaultCase()) {
                continue;
            }
            Optional<String> hit = firstMatch(document, bc.indicators());
            if (hit.isPresent()) {
                selected = bc;
                reasons.add("Geschäftsfall " + bc.id() + " über Indikator: " + hit.get());
                break;
            }
        }
        if (selected == null) {
            selected = c.businessCases().stream().filter(BusinessCase::defaultCase).findFirst().orElse(null);
            reasons.add("Geschäftsfall " + (selected == null ? "unbekannt" : selected.id()) + " (Standard)");
        }
        reasons.add(pages > 1 ? "mehrseitig (" + pages + " Seiten)" : "einseitig");
        return new ClassificationResult(type, selected, pages, reasons);
    }

    private static Optional<String> firstMatch(ExtractedDocument document, List<Indicator> indicators) {
        for (Indicator ind : indicators) {
            Pattern p = Pattern.compile(ind.regex(), Pattern.MULTILINE);
            if (ind.page() != null) {
                Optional<ExtractedPage> page = document.page(ind.page());
                if (page.isPresent() && p.matcher(page.get().text()).find()) {
                    return Optional.of(ind.regex() + " (Seite " + ind.page() + ")");
                }
            } else {
                for (ExtractedPage page : document.pages()) {
                    if (p.matcher(page.text()).find()) {
                        return Optional.of(ind.regex() + " (Seite " + page.pageNumber() + ")");
                    }
                }
            }
        }
        return Optional.empty();
    }
}
