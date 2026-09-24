package de.hofmannit.erechnung.configuration.profile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.TenantProperties;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Lädt beim Start alle Profile aus dem Profilverzeichnis, validiert die Mandantenkonfiguration
 * und stellt beides der Anwendung unveränderlich zur Verfügung.
 *
 * <p>Fehlerhafte Konfiguration verhindert den Start (fail fast), damit nie mit unvollständigen
 * oder widersprüchlichen Profilen verarbeitet wird.
 */
@Component
public class ProfileRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProfileRegistry.class);
    private static final Pattern TENANT_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Map<String, LoadedProfile> profiles;
    private final List<Tenant> tenants;

    public ProfileRegistry(AppProperties appProperties, TenantProperties tenantProperties) throws IOException {
        this.profiles = Map.copyOf(new ProfileLoader().loadAll(appProperties.directories().profiles()));
        this.tenants = List.copyOf(validateTenants(tenantProperties, profiles));
        log.info("Konfiguration geladen: {} Profil(e) {}, {} Mandant(en) {}",
                profiles.size(), profiles.keySet(), tenants.size(), tenants.stream().map(Tenant::id).toList());
    }

    static List<Tenant> validateTenants(TenantProperties tenantProperties, Map<String, LoadedProfile> profiles) {
        List<String> errors = new ArrayList<>();
        List<Tenant> tenants = tenantProperties == null || tenantProperties.tenants() == null
                ? List.of() : tenantProperties.tenants();
        if (tenants.isEmpty()) {
            errors.add("config/tenant.yaml: mindestens ein Mandant (tenants[]) ist erforderlich");
        }
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
        return profiles;
    }

    public Optional<LoadedProfile> profile(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    public List<Tenant> tenants() {
        return tenants;
    }

    public Optional<Tenant> tenant(String id) {
        return tenants.stream().filter(t -> t.id().equals(id)).findFirst();
    }
}
