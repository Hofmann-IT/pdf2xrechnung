package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.web.WebExceptionHandler.NotFoundException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Manuelle Prüfung (Vorgabe Abschnitt 30): Dateien aus {@code manual-review/} mit PDF,
 * extrahierten Werten, Plausibilitätsfehlern und Fundstellen. Keine Änderung fachlicher Werte;
 * Korrektur-/Freigabeprozesse sind nicht definiert und daher nicht vorhanden.
 */
@Controller
public class ReviewController {

    private final DirectoryLayout layout;
    private final ProfileRegistry registry;
    private final ObjectMapper json;

    public ReviewController(DirectoryLayout layout, ProfileRegistry registry, ObjectMapper json) {
        this.layout = layout;
        this.registry = registry;
        this.json = json;
    }

    /** Ein Vorgang in manual-review/. */
    public record ReviewItem(String tenantId, String directory, String reason, String correlationId, java.time.Instant modified, boolean hasPdf) {
    }

    @GetMapping("/pruefung")
    public String list(Model model) throws IOException {
        List<ReviewItem> items = new ArrayList<>();
        for (Tenant t : registry.tenants()) {
            Path dir = layout.manualReview(t);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> s = Files.list(dir)) {
                for (Path d : s.filter(Files::isDirectory).sorted().toList()) {
                    String text = readIfExists(d.resolve("pruefung.txt"));
                    items.add(new ReviewItem(t.id(), d.getFileName().toString(), reasonOf(text), correlationOf(text),
                            Files.getLastModifiedTime(d).toInstant(), Files.isRegularFile(d.resolve("original.pdf"))));
                }
            }
        }
        model.addAttribute("items", items);
        model.addAttribute("active", "pruefung");
        return "review";
    }

    @GetMapping("/pruefung/{tenant}/{dir}")
    public String detail(@PathVariable String tenant, @PathVariable String dir, Model model) throws IOException {
        Path d = resolve(tenant, dir);
        String text = readIfExists(d.resolve("pruefung.txt"));
        JsonNode extraction = null;
        if (Files.isRegularFile(d.resolve("extraction.json"))) {
            extraction = json.readTree(Files.readString(d.resolve("extraction.json"), StandardCharsets.UTF_8));
        }
        model.addAttribute("tenant", tenant);
        model.addAttribute("dir", dir);
        model.addAttribute("reason", text);
        model.addAttribute("extraction", extraction);
        model.addAttribute("hasPdf", Files.isRegularFile(d.resolve("original.pdf")));
        model.addAttribute("active", "pruefung");
        return "review-detail";
    }

    @GetMapping("/pruefung/{tenant}/{dir}/original.pdf")
    public ResponseEntity<FileSystemResource> pdf(@PathVariable String tenant, @PathVariable String dir) {
        Path file = resolve(tenant, dir).resolve("original.pdf");
        if (!Files.isRegularFile(file)) {
            throw new NotFoundException("PDF nicht vorhanden");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", "inline; filename=\"original.pdf\"")
                .body(new FileSystemResource(file));
    }

    private Path resolve(String tenantId, String dir) {
        Tenant t = registry.tenant(tenantId).orElseThrow(() -> new NotFoundException("Unbekannter Mandant"));
        Path root = layout.manualReview(t);
        if (dir.contains("..") || dir.contains("/") || dir.contains("\\")) {
            throw new NotFoundException("Ungültiger Vorgang");
        }
        Path d = root.resolve(dir).normalize();
        if (!d.startsWith(root) || !Files.isDirectory(d)) {
            throw new NotFoundException("Vorgang nicht vorhanden");
        }
        return d;
    }

    private static String readIfExists(Path p) throws IOException {
        return Files.isRegularFile(p) ? Files.readString(p, StandardCharsets.UTF_8) : "";
    }

    private static String reasonOf(String text) {
        int idx = text.indexOf("\n\n");
        return idx < 0 ? text.strip() : text.substring(idx).strip();
    }

    private static String correlationOf(String text) {
        for (String line : text.split("\n")) {
            if (line.startsWith("Run: ")) {
                return line.substring(5).strip();
            }
        }
        return "";
    }
}
