package de.hofmannit.erechnung.extraction;

/** PDF konnte nicht gelesen oder extrahiert werden. */
public class ExtractionException extends Exception {

    public ExtractionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ExtractionException(String message) {
        super(message);
    }
}
