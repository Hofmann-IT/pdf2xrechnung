package de.hofmannit.erechnung.extraction;

import java.util.List;

/**
 * Positionsbasiertes Extraktionsergebnis einer Seite. Koordinaten in PDF-Punkten, Ursprung
 * links oben, y nach unten.
 *
 * @param pageNumber 1-basiert
 * @param width      Seitenbreite
 * @param height     Seitenhöhe
 * @param fragments  alle Textfragmente in Lesereihenfolge
 * @param lines      rekonstruierte Zeilen, von oben nach unten
 */
public record ExtractedPage(int pageNumber, double width, double height, List<PositionedText> fragments, List<TextLine> lines) {

    public ExtractedPage {
        fragments = List.copyOf(fragments);
        lines = List.copyOf(lines);
    }

    /** Seitentext zeilenweise, für Regex- und Indikatorsuche. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (TextLine line : lines) {
            sb.append(line.text()).append('\n');
        }
        return sb.toString();
    }

    public boolean hasText() {
        return fragments.stream().anyMatch(f -> !f.text().isBlank());
    }
}
