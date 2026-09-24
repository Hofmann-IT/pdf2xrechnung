package de.hofmannit.erechnung.extraction;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

/**
 * Positionsbasierte Textextraktion mit Apache PDFBox 3 (ADR 0001).
 *
 * <p>Die Fragmente werden über den Erweiterungspunkt
 * {@link PDFTextStripper#writeString(String, List)} eingesammelt. PDFBox liefert mit
 * {@link TextPosition#getXDirAdj()} / {@link TextPosition#getYDirAdj()} Koordinaten mit Ursprung
 * links oben (y = Grundlinie), sodass keine eigene Achsenumrechnung erforderlich ist.
 * Fragmente werden an Leerzeichen und an größeren horizontalen Lücken in Wörter/Zellen
 * zerlegt und anschließend zu Zeilen gruppiert.
 */
@Component
public class PdfTextExtractor {

    /**
     * Lücke (relativ zur Schriftgröße), ab der ein neues Fragment beginnt. Wortabstände liegen
     * typischerweise bei 0,25–0,35 em, Spaltenabstände deutlich darüber. Die Leerzeichenbreite aus
     * {@link TextPosition#getWidthOfSpace()} ist bei eingebetteten Type0-Schriften nicht verlässlich.
     */
    private static final double GAP_FACTOR = 0.6;

    public ExtractedDocument extract(Path pdf) throws ExtractionException {
        try (PDDocument document = Loader.loadPDF(pdf.toFile())) {
            if (document.isEncrypted()) {
                throw new ExtractionException("PDF ist verschlüsselt: " + pdf.getFileName());
            }
            List<ExtractedPage> pages = new ArrayList<>();
            int count = document.getNumberOfPages();
            for (int i = 0; i < count; i++) {
                PDPage page = document.getPage(i);
                PDRectangle box = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                CollectingStripper stripper = new CollectingStripper(i + 1);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                stripper.setSortByPosition(true);
                stripper.getText(document);
                List<PositionedText> fragments = stripper.fragments();
                pages.add(new ExtractedPage(i + 1, box.getWidth(), box.getHeight(), fragments, buildLines(i + 1, fragments)));
            }
            return new ExtractedDocument(pages);
        } catch (IOException e) {
            throw new ExtractionException("PDF konnte nicht gelesen werden: " + pdf.getFileName() + " – " + e.getMessage(), e);
        }
    }

    /** Gruppiert Fragmente zu Zeilen: gleiche Grundlinie (± halbe Zeilenhöhe), nach x sortiert. */
    static List<TextLine> buildLines(int page, List<PositionedText> fragments) {
        List<PositionedText> sorted = new ArrayList<>(fragments);
        sorted.sort(Comparator.comparingDouble(PositionedText::bottom).thenComparingDouble(PositionedText::x));
        List<TextLine> lines = new ArrayList<>();
        List<PositionedText> current = new ArrayList<>();
        double currentBottom = Double.NaN;
        double currentHeight = 0;
        for (PositionedText f : sorted) {
            if (f.text().isBlank()) {
                continue;
            }
            double tolerance = Math.max(currentHeight, f.height()) * 0.5;
            if (current.isEmpty()) {
                current.add(f);
                currentBottom = f.bottom();
                currentHeight = f.height();
            } else if (Math.abs(f.bottom() - currentBottom) <= tolerance) {
                current.add(f);
                currentBottom = Math.max(currentBottom, f.bottom());
                currentHeight = Math.max(currentHeight, f.height());
            } else {
                lines.add(toLine(page, current));
                current = new ArrayList<>();
                current.add(f);
                currentBottom = f.bottom();
                currentHeight = f.height();
            }
        }
        if (!current.isEmpty()) {
            lines.add(toLine(page, current));
        }
        lines.sort(Comparator.comparingDouble(TextLine::top));
        return lines;
    }

    private static TextLine toLine(int page, List<PositionedText> fragments) {
        List<PositionedText> ordered = new ArrayList<>(fragments);
        ordered.sort(Comparator.comparingDouble(PositionedText::x));
        double top = ordered.stream().mapToDouble(PositionedText::y).min().orElse(0);
        double bottom = ordered.stream().mapToDouble(PositionedText::bottom).max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (PositionedText f : ordered) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(f.text());
        }
        return new TextLine(page, top, bottom, ordered, sb.toString());
    }

    /** Sammelt Fragmente je Seite; zerlegt an Leerzeichen und großen Lücken. */
    private static final class CollectingStripper extends PDFTextStripper {

        private final int page;
        private final List<PositionedText> fragments = new ArrayList<>();

        CollectingStripper(int page) {
            this.page = page;
        }

        List<PositionedText> fragments() {
            return fragments;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            List<TextPosition> chunk = new ArrayList<>();
            TextPosition previous = null;
            for (TextPosition tp : textPositions) {
                String unicode = tp.getUnicode();
                boolean whitespace = unicode == null || unicode.isBlank();
                boolean gap = previous != null && !chunk.isEmpty()
                        && tp.getXDirAdj() - (previous.getXDirAdj() + previous.getWidthDirAdj())
                        > Math.max(1.5, GAP_FACTOR * Math.max(previous.getFontSizeInPt(), 1f));
                if (whitespace || gap) {
                    flush(chunk);
                    chunk = new ArrayList<>();
                }
                if (!whitespace) {
                    chunk.add(tp);
                }
                previous = tp;
            }
            flush(chunk);
        }

        private void flush(List<TextPosition> chunk) {
            if (chunk.isEmpty()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            double left = Double.MAX_VALUE;
            double right = -Double.MAX_VALUE;
            double baseline = -Double.MAX_VALUE;
            double height = 0;
            for (TextPosition tp : chunk) {
                sb.append(tp.getUnicode());
                left = Math.min(left, tp.getXDirAdj());
                right = Math.max(right, tp.getXDirAdj() + tp.getWidthDirAdj());
                baseline = Math.max(baseline, tp.getYDirAdj());
                height = Math.max(height, tp.getHeightDir() > 0 ? tp.getHeightDir() : tp.getFontSizeInPt());
            }
            if (height <= 0) {
                height = chunk.get(0).getFontSizeInPt();
            }
            double top = baseline - height;
            fragments.add(new PositionedText(page, left, top, right - left, height, sb.toString()));
        }
    }
}
