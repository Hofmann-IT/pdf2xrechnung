package de.hofmannit.erechnung.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.configuration.RuntimeConfig;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileException;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Einrichtungs-Assistent (ADR 0013): beim ersten Aufruf Admin-Passwort, Mandant mit
 * Verkäuferangaben und Laufzeiteinstellungen erfassen. Schreibt die Mandantendatei
 * ({@code config/tenant.yaml}, bestehende Datei wird vorher gesichert, nie überschrieben),
 * lädt Mandanten ohne Neustart neu, speichert die Laufzeiteinstellungen und das Passwort.
 */
@Service
public class SetupService {

    private static final Logger log = LoggerFactory.getLogger(SetupService.class);

    /** Erfasste Mandantendaten; Schlüssel der festen Werte entsprechen profiles/standard.yaml. */
    public record TenantInput(String id, String name, String sellerName, String street, String postCode, String city, String countryCode,
                              String vatId, String taxNumber, String email, String contactName, String phone, String iban, String bic,
                              String accountName, String currency) {
    }

    private final ProfileRegistry registry;
    private final AdminSettingsService settings;
    private final AdminCredentialService credentials;
    private final Clock clock;

    public SetupService(ProfileRegistry registry, AdminSettingsService settings, AdminCredentialService credentials, Clock clock) {
        this.registry = registry;
        this.settings = settings;
        this.credentials = credentials;
        this.clock = clock;
    }

    /** Einrichtung nötig, solange kein aktiver Mandant existiert oder keine Anmeldung möglich ist. */
    public boolean needed() {
        return registry.tenants().stream().noneMatch(Tenant::enabled) || !credentials.configured();
    }

    public boolean tenantMissing() {
        return registry.tenants().stream().noneMatch(Tenant::enabled);
    }

    public boolean passwordMissing() {
        return !credentials.configured();
    }

    /** Technische Kennung aus dem Firmennamen: klein, ohne Umlaute, nur [a-z0-9-]. */
    public static String suggestId(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toLowerCase(Locale.GERMANY).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "mandant" : s;
    }

    /**
     * Führt die Einrichtung aus. Reihenfolge: prüfen, Mandantendatei schreiben, neu laden
     * (bei Fehler wird die vorherige Datei zurückgespielt), Laufzeiteinstellungen speichern,
     * Passwort setzen. Alles oder nichts, soweit ohne Transaktion über Datei und Datenbank möglich.
     */
    public synchronized void complete(TenantInput tenant, RuntimeConfig config, String username, String password, String passwordRepeat,
                                      String actor) throws SettingsException, IOException {
        List<String> errors = new ArrayList<>(validateTenant(tenant));
        errors.addAll(config.validate());
        if (tenantMissing() && !errors.isEmpty()) {
            throw new SettingsException(errors);
        }
        if (!errors.isEmpty()) {
            throw new SettingsException(errors);
        }
        if (passwordMissing() || (password != null && !password.isEmpty())) {
            // Passwortregeln vorab prüfen, ohne zu speichern
            if (password == null || password.length() < AdminCredentialService.MIN_LENGTH) {
                throw new SettingsException(List.of("Passwort muss mindestens " + AdminCredentialService.MIN_LENGTH + " Zeichen haben"));
            }
            if (!password.equals(passwordRepeat)) {
                throw new SettingsException(List.of("Passwort und Wiederholung stimmen nicht überein"));
            }
        }
        String who = actor == null || actor.isBlank() ? "einrichtung" : actor.trim();
        if (tenantMissing()) {
            writeTenantFile(tenant);
        }
        settings.save(config, who, "Einrichtungs-Assistent");
        if (passwordMissing() || (password != null && !password.isEmpty())) {
            credentials.set(username, password, passwordRepeat, who);
        }
        log.info("Einrichtung abgeschlossen durch {}: Mandant {}", who, tenant == null ? "-" : tenant.id());
    }

    static List<String> validateTenant(TenantInput t) {
        List<String> errors = new ArrayList<>();
        if (t == null) {
            return List.of("Mandantendaten fehlen");
        }
        if (t.id() == null || !t.id().matches("[a-z0-9][a-z0-9-]*")) {
            errors.add("Mandanten-Kennung: nur Kleinbuchstaben, Ziffern und Bindestrich, z. B. hofmann-it");
        }
        require(errors, t.name(), "Firmenname");
        require(errors, t.sellerName(), "Verkäufername (BT-27)");
        require(errors, t.street(), "Straße (BT-35)");
        require(errors, t.postCode(), "Postleitzahl (BT-38)");
        require(errors, t.city(), "Ort (BT-37)");
        if (t.countryCode() == null || !t.countryCode().trim().matches("[A-Z]{2}")) {
            errors.add("Ländercode (BT-40): zwei Großbuchstaben, z. B. DE");
        }
        if (isBlank(t.vatId()) && isBlank(t.taxNumber())) {
            errors.add("USt-IdNr. (BT-31) oder Steuernummer (BT-32) ist erforderlich (XRechnung BR-DE-16)");
        }
        if (!isBlank(t.vatId()) && !t.vatId().trim().replace(" ", "").matches("[A-Z]{2}[A-Z0-9]{2,13}")) {
            errors.add("USt-IdNr. (BT-31): Länderkürzel plus Nummer, z. B. DE123456789");
        }
        if (t.email() == null || !t.email().trim().matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            errors.add("E-Mail-Adresse (BT-43) ist erforderlich");
        }
        require(errors, t.contactName(), "Ansprechpartner (BT-41)");
        require(errors, t.phone(), "Telefon (BT-42)");
        String iban = t.iban() == null ? "" : t.iban().replace(" ", "").toUpperCase(Locale.ROOT);
        if (!iban.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}") || !ibanChecksumValid(iban)) {
            errors.add("IBAN (BT-84) ist ungültig (Prüfziffer)");
        }
        require(errors, t.accountName(), "Kontoinhaber (BT-85)");
        if (t.currency() == null || !t.currency().trim().matches("[A-Z]{3}")) {
            errors.add("Währung (BT-5): dreistelliger ISO-Code, z. B. EUR");
        }
        return errors;
    }

    /** IBAN-Prüfziffer nach ISO 7064 Mod 97-10 (XRechnung prüft BR-DE-19). */
    public static boolean ibanChecksumValid(String iban) {
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = (remainder * (value >= 10 ? 100 : 10) + value) % 97;
        }
        return remainder == 1;
    }

    private static void require(List<String> errors, String value, String label) {
        if (isBlank(value)) {
            errors.add(label + " ist erforderlich");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** Schreibt die Mandantendatei aus der Vorlage; eine vorhandene Datei wird vorher als Kopie gesichert. */
    void writeTenantFile(TenantInput t) throws IOException, SettingsException {
        Path file = registry.tenantFile().toAbsolutePath().normalize();
        Files.createDirectories(file.getParent());
        Path backup = null;
        if (Files.exists(file)) {
            backup = file.resolveSibling(file.getFileName() + ".bak-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).format(LocalDateTime.now(clock)));
            Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        String yaml = renderTenantYaml(t);
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, yaml, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        try {
            registry.reload();
        } catch (ProfileException | IOException e) {
            // Vorherigen Stand zurückspielen, damit die Datei nie in einem unbrauchbaren Zustand bleibt
            if (backup != null) {
                Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(file);
            }
            throw new SettingsException(List.of("Mandantendatei konnte nicht übernommen werden: " + e.getMessage()));
        }
        log.info("Mandantendatei geschrieben: {}{}", file, backup == null ? "" : " (Sicherung: " + backup.getFileName() + ")");
    }

    static String renderTenantYaml(TenantInput t) {
        Map<String, String> fixed = new LinkedHashMap<>();
        fixed.put("seller-name", t.sellerName());
        fixed.put("seller-street", t.street());
        fixed.put("seller-post-code", t.postCode());
        fixed.put("seller-city", t.city());
        fixed.put("seller-country-code", t.countryCode().trim());
        if (!isBlank(t.vatId())) {
            fixed.put("seller-vat-id", t.vatId().trim().replace(" ", ""));
        }
        if (!isBlank(t.taxNumber())) {
            fixed.put("seller-tax-number", t.taxNumber());
        }
        fixed.put("seller-email", t.email().trim());
        fixed.put("seller-contact-name", t.contactName());
        fixed.put("seller-phone", t.phone());
        fixed.put("payment-means-type-code", "58");
        fixed.put("payment-account-iban", t.iban().replace(" ", "").toUpperCase(Locale.ROOT));
        if (!isBlank(t.bic())) {
            fixed.put("payment-bic", t.bic().replace(" ", "").toUpperCase(Locale.ROOT));
        }
        fixed.put("payment-account-name", t.accountName());
        fixed.put("invoice-currency", t.currency().trim());

        StringBuilder sb = new StringBuilder();
        sb.append("""
                # =====================================================================================
                # Mandanten (config/tenant.yaml) – erzeugt vom Einrichtungs-Assistenten
                #
                # Jeder Mandant besitzt eine stabile technische ID; tenant_id + SHA-256 der Quell-PDF ist
                # eindeutig (Idempotenz). fixed-values sind Unternehmensangaben vom Briefpapier, die über
                # Mapping-Regeln vom Typ "fixed" (profiles/*.yaml) auf Business Terms abgebildet werden.
                # Änderungen werden in der Verwaltung mit "Mandanten neu laden" ohne Neustart wirksam.
                #
                # KEINE SECRETS in dieser Datei.
                # =====================================================================================
                tenants:
                """);
        sb.append("  - id: ").append(q(t.id())).append('\n');
        sb.append("    name: ").append(q(t.name())).append('\n');
        sb.append("    enabled: true\n");
        sb.append("    inbox-subdirectory: \"\"\n");
        sb.append("    profiles:\n      - standard\n");
        sb.append("    fixed-values:\n");
        fixed.forEach((k, v) -> sb.append("      ").append(k).append(": ").append(q(v)).append('\n'));
        sb.append("""
                    # Export des Rechnungsausgangsbuchs: CSV immer; DATEV-Buchungsstapel und Belegtransfer
                    # in der Oberfläche unter Ausgangsrechnungen → Export → Einstellungen bearbeiten aktivieren.
                    export:
                      datev:
                        enabled: false
                      belegtransfer:
                        enabled: false
                """);
        return sb.toString();
    }

    /** YAML-Zeichenkette in doppelten Anführungszeichen mit Escapes. */
    public static String q(String s) {
        String v = s == null ? "" : s.trim();
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
