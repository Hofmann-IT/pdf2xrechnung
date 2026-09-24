package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.ExtractionException;
import de.hofmannit.erechnung.extraction.PdfTextExtractor;
import de.hofmannit.erechnung.mapping.ClassificationResult;
import de.hofmannit.erechnung.mapping.Classifier;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.MappingEngine;
import de.hofmannit.erechnung.plausibility.PlausibilityChecker;
import de.hofmannit.erechnung.plausibility.PlausibilityResult;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Profile und Profil-Test-Seite (Vorgabe Abschnitt 31): PDF hochladen, erkanntes Profil,
 * Business Terms mit Fundstellen und Regeln, Plausibilität. Keine Erzeugung, keine Ablage,
 * kein Versand, kein Ledger-Eintrag.
 */
@Controller
public class ProfileController {

    private final ProfileRegistry registry;
    private final AppProperties properties;
    private final PdfTextExtractor extractor;
    private final Classifier classifier;
    private final MappingEngine mapping;
    private final PlausibilityChecker plausibility;

    public ProfileController(ProfileRegistry registry, AppProperties properties, PdfTextExtractor extractor, Classifier classifier,
                             MappingEngine mapping, PlausibilityChecker plausibility) {
        this.registry = registry;
        this.properties = properties;
        this.extractor = extractor;
        this.classifier = classifier;
        this.mapping = mapping;
        this.plausibility = plausibility;
    }

    /** Ergebnis des Profil-Tests. */
    public record ProfileTestResult(String fileName, int pages, LoadedProfile profile, ClassificationResult classification,
                                    InvoiceData data, PlausibilityResult plausibility, String error) {
    }

    @GetMapping("/profile")
    public String list(Model model) {
        model.addAttribute("profiles", registry.profiles().values());
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("profilesDir", properties.directories().profiles().toAbsolutePath().normalize());
        model.addAttribute("active", "profile");
        return "profiles";
    }

    @PostMapping("/profile/test")
    public String test(@RequestParam("file") MultipartFile file, @RequestParam(value = "tenant", required = false) String tenantId,
                       @RequestHeader(value = "HX-Request", required = false) String hx, Model model) throws IOException {
        model.addAttribute("active", "profile");
        model.addAttribute("profiles", registry.profiles().values());
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("profilesDir", properties.directories().profiles().toAbsolutePath().normalize());
        if (file == null || file.isEmpty()) {
            model.addAttribute("test", new ProfileTestResult("", 0, null, null, null, null, "Bitte eine PDF-Datei auswählen."));
            return hx != null ? "profiles :: test" : "profiles";
        }
        Tenant tenant = (tenantId == null || tenantId.isBlank() ? registry.tenants().stream().findFirst() : registry.tenant(tenantId))
                .orElseThrow();
        Path tmp = Files.createTempDirectory(Files.createDirectories(properties.directories().data().resolve("tmp")), "profiletest-");
        try {
            Path pdf = Files.write(tmp.resolve("test.pdf"), file.getBytes());
            ExtractedDocument doc = extractor.extract(pdf);
            LoadedProfile selected = null;
            ClassificationResult classification = null;
            for (String name : tenant.profiles()) {
                LoadedProfile candidate = registry.profile(name).orElseThrow();
                ClassificationResult c = classifier.classify(doc, candidate.definition());
                classification = c;
                if (c.isInvoice()) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) {
                model.addAttribute("test", new ProfileTestResult(file.getOriginalFilename(), doc.pageCount(), null, classification, null, null, null));
            } else {
                InvoiceData data = mapping.map(doc, selected.definition(), classification, tenant.fixedValues());
                PlausibilityResult pl = plausibility.check(data, selected.definition().plausibility());
                model.addAttribute("test", new ProfileTestResult(file.getOriginalFilename(), doc.pageCount(), selected, classification, data, pl, null));
            }
        } catch (ExtractionException e) {
            model.addAttribute("test", new ProfileTestResult(file.getOriginalFilename(), 0, null, null, null, null, e.getMessage()));
        } finally {
            try (Stream<Path> s = Files.walk(tmp)) {
                s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> p.toFile().delete());
            }
        }
        return hx != null ? "profiles :: test" : "profiles";
    }

    static List<String> nothing() {
        return List.of();
    }
}
