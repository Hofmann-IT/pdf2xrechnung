package de.hofmannit.erechnung.configuration.profile;

/** Ungültiges oder nicht ladbares Profil / ungültige Mandantenkonfiguration. */
public class ProfileException extends RuntimeException {

    public ProfileException(String message) {
        super(message);
    }

    public ProfileException(String message, Throwable cause) {
        super(message, cause);
    }
}
