package de.hofmannit.erechnung.configuration;

import java.nio.file.Path;
import java.util.Optional;

import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;

import org.springframework.stereotype.Component;

/**
 * Löst die Arbeitsverzeichnisse je Mandant auf. Jeder Mandant verwendet unterhalb von
 * inbox/, processing/, output/, failed/, manual-review/ und rejected/ dasselbe Unterverzeichnis
 * ({@code inbox-subdirectory}, leer = Wurzel). Archiv und Daten sind mandantenübergreifend.
 *
 * <p>Die Wurzeln kommen bei jedem Aufruf aus {@link RuntimeSettings}, damit Änderungen aus der
 * Verwaltung ohne Neustart wirken (ADR 0012).
 */
@Component
public class DirectoryLayout {

    private final RuntimeSettings settings;
    private final ProfileRegistry registry;

    public DirectoryLayout(RuntimeSettings settings, ProfileRegistry registry) {
        this.settings = settings;
        this.registry = registry;
    }

    private RuntimeConfig.Directories dirs() {
        return settings.current().directories();
    }

    public Path inbox(Tenant t) {
        return sub(Path.of(dirs().inbox()), t);
    }

    public Path processing(Tenant t) {
        return sub(Path.of(dirs().processing()), t);
    }

    public Path output(Tenant t) {
        return sub(Path.of(dirs().output()), t);
    }

    public Path failed(Tenant t) {
        return sub(Path.of(dirs().failed()), t);
    }

    public Path manualReview(Tenant t) {
        return sub(Path.of(dirs().manualReview()), t);
    }

    public Path rejected(Tenant t) {
        return sub(Path.of(dirs().rejected()), t);
    }

    public Path archiveRoot() {
        return Path.of(dirs().archive()).toAbsolutePath().normalize();
    }

    public Path processingRoot() {
        return Path.of(dirs().processing()).toAbsolutePath().normalize();
    }

    public Path inboundValidationRoot() {
        return Path.of(dirs().inboundValidation()).toAbsolutePath().normalize();
    }

    /** Mandant zu einem Unterverzeichnis (relativ zur jeweiligen Wurzel), {@code ""} = Wurzel. */
    public Optional<Tenant> tenantForSubdirectory(String subdirectory) {
        String wanted = normalize(subdirectory);
        return registry.tenants().stream()
                .filter(Tenant::enabled)
                .filter(t -> normalize(t.inboxSubdirectory()).equals(wanted))
                .findFirst();
    }

    private static Path sub(Path root, Tenant t) {
        String s = normalize(t.inboxSubdirectory());
        Path base = root.toAbsolutePath().normalize();
        return s.isEmpty() ? base : base.resolve(s).normalize();
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replace('\\', '/').replaceAll("^/+|/+$", "");
    }
}
