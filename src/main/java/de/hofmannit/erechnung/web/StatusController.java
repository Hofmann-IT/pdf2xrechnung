package de.hofmannit.erechnung.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.ApplicationVersion;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.dispatch.EmailDispatcher;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.validation.KositValidator;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Systemstatus: Verzeichnisse, Validator-Konfiguration, Watcher, SMTP, Version. */
@Controller
public class StatusController {

    private final AppProperties properties;
    private final ApplicationVersion version;
    private final KositValidator kosit;
    private final EmailDispatcher email;
    private final ProfileRegistry registry;
    private final LedgerRepository ledger;

    public StatusController(AppProperties properties, ApplicationVersion version, KositValidator kosit, EmailDispatcher email,
                            ProfileRegistry registry, LedgerRepository ledger) {
        this.properties = properties;
        this.version = version;
        this.kosit = kosit;
        this.email = email;
        this.registry = registry;
        this.ledger = ledger;
    }

    public record DirectoryStatus(String name, Path path, boolean exists, boolean writable) {
    }

    @GetMapping("/status")
    public String status(Model model) {
        AppProperties.Directories d = properties.directories();
        Map<String, Path> dirs = new LinkedHashMap<>();
        dirs.put("inbox", d.inbox());
        dirs.put("processing", d.processing());
        dirs.put("output", d.output());
        dirs.put("failed", d.failed());
        dirs.put("manual-review", d.manualReview());
        dirs.put("rejected", d.rejected());
        dirs.put("archive", d.archive());
        dirs.put("data", d.data());
        dirs.put("inbound-validation", d.inboundValidation());
        dirs.put("profiles", d.profiles());
        dirs.put("validator", d.validatorResources());
        List<DirectoryStatus> statuses = new ArrayList<>();
        dirs.forEach((name, p) -> {
            Path abs = p.toAbsolutePath().normalize();
            statuses.add(new DirectoryStatus(name, abs, Files.isDirectory(abs), Files.isDirectory(abs) && Files.isWritable(abs)));
        });
        model.addAttribute("directories", statuses);
        model.addAttribute("version", version.value());
        model.addAttribute("kositAvailable", kosit.isAvailable());
        model.addAttribute("kositConfig", kosit.configurationName());
        model.addAttribute("kositScenarios", properties.validation().kositScenarios().toAbsolutePath().normalize());
        model.addAttribute("watcher", properties.watcher());
        model.addAttribute("processing", properties.processing());
        model.addAttribute("smtpEnabled", email.isEnabled());
        model.addAttribute("smtp", properties.smtp());
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("profiles", registry.profiles().values());
        model.addAttribute("openRuns", ledger.countOpenRuns());
        model.addAttribute("storeReports", properties.inboundValidation().storeReports());
        model.addAttribute("javaVersion", System.getProperty("java.version"));
        model.addAttribute("active", "status");
        return "status";
    }
}
