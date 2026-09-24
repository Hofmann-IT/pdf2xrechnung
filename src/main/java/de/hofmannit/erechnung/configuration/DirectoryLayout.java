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
 */
@Component
public class DirectoryLayout {

    private final AppProperties.Directories dirs;
    private final ProfileRegistry registry;

    public DirectoryLayout(AppProperties appProperties, ProfileRegistry registry) {
        this.dirs = appProperties.directories();
        this.registry = registry;
    }

    public Path inbox(Tenant t) {
        return sub(dirs.inbox(), t);
    }

    public Path processing(Tenant t) {
        return sub(dirs.processing(), t);
    }

    public Path output(Tenant t) {
        return sub(dirs.output(), t);
    }

    public Path failed(Tenant t) {
        return sub(dirs.failed(), t);
    }

    public Path manualReview(Tenant t) {
        return sub(dirs.manualReview(), t);
    }

    public Path rejected(Tenant t) {
        return sub(dirs.rejected(), t);
    }

    public Path archiveRoot() {
        return dirs.archive().toAbsolutePath().normalize();
    }

    public Path processingRoot() {
        return dirs.processing().toAbsolutePath().normalize();
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
