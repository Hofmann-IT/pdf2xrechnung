package de.hofmannit.erechnung.dispatch;

/** Versand oder Postprozess nicht möglich (Vorbedingung verletzt oder technischer Fehler). */
public class DispatchException extends Exception {

    public DispatchException(String message) {
        super(message);
    }

    public DispatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
