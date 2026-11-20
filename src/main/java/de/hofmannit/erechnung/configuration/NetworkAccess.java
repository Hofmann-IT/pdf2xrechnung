package de.hofmannit.erechnung.configuration;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wirksame Erreichbarkeit der Oberfläche (ADR 0015, Phase B), einmal beim Start ermittelt.
 *
 * <p>Grundsatz: Im Zweifel bleibt der Dienst nur lokal erreichbar. Ist der Netzzugriff
 * angefordert, das Zertifikat aber nicht nutzbar, startet der Dienst im Modus „nur dieser
 * Computer" und nennt den Grund in Protokoll, Systemstatus und Verwaltung – er weicht niemals
 * auf unverschlüsselten Netzzugriff aus.
 */
public class NetworkAccess {

    private static final Logger log = LoggerFactory.getLogger(NetworkAccess.class);

    /** Erreichbarkeit; Schreibweise in der Konfiguration in Kleinbuchstaben. */
    public enum Mode {
        LOCAL("Nur dieser Computer"),
        NETWORK("Dieser Computer und Firmennetz (HTTPS)"),
        OPEN("Alle Schnittstellen, unverschlüsselt (Container / Reverse Proxy)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public String configValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Mode> parse(String value) {
            if (value == null) {
                return Optional.empty();
            }
            for (Mode m : values()) {
                if (m.name().equalsIgnoreCase(value.trim())) {
                    return Optional.of(m);
                }
            }
            return Optional.empty();
        }
    }

    /** Angaben zum Serverzertifikat; {@code sha256} ist der Fingerabdruck, den Arbeitsplätze bestätigen. */
    public record Certificate(String alias, String subject, String sha256, String thumbprint, Instant notBefore, Instant notAfter,
                              List<String> names) {

        public boolean validAt(Instant now) {
            return !now.isBefore(notBefore) && !now.isAfter(notAfter);
        }

        /** Fingerabdruck in Zweiergruppen, wie ihn Windows und die Verwaltungsanwendung anzeigen. */
        public String sha256Grouped() {
            return grouped(sha256);
        }

        public String thumbprintGrouped() {
            return grouped(thumbprint);
        }

        private static String grouped(String hex) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hex.length(); i += 2) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(hex, i, Math.min(i + 2, hex.length()));
            }
            return sb.toString().toUpperCase(Locale.ROOT);
        }
    }

    /** Schlüsselmaterial für den HTTPS-Connector; verlässt das Modul nicht als Text. */
    public record Tls(KeyStore keyStore, String alias, char[] keyPassword) {
    }

    /** Ergebnis der Zertifikatssuche: gefunden oder Grund, warum nicht. */
    public record Lookup(Certificate certificate, Tls tls, String problem) {
        public boolean found() {
            return certificate != null;
        }
    }

    private final Mode requested;
    private final Mode effective;
    private final int httpPort;
    private final int httpsPort;
    private final String problem;
    private final Certificate certificate;
    private final Tls tls;
    private final boolean certificateValid;

    public NetworkAccess(AppProperties.Network properties, int httpPort, CertificateSource source, Clock clock) {
        this.httpPort = httpPort;
        this.httpsPort = properties.port();
        Optional<Mode> parsed = Mode.parse(properties.mode());
        this.requested = parsed.orElse(Mode.LOCAL);
        String issue = parsed.isPresent() ? null
                : "Unbekannter Modus '" + properties.mode() + "' (erlaubt: local, network, open)";
        Certificate cert = null;
        Tls material = null;
        if (issue == null && requested == Mode.NETWORK) {
            if (httpsPort < 1 || httpsPort > 65535) {
                issue = "HTTPS-Port " + httpsPort + " ist ungültig (1 bis 65535)";
            } else if (httpPort != 0 && httpsPort == httpPort) {
                issue = "HTTPS-Port " + httpsPort + " ist bereits der lokale Port (server.port)";
            } else {
                Lookup lookup = lookup(properties, source);
                cert = lookup.certificate();
                material = lookup.tls();
                issue = lookup.problem();
            }
        }
        this.problem = issue;
        this.certificate = cert;
        this.tls = issue == null ? material : null;
        this.effective = issue == null ? requested : Mode.LOCAL;
        this.certificateValid = cert == null || cert.validAt(clock.instant());
        if (issue != null) {
            log.error("Netzzugriff: angefordert '{}', wirksam 'nur dieser Computer' – {}", properties.mode(), issue);
        } else if (effective == Mode.NETWORK) {
            log.info("Netzzugriff: HTTPS auf Port {} (Zertifikat '{}', SHA-256 {}, gültig bis {}); lokal weiter HTTP auf 127.0.0.1:{}",
                    httpsPort, cert.subject(), cert.sha256Grouped(), cert.notAfter(), httpPort);
            if (!certificateValid) {
                log.warn("Netzzugriff: Das Zertifikat ist zeitlich nicht gültig ({} bis {}); Arbeitsplätze werden warnen",
                        cert.notBefore(), cert.notAfter());
            }
        } else if (effective == Mode.OPEN) {
            log.warn("Netzzugriff: HTTP auf allen Schnittstellen, unverschlüsselt (Modus 'open') – nur für Container oder hinter einem Reverse Proxy mit TLS");
        } else {
            log.info("Netzzugriff: nur dieser Computer (HTTP auf 127.0.0.1:{})", httpPort);
        }
    }

    /**
     * Sucht das Zertifikat im Speicher: bei gesetztem Fingerabdruck genau dieses, sonst alle
     * Einträge mit dem Anzeigenamen (Windows hängt bei Dubletten „ (1)" an) und davon das am
     * längsten gültige. Nur Einträge mit privatem Schlüssel zählen.
     */
    public static Lookup lookup(AppProperties.Network properties, CertificateSource source) {
        CertificateSource.Loaded loaded;
        try {
            loaded = source.load();
        } catch (Exception | LinkageError e) {
            return new Lookup(null, null, "Zertifikatsspeicher '" + properties.certificateStore() + "' ist nicht verfügbar ("
                    + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage())
                    + "). Der Netzzugriff mit HTTPS setzt Windows voraus; unter Linux/Docker einen Reverse Proxy mit TLS verwenden");
        }
        String wantedThumbprint = properties.certificateThumbprint() == null ? ""
                : properties.certificateThumbprint().replaceAll("[^0-9A-Fa-f]", "").toLowerCase(Locale.ROOT);
        String wantedAlias = properties.certificateAlias() == null ? "" : properties.certificateAlias().trim();
        List<Certificate> candidates = new ArrayList<>();
        boolean withoutKey = false;
        try {
            KeyStore store = loaded.keyStore();
            for (String alias : Collections.list(store.aliases())) {
                if (!(store.getCertificate(alias) instanceof X509Certificate x509)) {
                    continue;
                }
                Certificate described = describe(alias, x509);
                boolean match = wantedThumbprint.isEmpty() ? aliasMatches(alias, wantedAlias) : described.thumbprint().equals(wantedThumbprint);
                if (!match) {
                    continue;
                }
                if (store.isKeyEntry(alias)) {
                    candidates.add(described);
                } else {
                    withoutKey = true;
                }
            }
        } catch (Exception e) {
            return new Lookup(null, null, "Zertifikatsspeicher konnte nicht gelesen werden: " + e.getMessage());
        }
        String wanted = wantedThumbprint.isEmpty() ? "mit dem Anzeigenamen '" + wantedAlias + "'" : "mit dem Fingerabdruck " + wantedThumbprint;
        if (candidates.isEmpty()) {
            return new Lookup(null, null, withoutKey
                    ? "Das Zertifikat " + wanted + " hat keinen privaten Schlüssel im Speicher '" + properties.certificateStore() + "'"
                    : "Kein Zertifikat " + wanted + " im Speicher '" + properties.certificateStore()
                            + "' gefunden (einmalig als Administrator deploy\\windows\\netzbetrieb-einrichten.ps1 ausführen)");
        }
        Certificate best = candidates.stream().max(Comparator.comparing(Certificate::notAfter)).orElseThrow();
        try {
            if (loaded.keyStore().getKey(best.alias(), loaded.keyPassword()) == null) {
                return new Lookup(null, null, "Der private Schlüssel des Zertifikats " + wanted + " ist für den Dienst nicht lesbar");
            }
        } catch (Exception e) {
            return new Lookup(null, null, "Der private Schlüssel des Zertifikats " + wanted + " ist für den Dienst nicht lesbar: " + e.getMessage());
        }
        return new Lookup(best, new Tls(loaded.keyStore(), best.alias(), loaded.keyPassword()), null);
    }

    private static boolean aliasMatches(String alias, String wanted) {
        if (wanted.isEmpty() || alias == null) {
            return false;
        }
        String a = alias.toLowerCase(Locale.ROOT);
        String w = wanted.toLowerCase(Locale.ROOT);
        return a.equals(w) || a.matches(java.util.regex.Pattern.quote(w) + " \\(\\d+\\)");
    }

    private static Certificate describe(String alias, X509Certificate x509) throws Exception {
        byte[] encoded = x509.getEncoded();
        List<String> names = new ArrayList<>();
        if (x509.getSubjectAlternativeNames() != null) {
            for (List<?> san : x509.getSubjectAlternativeNames()) {
                // 2 = DNS-Name, 7 = IP-Adresse (RFC 5280)
                if (san.size() > 1 && (Integer.valueOf(2).equals(san.get(0)) || Integer.valueOf(7).equals(san.get(0)))) {
                    names.add(String.valueOf(san.get(1)));
                }
            }
        }
        return new Certificate(alias, x509.getSubjectX500Principal().getName(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded)),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(encoded)),
                x509.getNotBefore().toInstant(), x509.getNotAfter().toInstant(), List.copyOf(names));
    }

    /** Standardquelle: Zertifikatsspeicher laut Konfiguration (unter Windows {@code Windows-MY-LOCALMACHINE}). */
    public static CertificateSource storeSource(String type) {
        return () -> {
            KeyStore store;
            try {
                store = KeyStore.getInstance(type);
            } catch (KeyStoreException e) {
                throw new KeyStoreException("Speichertyp '" + type + "' gibt es auf diesem System nicht");
            }
            store.load(null, null);
            return new CertificateSource.Loaded(store, null);
        };
    }

    /** In der Konfiguration angeforderter Modus (bei unbekanntem Wert: lokal). */
    public Mode requested() {
        return requested;
    }

    /** Tatsächlich wirksamer Modus dieses Dienstlaufs. */
    public Mode effective() {
        return effective;
    }

    public int httpPort() {
        return httpPort;
    }

    public int httpsPort() {
        return httpsPort;
    }

    /** Grund, warum der angeforderte Modus nicht wirksam ist; sonst {@code null}. */
    public String problem() {
        return problem;
    }

    public Certificate certificate() {
        return certificate;
    }

    public boolean certificateValid() {
        return certificateValid;
    }

    /** Schlüsselmaterial, nur im wirksamen Modus {@link Mode#NETWORK}. */
    public Optional<Tls> tls() {
        return Optional.ofNullable(tls);
    }
}
