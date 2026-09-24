package de.hofmannit.erechnung.extraction;

/**
 * Ein extrahiertes Textfragment mit seiner Position auf der PDF-Seite.
 *
 * <p>Koordinaten in PDF-Punkten (1/72 Zoll), Ursprung links oben der Seite
 * (y wächst nach unten), damit Regionen in Profilen intuitiv angegeben werden können.
 *
 * @param page   1-basierte Seitennummer
 * @param x      linke Kante
 * @param y      obere Kante
 * @param width  Breite
 * @param height Höhe
 * @param text   der Text des Fragments
 */
public record PositionedText(int page, double x, double y, double width, double height, String text) {

    public double right() {
        return x + width;
    }

    public double bottom() {
        return y + height;
    }
}
