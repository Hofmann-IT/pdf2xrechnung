package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import de.hofmannit.erechnung.inboundvalidation.InboundReportWriter;
import de.hofmannit.erechnung.inboundvalidation.InboundValidationResult;
import de.hofmannit.erechnung.inboundvalidation.InboundValidationService;
import de.hofmannit.erechnung.validation.ValidationReport;
import de.hofmannit.erechnung.validation.ValidatorKind;
import de.hofmannit.erechnung.web.WebExceptionHandler.NotFoundException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * "E-Rechnung prüfen" (Vorgabe Abschnitte 13 und 29). Die Prüfung läuft vollständig lokal;
 * Ergebnisse werden für Downloads kurzzeitig im Speicher gehalten (kein Dateisystem, wenn
 * {@code store-reports} aus ist).
 */
@Controller
public class InboundController {

    private static final Duration RETENTION = Duration.ofMinutes(60);
    private static final int MAX_CACHED = 100;

    private final InboundValidationService service;
    private final Map<String, Cached> cache = new LinkedHashMap<>();

    private record Cached(InboundValidationResult result, Instant storedAt) {
    }

    public InboundController(InboundValidationService service) {
        this.service = service;
    }

    @GetMapping("/pruefen")
    public String page(Model model) {
        model.addAttribute("active", "pruefen");
        return "inbound";
    }

    @PostMapping("/pruefen")
    public String validate(@RequestParam("file") MultipartFile file, @RequestParam(value = "user", required = false) String user,
                           @RequestHeader(value = "HX-Request", required = false) String hx, Model model) throws IOException {
        model.addAttribute("active", "pruefen");
        if (file == null || file.isEmpty()) {
            model.addAttribute("error", "Bitte eine XML- oder PDF-Datei auswählen.");
            return hx != null ? "inbound :: result" : "inbound";
        }
        InboundValidationResult result = service.validate(file.getOriginalFilename(), file.getBytes(), user);
        String token = remember(result);
        model.addAttribute("result", result);
        model.addAttribute("token", token);
        model.addAttribute("kosit", result.report(ValidatorKind.KOSIT));
        model.addAttribute("mustang", result.report(ValidatorKind.MUSTANG));
        return hx != null ? "inbound :: result" : "inbound";
    }

    @GetMapping("/pruefen/{token}/{name}")
    public ResponseEntity<byte[]> download(@PathVariable String token, @PathVariable String name) {
        InboundValidationResult r = lookup(token);
        byte[] body;
        MediaType type;
        switch (name) {
            case "zusammenfassung.txt" -> {
                body = InboundReportWriter.summaryText(r);
                type = MediaType.TEXT_PLAIN;
            }
            case "report.html" -> {
                body = InboundReportWriter.html(r);
                type = MediaType.TEXT_HTML;
            }
            case "report-kosit.xml" -> {
                body = reportXml(r, ValidatorKind.KOSIT);
                type = MediaType.APPLICATION_XML;
            }
            case "report-kosit.html" -> {
                ValidationReport rep = r.report(ValidatorKind.KOSIT);
                body = rep == null ? null : rep.reportHtml();
                type = MediaType.TEXT_HTML;
            }
            case "report-mustang.xml" -> {
                body = reportXml(r, ValidatorKind.MUSTANG);
                type = MediaType.APPLICATION_XML;
            }
            default -> throw new NotFoundException("Unbekannter Report");
        }
        if (body == null) {
            throw new NotFoundException("Dieser Report ist für die Prüfung nicht vorhanden");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(new MediaType(type, java.nio.charset.StandardCharsets.UTF_8))
                .body(body);
    }

    private static byte[] reportXml(InboundValidationResult r, ValidatorKind kind) {
        ValidationReport rep = r.report(kind);
        return rep == null ? null : rep.reportXml();
    }

    private synchronized String remember(InboundValidationResult result) {
        Instant now = Instant.now();
        Iterator<Map.Entry<String, Cached>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Cached> e = it.next();
            if (e.getValue().storedAt().plus(RETENTION).isBefore(now) || cache.size() >= MAX_CACHED) {
                it.remove();
            }
        }
        String token = UUID.randomUUID().toString();
        cache.put(token, new Cached(result, now));
        return token;
    }

    private synchronized InboundValidationResult lookup(String token) {
        Cached c = cache.get(token);
        if (c == null) {
            throw new NotFoundException("Der Prüfvorgang ist nicht mehr verfügbar. Bitte die Datei erneut prüfen.");
        }
        return c.result();
    }
}
