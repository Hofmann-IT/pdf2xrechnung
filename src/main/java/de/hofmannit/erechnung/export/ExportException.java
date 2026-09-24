package de.hofmannit.erechnung.export;

/** Export nicht möglich (Konfiguration unvollständig oder Daten verletzen das Zielformat). */
public class ExportException extends Exception {

    public ExportException(String message) {
        super(message);
    }
}
