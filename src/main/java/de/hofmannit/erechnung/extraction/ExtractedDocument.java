package de.hofmannit.erechnung.extraction;

import java.util.List;
import java.util.Optional;

/**
 * Positionsbasiertes Extraktionsergebnis eines PDF-Dokuments.
 *
 * @param pages Seiten in Reihenfolge
 */
public record ExtractedDocument(List<ExtractedPage> pages) {

    public ExtractedDocument {
        pages = List.copyOf(pages);
    }

    public int pageCount() {
        return pages.size();
    }

    public Optional<ExtractedPage> page(int pageNumber) {
        if (pageNumber < 1 || pageNumber > pages.size()) {
            return Optional.empty();
        }
        return Optional.of(pages.get(pageNumber - 1));
    }

    /** Text aller Seiten, durch Seitenumbruch (Form Feed) getrennt. */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (ExtractedPage p : pages) {
            sb.append(p.text()).append('\f');
        }
        return sb.toString();
    }

    /** Eine PDF ohne Textebene (z. B. reiner Scan) kann nicht extrahiert werden. */
    public boolean hasTextLayer() {
        return pages.stream().anyMatch(ExtractedPage::hasText);
    }
}
