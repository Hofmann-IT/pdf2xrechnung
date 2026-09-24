package de.hofmannit.erechnung.export;

import java.util.List;

/**
 * Ergebnis eines Exports.
 *
 * @param fileName   vorgeschlagener Dateiname
 * @param contentType MIME-Typ inkl. Zeichensatz
 * @param content    Dateiinhalt
 * @param records    Anzahl exportierter Datensätze (Buchungen bzw. Zeilen)
 * @param invoices   Anzahl berücksichtigter Rechnungen
 * @param skipped    Hinweise zu übersprungenen Vorgängen
 * @param warnings   Hinweise zu Anpassungen (z. B. ersetzte Zeichen in Belegfeld 1)
 * @param sha256     SHA-256 des Inhalts (für das Protokoll)
 */
public record ExportResult(String fileName, String contentType, byte[] content, int records, int invoices,
                           List<String> skipped, List<String> warnings, String sha256) {

    public ExportResult {
        skipped = List.copyOf(skipped);
        warnings = List.copyOf(warnings);
    }
}
