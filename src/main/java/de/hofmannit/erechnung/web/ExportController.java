package de.hofmannit.erechnung.web;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.export.ExportException;
import de.hofmannit.erechnung.export.ExportResult;
import de.hofmannit.erechnung.export.ExportService;
import de.hofmannit.erechnung.export.ExportService.Variant;
import de.hofmannit.erechnung.export.ExportSettingsService;
import de.hofmannit.erechnung.ledger.ExportRepository;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** Export des Rechnungsausgangsbuchs: Vorschau (Anzahl, Hinweise) und Download. */
@Controller
public class ExportController {

    private final ExportService exports;
    private final ExportSettingsService settings;
    private final ExportRepository exportRepository;
    private final ProfileRegistry registry;

    public ExportController(ExportService exports, ExportSettingsService settings, ExportRepository exportRepository, ProfileRegistry registry) {
        this.exports = exports;
        this.settings = settings;
        this.exportRepository = exportRepository;
        this.registry = registry;
    }

    @GetMapping("/rechnungen/export")
    public String page(@RequestParam(required = false) String tenant, Model model) {
        String tenantId = tenant == null || tenant.isBlank() ? registry.tenants().get(0).id() : tenant;
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("tenantId", tenantId);
        model.addAttribute("variants", exports.availableVariants(tenantId));
        model.addAttribute("datevConfigured", exports.availableVariants(tenantId).contains(Variant.DATEV_BUCHUNGSSTAPEL));
        model.addAttribute("settingsSource", settings.effective(tenantId).source());
        model.addAttribute("exportLog", exportRepository.exportLog(tenantId, 20));
        model.addAttribute("active", "rechnungen");
        return "export";
    }

    @GetMapping("/rechnungen/export/vorschau")
    public String preview(@RequestParam String tenant, @RequestParam String variant,
                          @RequestParam(required = false) String von, @RequestParam(required = false) String bis,
                          @RequestParam(required = false) String user,
                          @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("tenantId", tenant);
        model.addAttribute("variants", exports.availableVariants(tenant));
        model.addAttribute("active", "rechnungen");
        model.addAttribute("user", user);
        try {
            ExportResult r = exports.preview(Variant.valueOf(variant), tenant, parse(von), parse(bis));
            model.addAttribute("preview", r);
            model.addAttribute("previewVariant", Variant.valueOf(variant));
            model.addAttribute("von", von);
            model.addAttribute("bis", bis);
        } catch (ExportException | IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        }
        return hx != null ? "export :: preview" : "export";
    }

    /** Download mit Protokolleintrag; der Benutzername wird wie bei allen Aktionen als Formularfeld erwartet. */
    @GetMapping("/rechnungen/export/download")
    public ResponseEntity<byte[]> download(@RequestParam String tenant, @RequestParam String variant,
                                           @RequestParam(required = false) String von, @RequestParam(required = false) String bis,
                                           @RequestParam String user) throws ExportException {
        ExportResult r = exports.export(Variant.valueOf(variant), tenant, parse(von), parse(bis), user);
        String[] ct = r.contentType().split(";charset=");
        MediaType type = ct.length > 1 ? new MediaType(MediaType.parseMediaType(ct[0]), java.nio.charset.Charset.forName(ct[1]))
                : MediaType.parseMediaType(r.contentType());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + r.fileName() + "\"")
                .header("X-Content-SHA256", r.sha256())
                .contentType(type)
                .body(r.content());
    }

    private static LocalDate parse(String s) {
        return s == null || s.isBlank() ? null : LocalDate.parse(s.trim());
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
