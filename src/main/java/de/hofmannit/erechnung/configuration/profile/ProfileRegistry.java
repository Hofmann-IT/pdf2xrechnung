package de.hofmannit.erechnung.configuration.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.TenantProperties;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

/**
 * Lädt beim Start alle Profile aus dem Profilverzeichnis, validiert die Mandantenkonfiguration
 * und stellt beides der Anwendung zur Verfügung. Seit ADR 0013 lässt sich der Stand ohne Neustart
 * aus {@code profiles/} und der Mandantendatei neu laden ({@link #reload()}); ein fehlerhafter
 * neuer Stand wird abgewiesen, der bisherige bleibt wirksam.
 *
 * <p>Fehlerhafte Konfiguration verhindert den Start (fail fast), damit nie mit unvollständigen
 * oder widersprüchlichen Profilen verarbeitet wird. Ohne Mandanten startet die Anwendung im
 * Einrichtungsmodus (Assistent), der Watcher hat dann nichts zu überwachen.
 */
@Component
public class ProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProfileRegistry.class);
    private static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** Unveränderlicher Stand aus Profilen und Mandanten. */
    private record State(Map<String, LoadedProfile> profiles, List<Tenant> tenants) {
    }

    private final Path profilesDirectory;
    private final Path tenantFile;
    private final AtomicReference<State> state = new AtomicReference<>();

    public ProfileRegistry(AppProperties appProperties, TenantProperties tenantProperties) throws IOException {
        this.profilesDirectory = appProperties.directories().profiles();
        this.tenantFile = appProperties.tenantFile();
        Map<String, LoadedProfile> profiles = Map.copyOf(new ProfileLoader().loadAll(profilesDirectory));
        List<Tenant> tenants = List.copyOf(validateTenants(tenantProperties, profiles));
        state.set(new State(profiles, tenants));
        logLoaded(profiles, tenants);
    }

    private static void logLoaded(Map<String, LoadedProfile> profiles, List<Tenant> tenants) {
        log.info("Konfiguration geladen: {} Profil(e) {}, {} Mandant(en) {}",
                profiles.size(), profiles.keySet(), tenants.size(), tenants.stream().map(Tenant::id).toList());
        if (tenants.isEmpty()) {
            log.warn("Kein Mandant konfiguriert: Einrichtung über die Oberfläche (/einrichtung) erforderlich");
        }
    }

    /** Pfad der Mandantendatei (config/tenant.yaml). */
    public Path tenantFile() {
        return tenantFile;
    }

    /**
     * Lädt Profile und Mandanten neu (ADR 0013). Bei Fehlern bleibt der bisherige Stand wirksam und
     * die Ausnahme beschreibt alle Gründe.
     */
    public synchronized void reload() throws IOException {
        Map<String, LoadedProfile> profiles = Map.copyOf(new ProfileLoader().loadAll(profilesDirectory));
        List<Tenant> tenants = List.copyOf(validateTenants(readTenantFile(tenantFile), profiles));
        state.set(new State(profiles, tenants));
        log.info("Konfiguration neu geladen");
        logLoaded(profiles, tenants);
    }

    /** Liest die Mandantendatei mit denselben Bindungsregeln wie Spring Boot beim Start (kebab-case, Defaults). */
    static TenantProperties readTenantFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new TenantProperties(List.of());
        }
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("tenant.yaml", new FileSystemResource(file.toFile()));
        if (sources.isEmpty()) {
            return new TenantProperties(List.of());
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources));
        return binder.bind("", Bindable.of(TenantProperties.class)).orElse(new TenantProperties(List.of()));
    }

    static List<Tenant> validateTenants(TenantProperties tenantProperties, Map<String, LoadedProfile> profiles) {
        List<String> errors = new ArrayList<>();
        List<Tenant> tenants = tenantProperties == null || tenantProperties.tenants() == null
                ? List.of() : tenantProperties.tenants();
        Set<String> ids = new HashSet<>();
        Set<String> inboxes = new HashSet<>();
        for (Tenant t : tenants) {
            if (t.id() == null || !TENANT_ID.matcher(t.id()).matches()) {
                errors.add("Mandanten-ID '" + t.id() + "' ist ungültig (erlaubt: [a-z0-9-])");
                continue;
            }
            if (!ids.add(t.id())) {
                errors.add("Mandanten-ID doppelt: " + t.id());
            }
            if (t.name() == null || t.name().isBlank()) {
                errors.add("Mandant " + t.id() + ": name fehlt");
            }
            if (t.profiles().isEmpty()) {
                errors.add("Mandant " + t.id() + ": mindestens ein Profil ist erforderlich");
            }
            for (String p : t.profiles()) {
                if (!profiles.containsKey(p)) {
                    errors.add("Mandant " + t.id() + ": Profil '" + p + "' existiert nicht (vorhanden: " + profiles.keySet() + ")");
                }
            }
            String sub = t.inboxSubdirectory() == null ? "" : t.inboxSubdirectory().trim();
            if (sub.contains("..") || sub.startsWith("/") || sub.startsWith("\\") || sub.contains(":")) {
                errors.add("Mandant " + t.id() + ": inboxSubdirectory '" + sub + "' muss ein relatives Unterverzeichnis sein");
            }
            if (t.enabled() && !inboxes.add(sub)) {
                errors.add("Mandant " + t.id() + ": inboxSubdirectory '" + sub + "' wird bereits von einem anderen aktiven Mandanten verwendet");
            }
            validateDatev(t, errors);
        }
        if (!errors.isEmpty()) {
            throw new ProfileException("Mandantenkonfiguration ist ungültig:\n - " + String.join("\n - ", errors));
        }
        return tenants;
    }

    /** Prüft die DATEV-Konfiguration gegen die Feldregeln der Kopfzeile (docs/datev-format-referenz.md). */
    public static void validateDatev(Tenant t, List<String> errors) {
        validateDatev(t.id(), t.datev(), errors);
        validateBelegtransfer(t.id(), t.belegtransfer(), errors);
    }

    /** Belegtransfer-Verzeichnis: absolut, lokal (kein UNC-/Netzwerkpfad), Pflicht bei aktivierter Übergabe (ADR 0011). */
    public static void validateBelegtransfer(String tenantId, TenantProperties.Belegtransfer b, List<String> errors) {
        if (b == null || !b.enabled()) {
            return;
        }
        String p = "Mandant " + tenantId + ": export.belegtransfer.";
        String dir = b.directory() == null ? "" : b.directory().trim();
        if (dir.isEmpty()) {
            errors.add(p + "directory ist bei enabled: true erforderlich");
            return;
        }
        if (dir.startsWith("\\\\") || dir.startsWith("//")) {
            errors.add(p + "directory darf kein Netzwerkpfad (UNC) sein; der DATEV-Client hält die Verbindung, nicht diese Anwendung");
            return;
        }
        try {
            if (!java.nio.file.Path.of(dir).isAbsolute()) {
                errors.add(p + "directory muss ein absoluter lokaler Pfad sein");
            }
        } catch (RuntimeException e) {
            errors.add(p + "directory ist kein gültiger Pfad: " + dir);
        }
    }

    /** Wie {@link #validateDatev(Tenant, List)}, für Einstellungen aus der Datenbank (ADR 0010). */
    public static void validateDatev(String tenantId, TenantProperties.Datev d, List<String> errors) {
        if (d == null || !d.enabled()) {
            return;
        }
        String p = "Mandant " + tenantId + ": export.datev.";
        if (d.consultantNumber() == null || !d.consultantNumber().matches("\\d{4,6}|\\d{7}")) {
            errors.add(p + "consultantNumber (Beraternummer) muss 4–7 Ziffern haben");
        }
        if (d.clientNumber() == null || !d.clientNumber().matches("\\d{1,5}")) {
            errors.add(p + "clientNumber (Mandantennummer) muss 1–5 Ziffern haben");
        }
        if (d.fiscalYearStart() == null || !d.fiscalYearStart().matches("(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])")) {
            errors.add(p + "fiscalYearStart muss MM-DD sein");
        }
        if (d.accountLength() < 4 || d.accountLength() > 8) {
            errors.add(p + "accountLength (Sachkontenlänge) muss 4–8 sein");
        }
        if (d.chartOfAccounts() == null || !d.chartOfAccounts().matches("(\\d{2}){0,2}")) {
            errors.add(p + "chartOfAccounts (Sachkontenrahmen) muss zweistellig sein, z. B. 03 oder 04");
        }
        if (d.debtorStrategy() == TenantProperties.DebtorStrategy.COLLECTIVE
                && (d.collectiveDebtorAccount() == null || !d.collectiveDebtorAccount().matches("(?!0{1,9}$)\\d{1,9}"))) {
            errors.add(p + "collectiveDebtorAccount ist bei debtorStrategy COLLECTIVE erforderlich (1–9 Ziffern)");
        }
        if (d.revenueAccounts().isEmpty()) {
            errors.add(p + "revenueAccounts darf nicht leer sein");
        }
        d.revenueAccounts().forEach((key, ra) -> {
            if (ra == null || ra.account() == null || !ra.account().matches("(?!0{1,9}$)\\d{1,9}")) {
                errors.add(p + "revenueAccounts[" + key + "].account muss 1–9 Ziffern haben");
            }
            if (ra != null && ra.buKey() != null && !ra.buKey().isBlank() && !ra.buKey().matches("\\d{4}")) {
                errors.add(p + "revenueAccounts[" + key + "].buKey muss vierstellig sein");
            }
        });
        if (d.origin() == null || !d.origin().matches("\\w{0,2}")) {
            errors.add(p + "origin (Herkunft) darf höchstens 2 Zeichen haben");
        }
        if (d.dictationShortcut() != null && !d.dictationShortcut().matches("([A-Z]{2}){0,2}")) {
            errors.add(p + "dictationShortcut (Diktatkürzel) muss aus 2 oder 4 Großbuchstaben bestehen");
        }
        if (d.exportedBy() != null && !d.exportedBy().matches("\\w{0,25}")) {
            errors.add(p + "exportedBy darf höchstens 25 Wortzeichen haben");
        }
    }

    public Map<String, LoadedProfile> profiles() {
        return state.get().profiles();
    }

    public Optional<LoadedProfile> profile(String name) {
        return Optional.ofNullable(state.get().profiles().get(name));
    }

    public List<Tenant> tenants() {
        return state.get().tenants();
    }

    public Optional<Tenant> tenant(String id) {
        return state.get().tenants().stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
