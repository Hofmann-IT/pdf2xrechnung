package de.hofmannit.erechnung.configuration;

import java.security.KeyStore;

/**
 * Quelle des Serverzertifikats für den Netzzugriff (ADR 0015). Im Betrieb der
 * Windows-Zertifikatsspeicher: der private Schlüssel bleibt dort und ist nicht exportierbar,
 * es gibt weder eine Schlüsseldatei noch ein Passwort. Tests liefern einen eigenen Speicher.
 */
@FunctionalInterface
public interface CertificateSource {

    /** Geladener Speicher; {@code keyPassword} ist beim Windows-Speicher {@code null}. */
    record Loaded(KeyStore keyStore, char[] keyPassword) {
    }

    Loaded load() throws Exception;
}
