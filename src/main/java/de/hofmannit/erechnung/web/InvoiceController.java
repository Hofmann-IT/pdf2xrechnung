package de.hofmannit.erechnung.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.dispatch.DispatchException;
import de.hofmannit.erechnung.dispatch.PostProcessService;
import de.hofmannit.erechnung.export.BelegtransferService;
import de.hofmannit.erechnung.export.ExportException;
import de.hofmannit.erechnung.export.ExportSettingsService;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ArtifactRow;
import de.hofmannit.erechnung.ledger.Rows.EventRow;
import de.hofmannit.erechnung.ledger.Rows.InvoiceFilter;
import de.hofmannit.erechnung.ledger.Rows.InvoiceListRow;
import de.hofmannit.erechnung.model.ArtifactType;
import de.hofmannit.erechnung.watcher.ReprocessService;
import de.hofmannit.erechnung.watcher.ReprocessService.ReprocessException;
import de.hofmannit.erechnung.watcher.TenantExecutors;
import de.hofmannit.erechnung.web.WebExceptionHandler.NotFoundException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Ausgangsrechnungen: Liste mit Filtern, Detailansicht, Downloads, Reprocess und erneuter Versand (Vorgabe 27, 28). */
@Controller
public class InvoiceController {

    private final LedgerRepository ledger;
    private final DirectoryLayout layout;
    private final ProfileRegistry registry;
    private final ReprocessService reprocess;
    private final TenantExecutors executors;
    private final PostProcessService postProcess;
    private final ExportSettingsService exportSettings;
    private final ExportRepository exportRepository;
    private final BelegtransferService belegtransferService;
    private final ObjectMapper json;

    public InvoiceController(LedgerRepository ledger, DirectoryLayout layout, ProfileRegistry registry, ReprocessService reprocess,
                             TenantExecutors executors, PostProcessService postProcess, ExportSettingsService exportSettings,
                             ExportRepository exportRepository, BelegtransferService belegtransferService, ObjectMapper json) {
        this.belegtransferService = belegtransferService;
        this.ledger = ledger;
        this.layout = layout;
        this.registry = registry;
        this.reprocess = reprocess;
        this.executors = executors;
        this.postProcess = postProcess;
        this.exportSettings = exportSettings;
        this.exportRepository = exportRepository;
        this.json = json;
    }

    @GetMapping("/rechnungen")
    public String list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
                       @RequestParam(required = false) String format, @RequestParam(required = false) String tenant,
                       @RequestParam(required = false) String von, @RequestParam(required = false) String bis,
                       @RequestParam(required = false) String kunde,
                       @RequestHeader(value = "HX-Request", required = false) String hx, Model model) {
        InvoiceFilter filter = new InvoiceFilter(tenant, q, status, format, von, bis, kunde, 200);
        model.addAttribute("rows", ledger.listInvoices(filter));
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", InvoiceStatus.values());
        model.addAttribute("tenants", registry.tenants());
        model.addAttribute("active", "rechnungen");
        return hx != null ? "invoices :: rows" : "invoices";
    }

    /** Zeile der Prozess-Timeline. */
    public record TimelineStep(String label, String state, String detail) {
    }

    @GetMapping("/rechnungen/{runId}")
    public String detail(@PathVariable long runId, Model model) throws IOException {
        InvoiceListRow row = ledger.findInvoice(runId).orElseThrow(() -> new NotFoundException("Run " + runId + " existiert nicht"));
        List<EventRow> events = ledger.events(runId);
        List<ArtifactRow> artifacts = ledger.artifacts(runId);
        model.addAttribute("row", row);
        model.addAttribute("events", events);
        model.addAttribute("artifacts", artifacts);
        model.addAttribute("validations", ledger.validationResults(runId));
        model.addAttribute("taxLines", row.entry() == null ? List.of() : ledger.taxLines(row.entry().id()));
        model.addAttribute("timeline", timeline(events));
        model.addAttribute("runs", ledger.findRuns(row.source().id()));
        model.addAttribute("extraction", extraction(artifacts));
        model.addAttribute("needsConfirmation", row.status() == InvoiceStatus.VALID || row.status() == InvoiceStatus.ARCHIVED
                || row.status() == InvoiceStatus.DISPATCHED || row.status() == InvoiceStatus.DISPATCH_PENDING || row.status() == InvoiceStatus.DISPATCH_FAILED);
        model.addAttribute("canDispatch", row.run().result() == de.hofmannit.erechnung.ledger.RunResult.SUCCESS
                && registry.profile(row.run().profileName()).map(p -> p.definition().postProcess() != null
                        && p.definition().postProcess().email() != null && p.definition().postProcess().email().enabled()).orElse(false));
        model.addAttribute("exportFields", exportRepository.latestInvoiceFields(row.source().id()).orElse(null));
        model.addAttribute("exportFieldHistory", exportRepository.invoiceFieldHistory(row.source().id()));
        model.addAttribute("transfers", exportRepository.transfers(runId));
        model.addAttribute("belegtransferEnabled", exportSettings.effective(row.source().tenantId()).belegtransferEnabled());
        model.addAttribute("active", "rechnungen");
        return "invoice-detail";
    }

    /** Manuelle (erneute) Übergabe der ZUGFeRD-PDF an das DATEV-Belegtransfer-Verzeichnis (ADR 0011). */
    @PostMapping("/rechnungen/{runId}/belegtransfer")
    public String belegtransfer(@PathVariable long runId, @RequestParam String user, RedirectAttributes redirect) {
        try {
            var t = belegtransferService.transferManually(runId, user);
            if ("FAILED".equals(t.outcome())) {
                redirect.addFlashAttribute("error", "Belegtransfer fehlgeschlagen: " + t.message());
            } else {
                redirect.addFlashAttribute("notice", "Belegtransfer: " + t.message());
            }
        } catch (ExportException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/rechnungen/" + runId;
    }

    /** Ergänzt DATEV-Felder (#115/#116, #117, #40) je Quelldokument; append-only, gilt für alle Runs des Dokuments (ADR 0010). */
    @PostMapping("/rechnungen/{runId}/export-felder")
    public String exportFields(@PathVariable long runId, @RequestParam String user, @RequestParam(required = false) String serviceDate,
                               @RequestParam(required = false) String taxPeriodDate, @RequestParam(required = false) String dueDate,
                               @RequestParam(required = false) String buyerVatId, @RequestParam(required = false) String note,
                               RedirectAttributes redirect) {
        InvoiceListRow row = ledger.findInvoice(runId).orElseThrow(() -> new NotFoundException("Run " + runId + " existiert nicht"));
        try {
            exportSettings.saveInvoiceFields(row.source().id(), serviceDate, taxPeriodDate, dueDate, buyerVatId, user, note);
            redirect.addFlashAttribute("notice", "DATEV-Zusatzfelder gespeichert. Sie gelten für alle Runs dieses Dokuments und werden beim nächsten Export verwendet.");
        } catch (ExportException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/rechnungen/" + runId;
    }

    @GetMapping("/rechnungen/{runId}/artefakt/{artifactId}")
    public ResponseEntity<FileSystemResource> download(@PathVariable long runId, @PathVariable long artifactId) {
        ArtifactRow a = ledger.findArtifact(artifactId).filter(x -> x.processingRunId() == runId)
                .orElseThrow(() -> new NotFoundException("Artefakt existiert nicht"));
        Path root = layout.archiveRoot();
        Path file = root.resolve(a.path()).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            throw new NotFoundException("Artefaktdatei nicht vorhanden");
        }
        String name = file.getFileName().toString();
        MediaType type = name.endsWith(".pdf") ? MediaType.APPLICATION_PDF : name.endsWith(".xml") ? MediaType.APPLICATION_XML
                : name.endsWith(".html") ? MediaType.TEXT_HTML : name.endsWith(".json") ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"")
                .contentType(type)
                .body(new FileSystemResource(file));
    }

    @PostMapping("/rechnungen/{runId}/reprocess")
    public String reprocess(@PathVariable long runId, @RequestParam String user, @RequestParam String reason,
                            @RequestParam(required = false) String confirm, RedirectAttributes redirect) throws IOException {
        InvoiceListRow row = ledger.findInvoice(runId).orElseThrow(() -> new NotFoundException("Run " + runId + " existiert nicht"));
        try {
            String parent = reprocess.reprocessAsync(row.source().tenantId(), row.source().sha256(), user, reason, "on".equals(confirm) || "true".equals(confirm), executors);
            redirect.addFlashAttribute("notice", "Reprocess für " + parent + " gestartet. Der neue Run erscheint in der Liste, sobald er begonnen hat.");
        } catch (ReprocessException e) {
            redirect.addFlashAttribute("error", e.getMessage() + (e.isConfirmationRequired() ? " Bitte die Bestätigung setzen." : ""));
        }
        return "redirect:/rechnungen/" + runId;
    }

    @PostMapping("/rechnungen/{runId}/versand")
    public String dispatch(@PathVariable long runId, @RequestParam String user, RedirectAttributes redirect) {
        try {
            PostProcessService.DispatchOutcome outcome = postProcess.dispatchManually(runId, user == null || user.isBlank() ? "web" : user);
            redirect.addFlashAttribute("notice", outcome.message());
        } catch (DispatchException e) {
            redirect.addFlashAttribute("error", "Versand fehlgeschlagen: " + e.getMessage());
        }
        return "redirect:/rechnungen/" + runId;
    }

    /** Prozess-Timeline (Vorgabe 28): erkannt → extrahiert → plausibilisiert → erzeugt → validiert → archiviert → versendet. */
    static List<TimelineStep> timeline(List<EventRow> events) {
        Map<String, EventType[]> steps = new LinkedHashMap<>();
        steps.put("Erkannt", new EventType[] {EventType.PROCESSING_STARTED, EventType.REPROCESS_STARTED});
        steps.put("Extrahiert", new EventType[] {EventType.EXTRACTION_COMPLETED});
        steps.put("Plausibilisiert", new EventType[] {EventType.PLAUSIBILITY_PASSED});
        steps.put("Erzeugt", new EventType[] {EventType.GENERATION_COMPLETED});
        steps.put("Validiert", new EventType[] {EventType.VALIDATION_SUCCEEDED});
        steps.put("Archiviert", new EventType[] {EventType.ARCHIVED});
        steps.put("Versendet", new EventType[] {EventType.DISPATCH_SUCCEEDED});
        List<TimelineStep> result = new ArrayList<>();
        boolean failedSeen = false;
        for (Map.Entry<String, EventType[]> step : steps.entrySet()) {
            EventRow hit = null;
            for (EventRow e : events) {
                for (EventType t : step.getValue()) {
                    if (e.type() == t) {
                        hit = e;
                    }
                }
            }
            String state;
            String detail = hit == null ? "" : hit.message();
            if (hit != null) {
                state = "done";
            } else if (failedSeen) {
                state = "skipped";
            } else {
                EventRow failure = failureFor(step.getKey(), events);
                if (failure != null) {
                    state = "failed";
                    detail = failure.message();
                    failedSeen = true;
                } else {
                    state = "pending";
                }
            }
            result.add(new TimelineStep(step.getKey(), state, detail));
        }
        return result;
    }

    private static EventRow failureFor(String step, List<EventRow> events) {
        for (EventRow e : events) {
            switch (e.type()) {
                case REJECTED -> {
                    if (step.equals("Erkannt")) {
                        return e;
                    }
                }
                case REVIEW_REQUIRED -> {
                    if (step.equals("Plausibilisiert")) {
                        return e;
                    }
                }
                case VALIDATION_FAILED -> {
                    if (step.equals("Validiert")) {
                        return e;
                    }
                }
                case DISPATCH_FAILED -> {
                    if (step.equals("Versendet")) {
                        return e;
                    }
                }
                case PROCESSING_FAILED -> {
                    boolean generated = events.stream().anyMatch(x -> x.type() == EventType.GENERATION_COMPLETED);
                    boolean extracted = events.stream().anyMatch(x -> x.type() == EventType.EXTRACTION_COMPLETED);
                    String failing = !extracted ? "Extrahiert" : !generated ? "Erzeugt" : "Archiviert";
                    if (step.equals(failing)) {
                        return e;
                    }
                }
                default -> {
                }
            }
        }
        return null;
    }

    /** Extraktionsreport (extraction.json) aus dem Archiv für die Detailansicht. */
    private JsonNode extraction(List<ArtifactRow> artifacts) throws IOException {
        for (ArtifactRow a : artifacts) {
            if (a.type() == ArtifactType.EXTRACTION_LOG && a.path().endsWith("extraction.json")) {
                Path file = layout.archiveRoot().resolve(a.path()).normalize();
                if (file.startsWith(layout.archiveRoot()) && Files.isRegularFile(file)) {
                    return json.readTree(Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return null;
    }
}
