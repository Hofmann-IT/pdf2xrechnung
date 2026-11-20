package de.hofmannit.erechnung.web;

import java.security.Principal;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import de.hofmannit.erechnung.admin.UserAccountService;
import de.hofmannit.erechnung.admin.UserAccountService.Role;
import de.hofmannit.erechnung.admin.AdminSettingsService;
import de.hofmannit.erechnung.admin.NetworkSettingsService;
import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.NetworkAccess;
import de.hofmannit.erechnung.configuration.RuntimeConfig;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.dispatch.EmailDispatcher;
import de.hofmannit.erechnung.watcher.InboxWatcher;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Verwaltungsbereich (ADR 0012): Laufzeiteinstellungen anzeigen, ändern und historisieren.
 * Zugriff nur für Administratoren ({@link SecurityConfig}).
 */
@Controller
public class AdminController {

    /** Formularwerte als Text, damit fehlerhafte Eingaben unverändert zurückkommen. */
    public record Form(String inbox, String processing, String output, String failed, String manualReview, String rejected,
                       String archive, String inboundValidation, boolean watcherEnabled, String pollIntervalSeconds, String stableChecks,
                       String tenantParallelism, boolean storeReports, boolean smtpEnabled, String smtpHost, String smtpPort,
                       boolean smtpStarttls, boolean smtpSsl, boolean smtpAuth, String smtpFrom, String smtpTimeoutSeconds) {

        static Form of(RuntimeConfig c) {
            RuntimeConfig.Directories d = c.directories();
            return new Form(d.inbox(), d.processing(), d.output(), d.failed(), d.manualReview(), d.rejected(), d.archive(),
                    d.inboundValidation(), c.watcher().enabled(), seconds(c.watcher().pollInterval()),
                    String.valueOf(c.watcher().stableChecks()), String.valueOf(c.processing().tenantParallelism()),
                    c.inboundValidation().storeReports(), c.smtp().enabled(), nz(c.smtp().host()), String.valueOf(c.smtp().port()),
                    c.smtp().starttls(), c.smtp().ssl(), c.smtp().auth(), nz(c.smtp().from()), seconds(c.smtp().timeout()));
        }

        RuntimeConfig toConfig() throws SettingsException {
            try {
                return new RuntimeConfig(
                        new RuntimeConfig.Directories(inbox, processing, output, failed, manualReview, rejected, archive, inboundValidation),
                        new RuntimeConfig.Watcher(watcherEnabled, Duration.ofMillis(Math.round(Double.parseDouble(pollIntervalSeconds.trim().replace(',', '.')) * 1000)),
                                Integer.parseInt(stableChecks.trim())),
                        new RuntimeConfig.Processing(Integer.parseInt(tenantParallelism.trim())),
                        new RuntimeConfig.InboundValidation(storeReports),
                        new RuntimeConfig.Smtp(smtpEnabled, smtpHost.trim(), Integer.parseInt(smtpPort.trim()), smtpStarttls, smtpSsl, smtpAuth,
                                smtpFrom.trim(), Duration.ofSeconds(Long.parseLong(smtpTimeoutSeconds.trim()))));
            } catch (NumberFormatException e) {
                throw new SettingsException(List.of("Zahlenfeld ungültig: " + e.getMessage()));
            }
        }

        private static String seconds(Duration d) {
            if (d == null) {
                return "";
            }
            double s = d.toMillis() / 1000.0;
            return s == Math.rint(s) ? String.valueOf((long) s) : String.valueOf(s);
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }

    private final AdminSettingsService service;
    private final AppProperties properties;
    private final EmailDispatcher email;
    private final InboxWatcher watcher;
    private final ProfileRegistry registry;
    private final UserAccountService accounts;
    private final NetworkSettingsService network;

    public AdminController(AdminSettingsService service, AppProperties properties, EmailDispatcher email, InboxWatcher watcher,
                           ProfileRegistry registry, UserAccountService accounts, NetworkSettingsService network) {
        this.network = network;
        this.accounts = accounts;
        this.service = service;
        this.properties = properties;
        this.email = email;
        this.watcher = watcher;
        this.registry = registry;
    }

    @GetMapping("/verwaltung")
    public String page(Model model) {
        fill(model, Form.of(service.current()));
        return "verwaltung";
    }

    @PostMapping("/verwaltung")
    public String save(Principal principal, @RequestParam(required = false) String note,
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
                       Model model, RedirectAttributes redirect) {
        Form form = new Form(inbox, processing, output, failed, manualReview, rejected, archive, inboundValidation, on(watcherEnabled),
                pollIntervalSeconds, stableChecks, tenantParallelism, on(storeReports), on(smtpEnabled), smtpHost, smtpPort, on(smtpStarttls),
                on(smtpSsl), on(smtpAuth), smtpFrom, smtpTimeoutSeconds);
        try {
            service.save(form.toConfig(), principal.getName(), note);
            redirect.addFlashAttribute("notice", "Einstellungen gespeichert und sofort übernommen (neuer Datensatz, Historie bleibt erhalten).");
            return "redirect:/verwaltung";
        } catch (SettingsException e) {
            fill(model, form);
            model.addAttribute("errors", e.errors());
            model.addAttribute("note", note);
            return "verwaltung";
        }
    }

    // ---- Benutzer (ADR 0015) ----------------------------------------------------------------

    @PostMapping("/verwaltung/benutzer")
    public String createUser(@RequestParam String username, @RequestParam String role, @RequestParam String password,
                             @RequestParam String passwordRepeat, Principal principal, RedirectAttributes redirect) {
        return userAction(redirect, "Benutzer '" + username.trim() + "' angelegt.",
                () -> accounts.create(username, role(role), password, passwordRepeat, principal.getName()));
    }

    @PostMapping("/verwaltung/benutzer/passwort")
    public String resetUserPassword(@RequestParam String username, @RequestParam String password, @RequestParam String passwordRepeat,
                                    Principal principal, RedirectAttributes redirect) {
        return userAction(redirect, "Passwort für '" + username.trim() + "' neu vergeben.",
                () -> accounts.changePassword(username, password, passwordRepeat, principal.getName()));
    }

    @PostMapping("/verwaltung/benutzer/rolle")
    public String changeUserRole(@RequestParam String username, @RequestParam String role, Principal principal, RedirectAttributes redirect) {
        return userAction(redirect, "Rolle von '" + username.trim() + "' geändert; wirkt ab der nächsten Anfrage dieses Benutzers.",
                () -> accounts.changeRole(username, role(role), principal.getName()));
    }

    @PostMapping("/verwaltung/benutzer/aktiv")
    public String setUserActive(@RequestParam String username, @RequestParam boolean active, Principal principal, RedirectAttributes redirect) {
        return userAction(redirect, "Benutzer '" + username.trim() + "' " + (active ? "freigegeben." : "gesperrt; laufende Sitzungen enden mit der nächsten Anfrage."),
                () -> accounts.setActive(username, active, principal.getName()));
    }

    @FunctionalInterface
    private interface UserAction {
        void run() throws SettingsException;
    }

    private static String userAction(RedirectAttributes redirect, String notice, UserAction action) {
        try {
            action.run();
            redirect.addFlashAttribute("notice", notice);
        } catch (SettingsException e) {
            redirect.addFlashAttribute("error", String.join("; ", e.errors()));
        }
        return "redirect:/verwaltung#benutzer";
    }

    private static Role role(String value) throws SettingsException {
        try {
            return Role.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SettingsException(List.of("Rolle ungültig"));
        }
    }

    /** Netzzugriff (ADR 0015, Phase B): wird gespeichert und wirkt nach dem Neustart des Dienstes. */
    @PostMapping("/verwaltung/netz")
    public String saveNetwork(@RequestParam String mode, @RequestParam(defaultValue = "8443") String port, Principal principal,
                              RedirectAttributes redirect) {
        try {
            NetworkSettingsService.Pending saved = network.save(mode, port, principal.getName());
            redirect.addFlashAttribute("notice", "Netzzugriff gespeichert: " + saved.mode().label()
                    + ". Die Änderung wirkt nach einem Neustart des Dienstes.");
        } catch (SettingsException e) {
            redirect.addFlashAttribute("error", String.join("; ", e.errors()));
        }
        return "redirect:/verwaltung#netz";
    }

    /** Mandanten und Profile aus den Dateien neu laden, ohne Neustart (ADR 0013). */
    @PostMapping("/verwaltung/neu-laden")
    public String reload(RedirectAttributes redirect) {
        try {
            registry.reload();
            redirect.addFlashAttribute("notice", "Mandanten und Profile neu geladen: " + registry.tenants().size() + " Mandant(en), "
                    + registry.profiles().size() + " Profil(e).");
        } catch (IOException | RuntimeException e) {
            redirect.addFlashAttribute("error", "Neu laden abgelehnt, bisheriger Stand bleibt wirksam: " + e.getMessage());
        }
        return "redirect:/verwaltung";
    }

    private void fill(Model model, Form form) {
        model.addAttribute("accounts", accounts.accounts());
        model.addAttribute("roles", Role.values());
        model.addAttribute("envUsername", accounts.envUsername());
        model.addAttribute("envPassword", accounts.envConfigured());
        NetworkAccess access = network.access();
        model.addAttribute("net", access);
        model.addAttribute("netPending", network.pending().orElse(null));
        model.addAttribute("netFile", network.file());
        model.addAttribute("netHost", StatusController.hostName());
        // Solange der Netzzugriff nicht läuft, das Zertifikat für die Anzeige jetzt suchen
        model.addAttribute("netLookup", access.certificate() != null ? null : network.lookupCertificate());
        model.addAttribute("tenantFile", registry.tenantFile().toAbsolutePath().normalize());
        model.addAttribute("form", form);
        model.addAttribute("source", service.source());
        model.addAttribute("history", service.history(20));
        model.addAttribute("fixed", properties);
        model.addAttribute("smtpCredentials", email.hasCredentials());
        model.addAttribute("watcherIntervalMillis", watcher.activeIntervalMillis());
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("active", "verwaltung");
    }

    private static boolean on(String v) {
        return v != null && ("on".equals(v) || "true".equals(v));
    }
}
