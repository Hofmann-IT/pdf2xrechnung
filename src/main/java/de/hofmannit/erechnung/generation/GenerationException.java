package de.hofmannit.erechnung.generation;

/** Erzeugung eines Ausgabeformats fehlgeschlagen (fachlich oder technisch). */
public class GenerationException extends Exception {

    public GenerationException(String message) {
        super(message);
    }

    public GenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
