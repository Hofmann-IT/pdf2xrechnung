package de.hofmannit.erechnung.extraction;

import java.util.List;

/**
 * Eine aus Textfragmenten rekonstruierte Zeile (gleiche Grundlinie, nach x sortiert).
 *
 * @param page      1-basierte Seite
 * @param top       obere Kante der Zeile
 * @param bottom    untere Kante der Zeile
 * @param fragments Fragmente in Leserichtung
 * @param text      zusammengesetzter Text (Fragmente durch ein Leerzeichen getrennt)
 */
public record TextLine(int page, double top, double bottom, List<PositionedText> fragments, String text) {

    public TextLine {
        fragments = List.copyOf(fragments);
    }

    public double left() {
        return fragments.isEmpty() ? 0 : fragments.get(0).x();
    }

    public double right() {
        return fragments.isEmpty() ? 0 : fragments.get(fragments.size() - 1).right();
    }

    public double centerY() {
        return (top + bottom) / 2.0;
    }
}
