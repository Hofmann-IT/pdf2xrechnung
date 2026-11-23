package de.hofmannit.erechnung.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * Sichert die Eigenschaften der mitgelieferten Rechnungsvorlagen ab, von denen das Profil
 * „standard" abhängt: Es liest Positionen auf Folgeseiten nur, wenn der Tabellenkopf dort
 * wiederholt wird ({@code headerRepeatsOnContinuationPages}). Fehlt die Wiederholung in der
 * Vorlage, bleiben mehrseitige Rechnungen in der manuellen Prüfung.
 */
class InvoiceTemplateFilesTest {

    private static final String DIR = "/static/vorlagen/";

    @Test
    void wordTemplateRepeatsTheTableHeaderOnEveryPage() throws IOException {
        String document = entry("Rechnungsvorlage.docx", "word/document.xml");
        int header = document.indexOf(">Pos.<");
        assertThat(header).isPositive();
        int row = document.lastIndexOf("<w:tr>", header);
        String rowStart = document.substring(row, header);
        assertThat(rowStart).contains("<w:tblHeader/>");
        // genau eine Kopfzeile; Anschriften- und Summentabelle haben keine
        assertThat(document.split("<w:tblHeader/>", -1)).hasSize(2);
        assertThat(document).contains("Rechnungsnummer:", "Kunden-Nr.:", "E-Mail:", "Summe netto:", "Zahlbetrag:");
    }

    @Test
    void excelTemplateRepeatsTheTableHeaderAndCarriesNoPersonalData() throws IOException {
        String workbook = entry("Rechnungsvorlage.xlsx", "xl/workbook.xml");
        assertThat(workbook).contains("<definedName name=\"_xlnm.Print_Titles\" localSheetId=\"0\">Rechnung!$18:$18</definedName>");
        assertThat(workbook).contains("_xlnm.Print_Area").doesNotContain("absPath");
        String strings = entry("Rechnungsvorlage.xlsx", "xl/sharedStrings.xml");
        assertThat(strings).contains("Pos.", "Bezeichnung", "Rechnungsnummer:", "Kunden-Nr.:", "E-Mail:", "Summe netto:", "Gesamt netto:",
                "USt gesamt:", "Gesamtbetrag:", "Zahlbetrag:", "Zahlungsbedingungen:");
        assertThat(entry("Rechnungsvorlage.xlsx", "docProps/core.xml")).contains("<dc:creator>Hofmann IT</dc:creator>");
        assertThat(names("Rechnungsvorlage.xlsx")).doesNotContain("printerSettings").doesNotContain("vbaProject");
    }

    @Test
    void guideIsAPdf() throws IOException {
        try (InputStream in = InvoiceTemplateFilesTest.class.getResourceAsStream(DIR + "Rechnungsvorlage-anpassen.pdf")) {
            assertThat(in).isNotNull();
            assertThat(new String(in.readNBytes(5), StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        }
    }

    private static String entry(String file, String name) throws IOException {
        try (InputStream in = InvoiceTemplateFilesTest.class.getResourceAsStream(DIR + file)) {
            assertThat(in).as(file).isNotNull();
            try (ZipInputStream zip = new ZipInputStream(in)) {
                for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                    if (e.getName().equals(name)) {
                        return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            }
        }
        throw new AssertionError(name + " fehlt in " + file);
    }

    private static String names(String file) throws IOException {
        StringBuilder names = new StringBuilder();
        try (InputStream in = InvoiceTemplateFilesTest.class.getResourceAsStream(DIR + file); ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.append(e.getName()).append('\n');
            }
        }
        return names.toString();
    }
}
