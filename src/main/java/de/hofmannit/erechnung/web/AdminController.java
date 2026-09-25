package de.hofmannit.erechnung.web;

import java.time.Duration;
import java.util.List;

import de.hofmannit.erechnung.admin.AdminSettingsService;
import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.configuration.AppProperties;
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
 * Zugriff nur mit Admin-Anmeldung ({@link AdminAuthFilter}).
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

    public AdminController(AdminSettingsService service, AppProperties properties, EmailDispatcher email, InboxWatcher watcher,
                           ProfileRegistry registry) {
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
    public String save(@RequestParam String user, @RequestParam(required = false) String note,
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
            service.save(form.toConfig(), user, note);
            redirect.addFlashAttribute("notice", "Einstellungen gespeichert und sofort übernommen (neuer Datensatz, Historie bleibt erhalten).");
            return "redirect:/verwaltung";
        } catch (SettingsException e) {
            fill(model, form);
            model.addAttribute("errors", e.errors());
            model.addAttribute("user", user);
            model.addAttribute("note", note);
            return "verwaltung";
        }
    }

    private void fill(Model model, Form form) {
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
