package de.hofmannit.erechnung.ledger;

import java.util.Locale;

/**
 * Korrelations-ID für Logging (Vorgabe Abschnitt 35): SHA-256-Präfix der Quell-PDF,
 * bei mehreren Runs ergänzt um die Run-Nummer, z. B. {@code abc123ef/run-002}.
 *
 * @param value der formatierte Wert
 */
public record CorrelationId(String value) {

    /** Anzahl Hex-Zeichen des SHA-256-Präfixes. */
    public static final int PREFIX_LENGTH = 8;

    /** MDC-Schlüssel, unter dem die Korrelations-ID geloggt wird. */
    public static final String MDC_KEY = "correlationId";

    public CorrelationId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Korrelations-ID darf nicht leer sein");
        }
    }

    /** Korrelations-ID vor Anlage eines Runs (nur SHA-256-Präfix). */
    public static CorrelationId forSource(String sha256Hex) {
        return new CorrelationId(prefix(sha256Hex));
    }

    /** Korrelations-ID für einen konkreten Run, z. B. {@code abc123ef/run-002}. */
    public static CorrelationId forRun(String sha256Hex, int runNumber) {
        if (runNumber < 1) {
            throw new IllegalArgumentException("Run-Nummer muss >= 1 sein");
        }
        return new CorrelationId(prefix(sha256Hex) + "/run-" + String.format(Locale.ROOT, "%03d", runNumber));
    }

    private static String prefix(String sha256Hex) {
        if (sha256Hex == null || sha256Hex.length() < PREFIX_LENGTH) {
            throw new IllegalArgumentException("SHA-256 muss mindestens " + PREFIX_LENGTH + " Zeichen haben");
        }
        return sha256Hex.substring(0, PREFIX_LENGTH).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return value;
    }
}
