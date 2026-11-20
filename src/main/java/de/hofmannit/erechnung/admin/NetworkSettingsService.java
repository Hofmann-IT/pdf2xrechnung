package de.hofmannit.erechnung.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.CertificateSource;
import de.hofmannit.erechnung.configuration.NetworkAccess;
import de.hofmannit.erechnung.configuration.NetworkAccess.Lookup;
import de.hofmannit.erechnung.configuration.NetworkAccess.Mode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Netzzugriff in der Verwaltung umstellen (ADR 0015, Phase B). Die Bindung des Webservers steht
 * beim Start fest; gespeichert wird deshalb in {@code config/network.yaml} (eine vorhandene
 * Datei wird vorher gesichert), wirksam mit dem nächsten Dienst-Neustart. Der Netzzugriff wird
 * nur gespeichert, wenn das Zertifikat mit privatem Schlüssel im Speicher gefunden wird.
 */
@Service
public class NetworkSettingsService {

    private static final Logger log = LoggerFactory.getLogger(NetworkSettingsService.class);

    /** Gespeicherter, noch nicht wirksamer Stand. */
    public record Pending(Mode mode, int port, String savedBy) {
    }

    private final AppProperties.Network properties;
    private final NetworkAccess access;
    private final CertificateSource source;
    private final Clock clock;
    private volatile Pending pending;

    public NetworkSettingsService(AppProperties properties, NetworkAccess access, CertificateSource source, Clock clock) {
        this.properties = properties.network();
        this.access = access;
        this.source = source;
        this.clock = clock;
    }

    public NetworkAccess access() {
        return access;
    }

    public Path file() {
        return properties.file().toAbsolutePath().normalize();
    }

    /** In diesem Dienstlauf gespeicherte Änderung, die erst nach dem Neustart gilt. */
    public Optional<Pending> pending() {
        return Optional.ofNullable(pending);
    }

    /** Sucht das Zertifikat jetzt im Speicher (für die Anzeige, solange der Netzzugriff noch aus ist). */
    public Lookup lookupCertificate() {
        return NetworkAccess.lookup(properties, source);
    }

    /** Speichert Modus und Port; in der Oberfläche wählbar sind nur „lokal" und „Firmennetz". */
    public synchronized Pending save(String modeValue, String portValue, String user) throws SettingsException {
        List<String> errors = new ArrayList<>();
        if (user == null || user.isBlank()) {
            errors.add("Benutzername ist erforderlich");
        }
        Mode mode = Mode.parse(modeValue).filter(m -> m != Mode.OPEN).orElse(null);
        if (mode == null) {
            errors.add("Modus ungültig (wählbar: nur dieser Computer oder Firmennetz)");
        }
        int port = properties.port();
        try {
            port = Integer.parseInt(portValue == null ? "" : portValue.trim());
        } catch (NumberFormatException e) {
            errors.add("HTTPS-Port muss eine Zahl sein");
        }
        if (errors.isEmpty() && (port < 1024 || port > 65535)) {
            errors.add("HTTPS-Port: 1024 bis 65535");
        }
        if (errors.isEmpty() && port == access.httpPort()) {
            errors.add("HTTPS-Port " + port + " ist bereits der lokale Port der Oberfläche");
        }
        if (errors.isEmpty() && mode == Mode.NETWORK) {
            Lookup lookup = lookupCertificate();
            if (!lookup.found()) {
                errors.add("Netzzugriff nicht gespeichert: " + lookup.problem());
            }
        }
        if (!errors.isEmpty()) {
            throw new SettingsException(errors);
        }
        try {
            write(mode, port, user.trim());
        } catch (IOException e) {
            throw new SettingsException(List.of("Datei " + file() + " konnte nicht geschrieben werden: " + e.getMessage()));
        }
        Pending saved = new Pending(mode, port, user.trim());
        boolean unchanged = access.problem() == null && access.requested() == mode && (mode != Mode.NETWORK || access.httpsPort() == port);
        this.pending = unchanged ? null : saved;
        log.info("Netzzugriff gespeichert von {}: Modus '{}', HTTPS-Port {} – wirksam nach Dienst-Neustart", user.trim(), mode.configValue(), port);
        return saved;
    }

    private void write(Mode mode, int port, String user) throws IOException {
        Path file = file();
        Files.createDirectories(file.getParent());
        LocalDateTime now = LocalDateTime.now(clock);
        if (Files.exists(file)) {
            Path backup = file.resolveSibling(file.getFileName() + ".bak-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(now));
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        String yaml = """
                # =====================================================================================
                # Netzzugriff (config/network.yaml) – geschrieben von Verwaltung → Netzzugriff
                # Gespeichert von %s am %s. Wirkt nach einem Neustart des Dienstes.
                #
                #   local   = nur dieser Computer (HTTP auf 127.0.0.1)
                #   network = zusätzlich HTTPS für das Firmennetz; Zertifikat und privater Schlüssel
                #             liegen im Windows-Zertifikatsspeicher, nicht in einer Datei
                #
                # KEINE SECRETS in dieser Datei.
                # =====================================================================================
                app:
                  network:
                    mode: %s
                    port: %d
                """.formatted(SetupService.q(user), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).format(now), mode.configValue(), port);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }
}
