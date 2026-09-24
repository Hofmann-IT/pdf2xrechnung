package de.hofmannit.erechnung.configuration;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Technische Anwendungskonfiguration aus {@code config/application.yaml} (Präfix {@code app}).
 *
 * <p>Alle Pfade, Intervalle, Parallelität und SMTP-Parameter sind hier gebündelt.
 * Secrets (SMTP-Passwort) werden nicht in YAML abgelegt, sondern ausschließlich über
 * Umgebungsvariablen referenziert (siehe {@code config/application.yaml}).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Directories directories,
        Watcher watcher,
        Processing processing,
        Validation validation,
        InboundValidation inboundValidation,
        Smtp smtp,
        Logging logging) {

    /** Logging-Ablage (Rotation siehe {@code logback-spring.xml}). */
    public record Logging(@DefaultValue("./logs") Path directory) {
    }

    /** Alle Arbeitsverzeichnisse der Anwendung. */
    public record Directories(
            @DefaultValue("./inbox") Path inbox,
            @DefaultValue("./processing") Path processing,
            @DefaultValue("./output") Path output,
            @DefaultValue("./failed") Path failed,
            @DefaultValue("./manual-review") Path manualReview,
            @DefaultValue("./rejected") Path rejected,
            @DefaultValue("./archive") Path archive,
            @DefaultValue("./data") Path data,
            @DefaultValue("./inbound-validation") Path inboundValidation,
            @DefaultValue("./profiles") Path profiles,
            @DefaultValue("./validator") Path validatorResources) {
    }

    /** Parameter der Inbox-Überwachung. */
    public record Watcher(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("5s") Duration pollInterval,
            /** Anzahl aufeinanderfolgender Intervalle mit stabiler Dateigröße vor der Übernahme. */
            @DefaultValue("2") int stableChecks) {
    }

    /** Parallelität der Verarbeitung. */
    public record Processing(
            /** Anzahl gleichzeitig verarbeitender Mandanten; innerhalb eines Mandanten immer sequentiell. */
            @DefaultValue("1") int tenantParallelism) {
    }

    /** Lokale Validierungsressourcen. */
    public record Validation(
            /** Pfad zur lokal abgelegten KoSIT-Szenariokonfiguration für XRechnung (scenarios.xml). */
            @DefaultValue("./validator/xrechnung/scenarios.xml") Path kositScenarios,
            /** Basisverzeichnis der KoSIT-Konfiguration (Schematron, XSD, Reports). */
            @DefaultValue("./validator/xrechnung") Path kositRepository) {
    }

    /** Eingangs-Validierung ("E-Rechnung prüfen"). */
    public record InboundValidation(
            /** Prüfprotokoll persistent ablegen (Standard: nur temporär). */
            @DefaultValue("false") boolean storeReports,
            /** Maximale Uploadgröße. */
            @DefaultValue("50MB") org.springframework.util.unit.DataSize maxUploadSize) {
    }

    /** SMTP-Konfiguration (einzige erlaubte ausgehende Netzwerkverbindung). */
    public record Smtp(
            @DefaultValue("false") boolean enabled,
            String host,
            @DefaultValue("587") int port,
            @DefaultValue("true") boolean starttls,
            @DefaultValue("false") boolean ssl,
            @DefaultValue("true") boolean auth,
            /** Wird aus der Umgebungsvariable SMTP_USERNAME befüllt. */
            String username,
            /** Wird aus der Umgebungsvariable SMTP_PASSWORD befüllt; niemals in YAML eintragen. */
            String password,
            String from,
            @DefaultValue("30s") Duration timeout) {
    }
}
