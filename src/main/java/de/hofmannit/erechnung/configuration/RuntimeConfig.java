package de.hofmannit.erechnung.configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Zur Laufzeit änderbare Einstellungen (ADR 0012). Startwert kommt aus {@link AppProperties}
 * (YAML); in der Verwaltung gespeicherte Werte gehen vor und werden ohne Neustart wirksam.
 *
 * <p>Nicht enthalten sind Einstellungen, die nur mit Neustart wirken: Datenverzeichnis
 * (offene Datenbank), Profil- und Validator-Verzeichnisse (beim Start geladen),
 * Logverzeichnis, Port, Upload-Grenze sowie alle Zugangsdaten (nur Umgebungsvariablen).
 * Alle Pfade als Text, damit der Datensatz als JSON in {@code app_settings} liegt.
 */
public record RuntimeConfig(Directories directories, Watcher watcher, Processing processing,
                            InboundValidation inboundValidation, Smtp smtp) {

    public record Directories(String inbox, String processing, String output, String failed, String manualReview,
                              String rejected, String archive, String inboundValidation) {
        public Map<String, String> asMap() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("inbox", inbox);
            m.put("processing", processing);
            m.put("output", output);
            m.put("failed", failed);
            m.put("manual-review", manualReview);
            m.put("rejected", rejected);
            m.put("archive", archive);
            m.put("inbound-validation", inboundValidation);
            return m;
        }
    }

    public record Watcher(boolean enabled, Duration pollInterval, int stableChecks) {
    }

    public record Processing(int tenantParallelism) {
    }

    public record InboundValidation(boolean storeReports) {
    }

    /** SMTP ohne Zugangsdaten; Benutzer und Passwort kommen ausschließlich aus SMTP_USERNAME/SMTP_PASSWORD. */
    public record Smtp(boolean enabled, String host, int port, boolean starttls, boolean ssl, boolean auth, String from, Duration timeout) {
    }

    /** Startwerte aus der YAML-Konfiguration. */
    public static RuntimeConfig fromProperties(AppProperties p) {
        AppProperties.Directories d = p.directories();
        return new RuntimeConfig(
                new Directories(s(d.inbox()), s(d.processing()), s(d.output()), s(d.failed()), s(d.manualReview()), s(d.rejected()),
                        s(d.archive()), s(d.inboundValidation())),
                new Watcher(p.watcher().enabled(), p.watcher().pollInterval(), p.watcher().stableChecks()),
                new Processing(p.processing().tenantParallelism()),
                new InboundValidation(p.inboundValidation().storeReports()),
                new Smtp(p.smtp().enabled(), p.smtp().host(), p.smtp().port(), p.smtp().starttls(), p.smtp().ssl(), p.smtp().auth(),
                        p.smtp().from(), p.smtp().timeout()));
    }

    /** Fachliche Prüfung; leere Liste = gültig. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        Map<String, String> dirs = directories == null ? Map.of() : directories.asMap();
        Map<String, String> normalized = new LinkedHashMap<>();
        dirs.forEach((name, value) -> {
            if (value == null || value.isBlank()) {
                errors.add("Verzeichnis '" + name + "' darf nicht leer sein");
                return;
            }
            try {
                String key = Path.of(value.trim()).toAbsolutePath().normalize().toString();
                String other = normalized.put(key, name);
                if (other != null) {
                    errors.add("Verzeichnisse '" + other + "' und '" + name + "' zeigen auf denselben Pfad");
                }
            } catch (RuntimeException e) {
                errors.add("Verzeichnis '" + name + "': ungültiger Pfad '" + value + "'");
            }
        });
        if (watcher == null) {
            errors.add("Watcher-Einstellungen fehlen");
        } else {
            if (watcher.pollInterval() == null || watcher.pollInterval().toMillis() < 250 || watcher.pollInterval().toHours() > 1) {
                errors.add("Prüfintervall muss zwischen 250 ms und 1 h liegen");
            }
            if (watcher.stableChecks() < 1 || watcher.stableChecks() > 20) {
                errors.add("Stabile Prüfungen: 1 bis 20");
            }
        }
        if (processing == null || processing.tenantParallelism() < 1 || processing.tenantParallelism() > 32) {
            errors.add("Parallelität: 1 bis 32 Mandanten");
        }
        if (smtp == null) {
            errors.add("SMTP-Einstellungen fehlen");
        } else {
            if (smtp.port() < 1 || smtp.port() > 65535) {
                errors.add("SMTP-Port: 1 bis 65535");
            }
            if (smtp.timeout() == null || smtp.timeout().toSeconds() < 1 || smtp.timeout().toMinutes() > 10) {
                errors.add("SMTP-Timeout: 1 s bis 10 min");
            }
            if (smtp.enabled()) {
                if (smtp.host() == null || smtp.host().isBlank()) {
                    errors.add("SMTP-Host ist bei aktiviertem Versand erforderlich");
                }
                if (smtp.from() == null || !smtp.from().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                    errors.add("Absenderadresse ist bei aktiviertem Versand erforderlich (z. B. rechnung@firma.de)");
                }
                if (smtp.starttls() && smtp.ssl()) {
                    errors.add("STARTTLS und SSL schließen sich aus");
                }
            }
        }
        return errors;
    }

    private static String s(Path p) {
        return p == null ? "" : p.toString();
    }
}
