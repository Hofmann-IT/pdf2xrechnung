package de.hofmannit.erechnung.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.color.PDOutputIntent;
import org.apache.xmpbox.XMPMetadata;
import org.apache.xmpbox.schema.PDFAIdentificationSchema;
import org.apache.xmpbox.xml.XmpSerializer;

/**
 * Erzeugt mit PDFBox eine PDF/A-1b-Testrechnung, deren Layout exakt zu
 * {@code profiles/standard.yaml} passt (Anker, Regionen, Tabellenspalten). Kein externes
 * Testdokument (Vorgabe Phase 2).
 *
 * <p>Koordinaten werden als "Grundlinie von oben" angegeben und in PDF-Koordinaten
 * (Ursprung unten links) umgerechnet. Schrift: LiberationSans aus dem PDFBox-Jar (eingebettet,
 * PDF/A-konform); Output-Intent: sRGB-Profil aus dem Mustang-Jar.
 */
public final class TestInvoicePdf {

    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float FONT_SIZE = 10f;

    public record Line(String pos, String description, String continuation, String quantity, String unit, String unitPrice,
                       String vatRate, String lineTotal) {
    }

    public record Totals(String net, String netPerRate, String vatRatePercent, String vatAmount, String gross, String payable) {
    }

    private String title = "Rechnung";
    private String invoiceNumber = "RE-2026-4711";
    private String invoiceDate = "24.09.2026";
    private String deliveryDate = "24.09.2026";
    private String dueDate = "08.10.2026";
    private String leitwegId = "04011000-12345-67";
    private String orderReference = "B-2026-0815";
    private String customerName = "Beispiel GmbH";
    private String customerStreet = "Kundenweg 2";
    private String customerCity = "54321 Kundenstadt";
    private String customerEmail = "buchhaltung@example.invalid";
    private boolean secondPage = true;
    private final List<Line> lines = new ArrayList<>();
    private Totals totals;

    public static TestInvoicePdf standardInvoice() {
        TestInvoicePdf pdf = new TestInvoicePdf();
        pdf.lines.add(new Line("1", "IT-Beratung", "September 2026", "8,00", "HUR", "120,00", "19", "960,00"));
        pdf.lines.add(new Line("2", "Softwarelizenz", null, "2,00", "C62", "250,00", "19", "500,00"));
        pdf.lines.add(new Line("3", "Support-Pauschale", null, "1,00", "C62", "100,00", "19", "100,00"));
        pdf.totals = new Totals("1.560,00", "1.560,00", "19", "296,40", "1.856,40", "1.856,40");
        return pdf;
    }

    public TestInvoicePdf title(String t) {
        this.title = t;
        return this;
    }

    public TestInvoicePdf invoiceNumber(String n) {
        this.invoiceNumber = n;
        return this;
    }

    public TestInvoicePdf totals(Totals t) {
        this.totals = t;
        return this;
    }

    public TestInvoicePdf singlePage() {
        this.secondPage = false;
        return this;
    }

    public void writeTo(Path target) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font;
            try (InputStream ttf = TestInvoicePdf.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDType0Font.load(doc, ttf, true);
            }
            List<Line> firstPageLines = secondPage ? lines.subList(0, Math.min(2, lines.size())) : lines;
            List<Line> secondPageLines = secondPage && lines.size() > 2 ? lines.subList(2, lines.size()) : List.of();

            // ---------- Seite 1
            PDPage page1 = new PDPage(PDRectangle.A4);
            doc.addPage(page1);
            try (Writer w = new Writer(doc, page1, font)) {
                w.text(70, 160, customerName);
                w.text(70, 174, customerStreet);
                w.text(70, 188, customerCity);
                w.text(70, 205, "E-Mail: " + customerEmail);

                w.text(350, 150, "Rechnungsnummer:");
                w.text(450, 150, invoiceNumber);
                w.text(350, 165, "Rechnungsdatum: " + invoiceDate);
                w.text(350, 180, "Leistungsdatum:");
                w.text(450, 180, deliveryDate);
                w.text(350, 195, "Leitweg-ID:");
                w.text(450, 195, leitwegId);
                w.text(350, 210, "Ihre Bestellung:");
                w.text(450, 210, orderReference);

                w.text(70, 240, title);
                float y = tableHeader(w, 270);
                for (Line l : firstPageLines) {
                    y = row(w, y, l);
                }
                if (!secondPageLines.isEmpty()) {
                    w.text(70, y + 10, "Fortsetzung auf Seite 2");
                }
                if (secondPageLines.isEmpty()) {
                    footer(w, y + 20);
                }
            }

            // ---------- Seite 2 (Fortsetzung mit wiederholtem Tabellenkopf)
            if (!secondPageLines.isEmpty()) {
                PDPage page2 = new PDPage(PDRectangle.A4);
                doc.addPage(page2);
                try (Writer w = new Writer(doc, page2, font)) {
                    w.text(70, 100, "Seite 2 von 2 - " + title + " " + invoiceNumber);
                    w.text(70, 120, "Übertrag");
                    float y = tableHeader(w, 140);
                    for (Line l : secondPageLines) {
                        y = row(w, y, l);
                    }
                    footer(w, y + 20);
                }
            }
            addPdfA1Metadata(doc);
            doc.save(target.toFile());
        }
    }

    private static float tableHeader(Writer w, float y) throws IOException {
        w.text(56, y, "Pos.");
        w.text(90, y, "Bezeichnung");
        w.textRight(360, y, "Menge");
        w.text(362, y, "Einheit");
        w.textRight(460, y, "Einzelpreis");
        w.textRight(498, y, "USt");
        w.textRight(570, y, "Gesamt");
        return y + 18;
    }

    private static float row(Writer w, float y, Line l) throws IOException {
        w.text(56, y, l.pos());
        w.text(90, y, l.description());
        w.textRight(360, y, l.quantity());
        w.text(362, y, l.unit());
        w.textRight(460, y, l.unitPrice());
        w.textRight(498, y, l.vatRate() + " %");
        w.textRight(570, y, l.lineTotal());
        y += 14;
        if (l.continuation() != null) {
            w.text(90, y, l.continuation());
            y += 14;
        }
        return y;
    }

    private void footer(Writer w, float y) throws IOException {
        w.text(350, y, "Summe netto:");
        w.textRight(570, y, totals.net());
        y += 14;
        w.text(350, y, "Netto " + totals.vatRatePercent() + " %:");
        w.textRight(570, y, totals.netPerRate());
        y += 14;
        w.text(350, y, "USt " + totals.vatRatePercent() + " %:");
        w.textRight(570, y, totals.vatAmount());
        y += 14;
        w.text(350, y, "Gesamt netto:");
        w.textRight(570, y, totals.net());
        y += 14;
        w.text(350, y, "USt gesamt:");
        w.textRight(570, y, totals.vatAmount());
        y += 14;
        w.text(350, y, "Gesamtbetrag:");
        w.textRight(570, y, totals.gross());
        y += 14;
        w.text(350, y, "Zahlbetrag:");
        w.textRight(570, y, totals.payable());
        y += 28;
        w.text(70, y, "Zahlungsbedingungen:");
        w.text(180, y, "Zahlbar innerhalb von 14 Tagen ohne Abzug, zahlbar bis " + dueDate);
        y += 14;
        w.text(70, y, "Vielen Dank für Ihren Auftrag.");
    }

    private static void addPdfA1Metadata(PDDocument doc) throws Exception {
        XMPMetadata xmp = XMPMetadata.createXMPMetadata();
        PDFAIdentificationSchema id = xmp.createAndAddPDFAIdentificationSchema();
        id.setPart(1);
        id.setConformance("B");
        xmp.createAndAddDublinCoreSchema().setTitle("Testrechnung");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        new XmpSerializer().serialize(xmp, bos, true);
        PDMetadata md = new PDMetadata(doc);
        md.importXMPMetadata(bos.toByteArray());
        doc.getDocumentCatalog().setMetadata(md);
        try (InputStream icc = TestInvoicePdf.class.getResourceAsStream("/sRGB.icc")) {
            PDOutputIntent oi = new PDOutputIntent(doc, icc);
            oi.setInfo("sRGB IEC61966-2.1");
            oi.setOutputCondition("sRGB IEC61966-2.1");
            oi.setOutputConditionIdentifier("sRGB IEC61966-2.1");
            oi.setRegistryName("http://www.color.org");
            doc.getDocumentCatalog().addOutputIntent(oi);
        }
    }

    /** Schreibt Text mit Grundlinie "von oben" gemessen. */
    private static final class Writer implements AutoCloseable {
        private final PDPageContentStream cs;
        private final PDType0Font font;

        Writer(PDDocument doc, PDPage page, PDType0Font font) throws IOException {
            this.cs = new PDPageContentStream(doc, page);
            this.font = font;
        }

        void text(float x, float baselineFromTop, String text) throws IOException {
            cs.beginText();
            cs.setFont(font, FONT_SIZE);
            cs.newLineAtOffset(x, PAGE_HEIGHT - baselineFromTop);
            cs.showText(text);
            cs.endText();
        }

        void textRight(float rightEdge, float baselineFromTop, String text) throws IOException {
            float width = font.getStringWidth(text) / 1000f * FONT_SIZE;
            text(rightEdge - width, baselineFromTop, text);
        }

        @Override
        public void close() throws IOException {
            cs.close();
        }
    }
}
