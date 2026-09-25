package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.util.List;

import de.hofmannit.erechnung.admin.AdminCredentialService;
import de.hofmannit.erechnung.admin.AdminSettingsService;
import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.admin.SetupService;
import de.hofmannit.erechnung.admin.SetupService.TenantInput;
import de.hofmannit.erechnung.configuration.ApplicationVersion;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.web.AdminController.Form;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Einrichtungs-Assistent beim ersten Aufruf (ADR 0013). Nach Abschluss nur noch als Hinweis erreichbar. */
@Controller
public class SetupController {

    private final SetupService setup;
    private final AdminSettingsService settings;
    private final AdminCredentialService credentials;
    private final ProfileRegistry registry;
    private final ApplicationVersion version;

    public SetupController(SetupService setup, AdminSettingsService settings, AdminCredentialService credentials, ProfileRegistry registry,
                           ApplicationVersion version) {
        this.setup = setup;
        this.settings = settings;
        this.credentials = credentials;
        this.registry = registry;
        this.version = version;
    }

    @GetMapping("/einrichtung")
    public String page(Model model) {
        if (!setup.needed()) {
            model.addAttribute("done", true);
            model.addAttribute("active", "verwaltung");
            return "einrichtung";
        }
        Tenant existing = registry.tenants().stream().findFirst().orElse(null);
        TenantInput tenant = existing == null
                ? new TenantInput("", "", "", "", "", "", "DE", "", "", "", "", "", "", "", "", "EUR")
                : fromTenant(existing);
        fill(model, tenant, Form.of(settings.current()), "admin");
        return "einrichtung";
    }

    @PostMapping("/einrichtung")
    public String complete(@RequestParam(defaultValue = "") String id, @RequestParam(defaultValue = "") String name,
                           @RequestParam(defaultValue = "") String sellerName, @RequestParam(defaultValue = "") String street,
                           @RequestParam(defaultValue = "") String postCode, @RequestParam(defaultValue = "") String city,
                           @RequestParam(defaultValue = "DE") String countryCode, @RequestParam(defaultValue = "") String vatId,
                           @RequestParam(defaultValue = "") String taxNumber, @RequestParam(defaultValue = "") String email,
                           @RequestParam(defaultValue = "") String contactName, @RequestParam(defaultValue = "") String phone,
                           @RequestParam(defaultValue = "") String iban, @RequestParam(defaultValue = "") String bic,
                           @RequestParam(defaultValue = "") String accountName, @RequestParam(defaultValue = "EUR") String currency,
                           @RequestParam(defaultValue = "") String inbox, @RequestParam(defaultValue = "") String processing,
                           @RequestParam(defaultValue = "") String output, @RequestParam(defaultValue = "") String failed,
                           @RequestParam(defaultValue = "") String manualReview, @RequestParam(defaultValue = "") String rejected,
                           @RequestParam(defaultValue = "") String archive, @RequestParam(defaultValue = "") String inboundValidation,
                           @RequestParam(required = false) String watcherEnabled, @RequestParam(defaultValue = "5") String pollIntervalSeconds,
                           @RequestParam(defaultValue = "2") String stableChecks, @RequestParam(defaultValue = "1") String tenantParallelism,
                           @RequestParam(required = false) String storeReports, @RequestParam(required = false) String smtpEnabled,
                           @RequestParam(defaultValue = "") String smtpHost, @RequestParam(defaultValue = "587") String smtpPort,
                           @RequestParam(required = false) String smtpStarttls, @RequestParam(required = false) String smtpSsl,
                           @RequestParam(required = false) String smtpAuth, @RequestParam(defaultValue = "") String smtpFrom,
                           @RequestParam(defaultValue = "30") String smtpTimeoutSeconds,
                           @RequestParam(defaultValue = "admin") String adminUser, @RequestParam(defaultValue = "") String adminPassword,
                           @RequestParam(defaultValue = "") String adminPasswordRepeat, @RequestParam(defaultValue = "") String actor,
                           Model model, RedirectAttributes redirect) throws IOException {
        if (!setup.needed()) {
            return "redirect:/verwaltung";
        }
        String tenantId = id.isBlank() ? SetupService.suggestId(name) : id.trim();
        TenantInput tenant = new TenantInput(tenantId, name, sellerName.isBlank() ? name : sellerName, street, postCode, city, countryCode,
                vatId, taxNumber, email, contactName, phone, iban, bic, accountName.isBlank() ? (sellerName.isBlank() ? name : sellerName) : accountName,
                currency);
        Form form = new Form(inbox, processing, output, failed, manualReview, rejected, archive, inboundValidation, on(watcherEnabled),
                pollIntervalSeconds, stableChecks, tenantParallelism, on(storeReports), on(smtpEnabled), smtpHost, smtpPort, on(smtpStarttls),
                on(smtpSsl), on(smtpAuth), smtpFrom, smtpTimeoutSeconds);
        try {
            setup.complete(tenant, form.toConfig(), adminUser, adminPassword, adminPasswordRepeat, actor.isBlank() ? contactName : actor);
            redirect.addFlashAttribute("notice", "Einrichtung abgeschlossen. Melden Sie sich in der Verwaltung mit Benutzer '"
                    + credentials.username() + "' und dem vergebenen Passwort an.");
            return "redirect:/";
        } catch (SettingsException e) {
            fill(model, tenant, form, adminUser);
            model.addAttribute("errors", e.errors());
            model.addAttribute("actor", actor);
            return "einrichtung";
        }
    }

    private void fill(Model model, TenantInput tenant, Form form, String adminUser) {
        model.addAttribute("tenant", tenant);
        model.addAttribute("form", form);
        model.addAttribute("adminUser", adminUser);
        model.addAttribute("tenantMissing", setup.tenantMissing());
        model.addAttribute("passwordMissing", setup.passwordMissing());
        model.addAttribute("envPassword", credentials.envConfigured());
        model.addAttribute("tenantFile", registry.tenantFile().toAbsolutePath().normalize());
        model.addAttribute("version", version.value());
        model.addAttribute("active", "verwaltung");
    }

    private static TenantInput fromTenant(Tenant t) {
        var f = t.fixedValues();
        return new TenantInput(t.id(), t.name(), f.getOrDefault("seller-name", t.name()), f.getOrDefault("seller-street", ""),
                f.getOrDefault("seller-post-code", ""), f.getOrDefault("seller-city", ""), f.getOrDefault("seller-country-code", "DE"),
                f.getOrDefault("seller-vat-id", ""), f.getOrDefault("seller-tax-number", ""), f.getOrDefault("seller-email", ""),
                f.getOrDefault("seller-contact-name", ""), f.getOrDefault("seller-phone", ""), f.getOrDefault("payment-account-iban", ""),
                f.getOrDefault("payment-bic", ""), f.getOrDefault("payment-account-name", ""), f.getOrDefault("invoice-currency", "EUR"));
    }

    private static boolean on(String v) {
        return v != null && ("on".equals(v) || "true".equals(v));
    }

    static List<String> none() {
        return List.of();
    }
}
