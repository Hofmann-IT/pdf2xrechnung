package de.hofmannit.erechnung.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import de.hofmannit.erechnung.configuration.CertificateSource;

/**
 * Selbstsignierte Testzertifikate in einem PKCS12-Speicher unter {@code target/test-data},
 * erzeugt mit dem {@code keytool} der laufenden Java-Laufzeit (keine zusätzliche Bibliothek).
 * Das Speicherpasswort ist ein reiner Testwert.
 */
public final class TestCertificates {

    private static final String STORE_PASSWORD = "nur-fuer-tests";

    private final Path file;

    private TestCertificates(Path file) {
        this.file = file;
    }

    public static TestCertificates create(String prefix) {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            Path dir = Files.createTempDirectory(base, prefix).toAbsolutePath();
            return new TestCertificates(dir.resolve("test-zertifikate.p12"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fügt ein Schlüsselpaar mit selbstsigniertem Zertifikat hinzu; {@code startOffsetDays} verschiebt den Gültigkeitsbeginn. */
    public TestCertificates add(String alias, String commonName, int startOffsetDays, int validityDays) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-dname", "CN=" + commonName, "-ext", "SAN=dns:localhost,dns:" + commonName.toLowerCase(java.util.Locale.ROOT),
                "-startdate", (startOffsetDays >= 0 ? "+" : "") + startOffsetDays + "d", "-validity", String.valueOf(validityDays),
                "-storetype", "PKCS12", "-keystore", file.toString(), "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool fehlgeschlagen: " + output);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return this;
    }

    public KeyStore keyStore() {
        try (InputStream in = Files.newInputStream(file)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, STORE_PASSWORD.toCharArray());
            return store;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Quelle wie der Windows-Speicher im Betrieb, nur mit Schlüsselpasswort. */
    public CertificateSource source() {
        return () -> new CertificateSource.Loaded(keyStore(), STORE_PASSWORD.toCharArray());
    }

    /** Leerer Speicher: es ist kein Zertifikat eingerichtet. */
    public static CertificateSource emptySource() {
        return () -> {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            return new CertificateSource.Loaded(store, null);
        };
    }
}
