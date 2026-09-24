package de.hofmannit.erechnung.generation;

import java.nio.file.Path;

import de.hofmannit.erechnung.model.OutputFormat;

/**
 * Ein erzeugtes Ausgabedokument im Arbeitsverzeichnis des Runs (noch nicht validiert, noch nicht
 * archiviert).
 *
 * @param format    Ausgabeformat
 * @param path      Datei im Arbeitsverzeichnis
 * @param fileName  Zieldateiname laut Profil-Template inklusive Endung (für output/)
 */
public record GeneratedArtifact(OutputFormat format, Path path, String fileName) {

    /** Fester Dateiname im Archiv (Vorgabe Abschnitt 21). */
    public String archiveName() {
        return switch (format) {
            case XRECHNUNG_CII -> "invoice-cii.xml";
            case XRECHNUNG_UBL -> "invoice-ubl.xml";
            case ZUGFERD_EN16931, ZUGFERD_XRECHNUNG -> "invoice-zugferd.pdf";
        };
    }

    public static String extension(OutputFormat format) {
        return switch (format) {
            case XRECHNUNG_CII, XRECHNUNG_UBL -> ".xml";
            case ZUGFERD_EN16931, ZUGFERD_XRECHNUNG -> ".pdf";
        };
    }

    public static String suffix(OutputFormat format) {
        return switch (format) {
            case XRECHNUNG_CII -> "_xrechnung-cii";
            case XRECHNUNG_UBL -> "_xrechnung-ubl";
            case ZUGFERD_EN16931 -> "_zugferd";
            case ZUGFERD_XRECHNUNG -> "_zugferd-xrechnung";
        };
    }
}
