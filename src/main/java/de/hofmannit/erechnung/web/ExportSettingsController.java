package de.hofmannit.erechnung.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.configuration.TenantProperties.Belegtransfer;
import de.hofmannit.erechnung.configuration.TenantProperties.Datev;
import de.hofmannit.erechnung.configuration.TenantProperties.DebtorStrategy;
import de.hofmannit.erechnung.configuration.TenantProperties.RevenueAccount;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.export.ExportException;
import de.hofmannit.erechnung.export.ExportSettingsService;
import de.hofmannit.erechnung.export.ExportSettingsService.Effective;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.web.WebExceptionHandler.NotFoundException;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Pflege der DATEV-Export-Einstellungen je Mandant (ADR 0010). Jede Speicherung ist ein neuer
 * Datensatz mit Benutzer, Zeitpunkt und Notiz; die Historie wird angezeigt, nichts wird überschrieben.
 */
@Controller
public class ExportSettingsController {

    private final ExportSettingsService settings;
    private final ExportRepository repository;
    private final ProfileRegistry registry;

    public ExportSettingsController(ExportSettingsService settings, ExportRepository repository, ProfileRegistry registry) {
        this.settings = settings;
        this.repository = repository;
        this.registry = registry;
    }

    /** Formularwerte (alles Text, damit fehlerhafte Eingaben unverändert zurück ins Formular kommen). */
    public record Form(boolean enabled, String consultantNumber, String clientNumber, String fiscalYearStart, String accountLength,
                       String chartOfAccounts, String debtorStrategy, String collectiveDebtorAccount, String customerAccounts,
                       String revenueAccounts, String origin, String exportedBy, String dictationShortcut, boolean lockRecords,
                       String bookingTextTemplate, boolean belegtransferEnabled, String belegtransferDirectory) {

        static Form of(Datev d, Belegtransfer b) {
            boolean btEnabled = b != null && b.enabled();
            String btDir = b == null ? "" : nz(b.directory());
            if (d == null) {
                return new Form(false, "", "", "01-01", "4", "03", "COLLECTIVE", "", "", "", "RE", "", "", true,
                        "Rechnung {invoiceNumber} {customerName}", btEnabled, btDir);
            }
            StringBuilder customers = new StringBuilder();
            d.customerAccounts().forEach((k, v) -> customers.append(k).append(" = ").append(v).append('\n'));
            StringBuilder revenues = new StringBuilder();
            d.revenueAccounts().forEach((k, v) -> revenues.append(k).append(" = ").append(v.account())
                    .append(v.buKey() == null || v.buKey().isBlank() ? "" : " ; " + v.buKey()).append('\n'));
            return new Form(d.enabled(), nz(d.consultantNumber()), nz(d.clientNumber()), nz(d.fiscalYearStart()),
                    String.valueOf(d.accountLength()), nz(d.chartOfAccounts()), d.debtorStrategy() == null ? "COLLECTIVE" : d.debtorStrategy().name(),
                    nz(d.collectiveDebtorAccount()), customers.toString(), revenues.toString(), nz(d.origin()), nz(d.exportedBy()),
                    nz(d.dictationShortcut()), d.lockRecords(), nz(d.bookingTextTemplate()), btEnabled, btDir);
        }

        Belegtransfer toBelegtransfer() {
            return new Belegtransfer(belegtransferEnabled, belegtransferDirectory == null ? null : belegtransferDirectory.trim());
        }

        /** Wandelt die Formularwerte in die Konfiguration; Zahlen- und Zuordnungsfehler werden als Meldung gemeldet. */
        Datev toDatev() throws ExportException {
            int length;
            try {
                length = Integer.parseInt(accountLength.trim());
            } catch (RuntimeException e) {
                throw new ExportException("Sachkontenlänge muss eine Zahl von 4 bis 8 sein");
            }
            DebtorStrategy strategy;
            try {
                strategy = DebtorStrategy.valueOf(debtorStrategy.trim());
            } catch (RuntimeException e) {
                throw new ExportException("Debitorenstrategie ungültig");
            }
            Map<String, String> customers = new LinkedHashMap<>();
            for (String line : lines(customerAccounts)) {
                String[] kv = splitPair(line, "Kundenkonten");
                customers.put(kv[0], kv[1]);
            }
            Map<String, RevenueAccount> revenues = new LinkedHashMap<>();
            for (String line : lines(revenueAccounts)) {
                String[] kv = splitPair(line, "Erlöskonten");
                String[] parts = kv[1].split(";", 2);
                String bu = parts.length > 1 ? parts[1].trim() : null;
                revenues.put(kv[0], new RevenueAccount(parts[0].trim(), bu == null || bu.isBlank() ? null : bu));
            }
            return new Datev(enabled, consultantNumber.trim(), clientNumber.trim(), fiscalYearStart.trim(), length, chartOfAccounts.trim(),
                    strategy, collectiveDebtorAccount == null || collectiveDebtorAccount.isBlank() ? null : collectiveDebtorAccount.trim(),
                    customers, revenues, origin.trim(), exportedBy.trim(), dictationShortcut.trim(), lockRecords, bookingTextTemplate.trim());
        }

        private static List<String> lines(String text) {
            return text == null ? List.of() : text.lines().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        }

        private static String[] splitPair(String line, String what) throws ExportException {
            int idx = line.indexOf('=');
            if (idx <= 0 || idx == line.length() - 1) {
                throw new ExportException(what + ": Zeile '" + line + "' muss die Form 'Schlüssel = Konto' haben");
            }
            return new String[] {line.substring(0, idx).trim(), line.substring(idx + 1).trim()};
        }

        private static String nz(String s) {
            return s == null ? "" : s;
        }
    }

    @GetMapping("/rechnungen/export/einstellungen")
    public String page(@RequestParam(required = false) String tenant, Model model) {
        String tenantId = tenant == null || tenant.isBlank() ? registry.tenants().get(0).id() : tenant;
        registry.tenant(tenantId).orElseThrow(() -> new NotFoundException("Mandant " + tenantId + " existiert nicht"));
        Effective eff = settings.effective(tenantId);
        fill(model, tenantId, Form.of(eff.datev(), eff.belegtransfer()), eff);
        return "export-settings";
    }

    @PostMapping("/rechnungen/export/einstellungen")
    public String save(@RequestParam String tenant, @RequestParam String user, @RequestParam(required = false) String note,
                       @RequestParam(required = false) String enabled, @RequestParam(defaultValue = "") String consultantNumber,
                       @RequestParam(defaultValue = "") String clientNumber, @RequestParam(defaultValue = "01-01") String fiscalYearStart,
                       @RequestParam(defaultValue = "4") String accountLength, @RequestParam(defaultValue = "03") String chartOfAccounts,
                       @RequestParam(defaultValue = "COLLECTIVE") String debtorStrategy, @RequestParam(defaultValue = "") String collectiveDebtorAccount,
                       @RequestParam(defaultValue = "") String customerAccounts, @RequestParam(defaultValue = "") String revenueAccounts,
                       @RequestParam(defaultValue = "RE") String origin, @RequestParam(defaultValue = "") String exportedBy,
                       @RequestParam(defaultValue = "") String dictationShortcut, @RequestParam(required = false) String lockRecords,
                       @RequestParam(defaultValue = "") String bookingTextTemplate,
                       @RequestParam(required = false) String belegtransferEnabled, @RequestParam(defaultValue = "") String belegtransferDirectory,
                       Model model, RedirectAttributes redirect) {
        registry.tenant(tenant).orElseThrow(() -> new NotFoundException("Mandant " + tenant + " existiert nicht"));
        Form form = new Form(checked(enabled), consultantNumber, clientNumber, fiscalYearStart, accountLength, chartOfAccounts, debtorStrategy,
                collectiveDebtorAccount, customerAccounts, revenueAccounts, origin, exportedBy, dictationShortcut, checked(lockRecords),
                bookingTextTemplate, checked(belegtransferEnabled), belegtransferDirectory);
        try {
            settings.save(tenant, form.toDatev(), form.toBelegtransfer(), user, note);
            redirect.addFlashAttribute("notice", "Export-Einstellungen für " + tenant + " gespeichert (neuer Datensatz, Historie bleibt erhalten).");
            return "redirect:/rechnungen/export/einstellungen?tenant=" + tenant;
        } catch (ExportException e) {
            fill(model, tenant, form, settings.effective(tenant));
            model.addAttribute("error", e.getMessage());
            model.addAttribute("user", user);
            model.addAttribute("note", note);
            return "export-settings";
        }
    }

    private void fill(Model model, String tenantId, Form form, Effective eff) {
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("form", form);
        model.addAttribute("source", eff.source());
        model.addAttribute("history", repository.settingsHistory(tenantId));
        model.addAttribute("active", "rechnungen");
    }

    private static boolean checked(String value) {
        return value != null && ("on".equals(value) || "true".equals(value));
    }
}
