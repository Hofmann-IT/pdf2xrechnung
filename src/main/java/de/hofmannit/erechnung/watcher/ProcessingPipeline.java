package de.hofmannit.erechnung.watcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import de.hofmannit.erechnung.archive.ArchiveService;
import de.hofmannit.erechnung.archive.FileStore;
import de.hofmannit.erechnung.archive.FileStore.StoredFile;
import de.hofmannit.erechnung.configuration.ApplicationVersion;
import de.hofmannit.erechnung.configuration.DirectoryLayout;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.dispatch.PostProcessService;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.ExtractionException;
import de.hofmannit.erechnung.extraction.PdfTextExtractor;
import de.hofmannit.erechnung.generation.EInvoiceGenerator;
import de.hofmannit.erechnung.generation.GeneratedArtifact;
import de.hofmannit.erechnung.generation.GenerationException;
import de.hofmannit.erechnung.ledger.CorrelationId;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.Rows.ValidationResultRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.mapping.ClassificationResult;
import de.hofmannit.erechnung.mapping.Classifier;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.MappingEngine;
import de.hofmannit.erechnung.model.ArtifactType;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.OutputFormat;
import de.hofmannit.erechnung.plausibility.PlausibilityChecker;
import de.hofmannit.erechnung.plausibility.PlausibilityResult;
import de.hofmannit.erechnung.validation.ValidationReport;
import de.hofmannit.erechnung.validation.ValidationService;
import de.hofmannit.erechnung.validation.ValidatorKind;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Orchestriert einen Processing-Run (Vorgabe Abschnitte 5–21):
 * Extraktion → Klassifizierung → Mapping → Plausibilität → Erzeugung → Validierung →
 * Ablage → Archiv → Ledger/Events.
 *
 * <p>Jeder Schritt wird als Event persistiert, bevor der nächste beginnt (Absturzsicherheit).
 * Der Run wird genau einmal abgeschlossen. Nichts wird überschrieben.
 */
@Component
public class ProcessingPipeline {

    private static final Logger log = LoggerFactory.getLogger(ProcessingPipeline.class);

    private final LedgerRepository ledger;
    private final ProfileRegistry profiles;
    private final DirectoryLayout layout;
    private final PdfTextExtractor extractor;
    private final Classifier classifier;
    private final MappingEngine mappingEngine;
    private final PlausibilityChecker plausibilityChecker;
    private final EInvoiceGenerator generator;
    private final ValidationService validationService;
    private final ArchiveService archive;
    private final ApplicationVersion version;
    private final Clock clock;
    private final ObjectMapper json;
    private final PostProcessService postProcess;

    public ProcessingPipeline(LedgerRepository ledger, ProfileRegistry profiles, DirectoryLayout layout, PdfTextExtractor extractor,
                              Classifier classifier, MappingEngine mappingEngine, PlausibilityChecker plausibilityChecker,
                              EInvoiceGenerator generator, ValidationService validationService, ArchiveService archive,
                              ApplicationVersion version, Clock clock, ObjectMapper json, PostProcessService postProcess) {
        this.postProcess = postProcess;
        this.ledger = ledger;
        this.profiles = profiles;
        this.layout = layout;
        this.extractor = extractor;
        this.classifier = classifier;
        this.mappingEngine = mappingEngine;
        this.plausibilityChecker = plausibilityChecker;
        this.generator = generator;
        this.validationService = validationService;
        this.archive = archive;
        this.version = version;
        this.clock = clock;
        this.json = json;
    }

    public RunOutcome process(ProcessingJob job) {
        Tenant tenant = job.tenant();
        SourceDocumentRow source = ledger.findSource(tenant.id(), job.sha256())
                .orElseGet(() -> ledger.createSource(tenant.id(), job.sha256(), job.originalFilename(), sizeOf(job.file()), clock.instant()));
        int runNumber = ledger.nextRunNumber(source.id());
        CorrelationId correlation = CorrelationId.forRun(job.sha256(), runNumber);
        MDC.put(CorrelationId.MDC_KEY, correlation.value());
        try {
            return new Run(job, source, runNumber, correlation).execute();
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    /** Zustand eines einzelnen Laufs. */
    private final class Run {
        private final ProcessingJob job;
        private final Tenant tenant;
        private final SourceDocumentRow source;
        private final int runNumber;
        private final CorrelationId correlation;
        private final String sha8;
        private final String baseName;
        private ProcessingRunRow run;
        private Path workDir;
        private LoadedProfile profile;

        Run(ProcessingJob job, SourceDocumentRow source, int runNumber, CorrelationId correlation) {
            this.job = job;
            this.tenant = job.tenant();
            this.source = source;
            this.runNumber = runNumber;
            this.correlation = correlation;
            this.sha8 = job.sha256().substring(0, 8);
            this.baseName = sha8 + "_" + stripExtension(job.originalFilename());
        }

        RunOutcome execute() {
            // 1. Extraktion und Klassifizierung (bestimmen das Profil des Runs)
            ExtractedDocument document = null;
            ExtractionException extractionError = null;
            try {
                document = extractor.extract(job.file());
            } catch (ExtractionException e) {
                extractionError = e;
            }
            ClassificationResult classification = null;
            List<String> triedProfiles = new ArrayList<>();
            if (document != null) {
                for (String name : tenant.profiles()) {
                    LoadedProfile candidate = profiles.profile(name).orElseThrow();
                    triedProfiles.add(name);
                    ClassificationResult c = classifier.classify(document, candidate.definition());
                    if (c.isInvoice()) {
                        profile = candidate;
                        classification = c;
                        break;
                    }
                    classification = c; // letzte Begründung behalten
                }
            }
            if (profile == null) {
                profile = profiles.profile(tenant.profiles().get(0)).orElseThrow();
            }

            // 2. Run anlegen
            run = ledger.createRun(source.id(), runNumber, job.trigger(), job.parentRunId(), job.requestedBy(), job.reason(),
                    profile.name(), profile.sha256(), version.value(), correlation.value(), clock.instant());
            event(job.trigger() == de.hofmannit.erechnung.ledger.RunTrigger.MANUAL_REPROCESS ? EventType.REPROCESS_STARTED : EventType.PROCESSING_STARTED,
                    "Verarbeitung gestartet (" + job.trigger() + ")",
                    Map.of("file", job.originalFilename(), "sha256", job.sha256(), "profile", profile.name(), "trigger", job.trigger().name()));
            log.info("Run {} gestartet für {} (Mandant {}, Profil {})", runNumber, job.originalFilename(), tenant.id(), profile.name());

            try {
                workDir = Files.createDirectories(layout.processing(tenant).resolve(".work").resolve(sha8 + "-run-" + String.format(Locale.ROOT, "%03d", runNumber)));

                if (extractionError != null) {
                    return fail("Extraktion fehlgeschlagen: " + extractionError.getMessage(), null);
                }
                if (!classification.isInvoice()) {
                    return reject(classification, triedProfiles);
                }

                // 3. Mapping
                InvoiceData data = mappingEngine.map(document, profile.definition(), classification, tenant.fixedValues());
                PlausibilityResult plausibility = plausibilityChecker.check(data, profile.definition().plausibility());
                byte[] extractionJson = extractionReport(classification, data, plausibility);
                Files.write(workDir.resolve("extraction.json"), extractionJson);
                event(EventType.EXTRACTION_COMPLETED, "Extraktion und Mapping abgeschlossen",
                        Map.of("fields", data.fields().size(), "lines", data.lines().size(), "documentType", data.documentType().name(),
                                "businessCase", String.valueOf(data.businessCaseId())));

                // 4. Plausibilität
                if (!plausibility.passed()) {
                    return review(data, plausibility);
                }
                event(EventType.PLAUSIBILITY_PASSED, "Plausibilität bestanden", Map.of("taxLines", plausibility.taxLines().size()));

                // 5. Erzeugung
                String outputBase = EInvoiceGenerator.baseName(profile.definition(), data, tenant.id(), runNumber);
                List<GeneratedArtifact> generated;
                try {
                    generated = generator.generate(data, profile.definition(), job.file(), workDir, outputBase);
                } catch (GenerationException e) {
                    return fail("Erzeugung fehlgeschlagen: " + e.getMessage(), data);
                }
                event(EventType.GENERATION_COMPLETED, "Formate erzeugt: " + generated.stream().map(g -> g.format().name()).toList(),
                        Map.of("formats", generated.stream().map(g -> g.format().name()).toList()));

                // 6. Validierung
                List<ValidationReport> reports = new ArrayList<>();
                Map<ValidationReport, GeneratedArtifact> reportArtifact = new LinkedHashMap<>();
                for (GeneratedArtifact g : generated) {
                    for (ValidationReport r : validationService.validate(g.format(), g.path(), workDir)) {
                        reports.add(r);
                        reportArtifact.put(r, g);
                        writeReport(r);
                    }
                }
                boolean valid = ValidationService.allMandatoryValid(reports);
                List<String> summary = reports.stream().map(r -> r.validator() + "/" + r.target() + "=" + r.outcome()
                        + (r.errorCount() > 0 ? " (" + r.errorCount() + " Fehler)" : "") + (r.warningCount() > 0 ? " (" + r.warningCount() + " Warnungen)" : "")).toList();
                if (!valid) {
                    event(EventType.VALIDATION_FAILED, "Validierung fehlgeschlagen: " + summary, Map.of("results", summary));
                    return fail("Validierung fehlgeschlagen: " + summary, data, generated, reports, reportArtifact, plausibility);
                }
                event(EventType.VALIDATION_SUCCEEDED, "Alle verpflichtenden Validierungen bestanden: " + summary, Map.of("results", summary));

                // 7. Ablage in output/ (Kollisionen vorab prüfen, nie überschreiben)
                Path outputDir = Files.createDirectories(layout.output(tenant));
                for (GeneratedArtifact g : generated) {
                    if (Files.exists(outputDir.resolve(g.fileName()))) {
                        return fail("Dateinamenskollision in output/: " + g.fileName() + " existiert bereits", data, generated, reports, reportArtifact, plausibility);
                    }
                }
                List<String> outputFiles = new ArrayList<>();
                for (GeneratedArtifact g : generated) {
                    Path target = FileStore.copyNoOverwrite(g.path(), outputDir.resolve(g.fileName()));
                    outputFiles.add(target.toString());
                }

                // 8. Archiv, Artefakte, Ledger
                LocalDate invoiceDate = data.date(BusinessTerm.BT_2).orElse(LocalDate.now(clock));
                Path runDir = archive.createRunDirectory(invoiceDate, data.value(BusinessTerm.BT_1).orElse(null), job.sha256(), runNumber);
                registerArtifact(ArtifactType.SOURCE_PDF, archive.archiveMove(runDir, "original.pdf", job.file()));
                for (GeneratedArtifact g : generated) {
                    registerArtifact(artifactType(g.format()), archive.archiveMove(runDir, g.archiveName(), g.path()));
                }
                registerArtifact(ArtifactType.EXTRACTION_LOG, archive.archiveBytes(runDir, "extraction.json", extractionJson));
                recordValidation(runDir, reports, reportArtifact);
                writeLedger(data, plausibility, generated.stream().map(g -> g.format().name()).toList());
                event(EventType.ARCHIVED, "Archiviert unter " + archive.relativize(runDir), Map.of("archive", archive.relativize(runDir), "output", outputFiles));
                cleanupWorkDir();
                ledger.finishRun(run.id(), clock.instant(), RunResult.SUCCESS);
                log.info("Run {} erfolgreich: {}", runNumber, outputFiles);
                // 9. Postprozess (Versand/Kommando) nur automatisch bei Erstverarbeitung; nie bei Reprocess.
                if (automaticPostProcessAllowed()) {
                    postProcess.afterSuccessfulRun(run.id());
                } else {
                    log.info("Run {}: kein automatischer Postprozess (Trigger {})", runNumber, job.trigger());
                }
                return new RunOutcome(run.id(), runNumber, RunResult.SUCCESS, "Erfolgreich verarbeitet");
            } catch (Exception e) {
                log.error("Run {} technisch fehlgeschlagen", runNumber, e);
                try {
                    return fail("Technischer Fehler: " + e, null);
                } catch (RuntimeException inner) {
                    log.error("Fehlerbehandlung des Runs {} fehlgeschlagen", runNumber, inner);
                    return new RunOutcome(run.id(), runNumber, RunResult.FAILED, "Technischer Fehler: " + e);
                }
            }
        }

        /**
         * AUTO: ja. MANUAL_REPROCESS: nie (Vorgabe Abschnitt 16). RESTART_RECOVERY: nur, wenn der
         * abgebrochene Vorgänger noch keinen Versandversuch unternommen hatte.
         */
        private boolean automaticPostProcessAllowed() {
            return switch (job.trigger()) {
                case AUTO -> true;
                case MANUAL_REPROCESS -> false;
                case RESTART_RECOVERY -> job.parentRunId() == null || ledger.events(job.parentRunId()).stream()
                        .noneMatch(e -> e.type() == EventType.DISPATCH_ATTEMPTED);
            };
        }

        // ------------------------------------------------------------ Ergebniszweige

        private RunOutcome reject(ClassificationResult classification, List<String> tried) {
            String reason = "Als Nicht-Rechnung klassifiziert (geprüfte Profile " + tried + "): " + String.join("; ", classification.reasons());
            event(EventType.REJECTED, reason, Map.of("reasons", classification.reasons(), "profiles", tried));
            try {
                Path runDir = archive.createRunDirectory(LocalDate.now(clock), null, job.sha256(), runNumber);
                registerArtifact(ArtifactType.SOURCE_PDF, archive.archiveCopy(runDir, "original.pdf", job.file()));
                registerArtifact(ArtifactType.EXTRACTION_LOG, archive.archiveBytes(runDir, "classification.txt", reason.getBytes(StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                log.error("Archivierung des abgewiesenen Runs {} nicht möglich", runNumber, e);
            }
            try {
                Path rejectedDir = Files.createDirectories(layout.rejected(tenant));
                FileStore.moveNoOverwrite(job.file(), rejectedDir.resolve(baseName + ".pdf"));
                FileStore.writeNew(rejectedDir.resolve(baseName + ".txt"), reason.getBytes(StandardCharsets.UTF_8));
                cleanupWorkDir();
            } catch (IOException e) {
                log.error("Ablage in rejected/ fehlgeschlagen", e);
            }
            ledger.finishRun(run.id(), clock.instant(), RunResult.REJECTED);
            log.info("Run {} abgewiesen: {}", runNumber, reason);
            return new RunOutcome(run.id(), runNumber, RunResult.REJECTED, reason);
        }

        private RunOutcome review(InvoiceData data, PlausibilityResult plausibility) {
            String reason = plausibility.describe();
            event(EventType.REVIEW_REQUIRED, "Manuelle Prüfung erforderlich: " + reason,
                    Map.of("issues", plausibility.issues().stream().map(i -> (i.check() == null ? "" : i.check() + ": ") + i.message()).toList()));
            try {
                LocalDate date = data.date(BusinessTerm.BT_2).orElse(LocalDate.now(clock));
                Path runDir = archive.createRunDirectory(date, data.value(BusinessTerm.BT_1).orElse(null), job.sha256(), runNumber);
                registerArtifact(ArtifactType.SOURCE_PDF, archive.archiveCopy(runDir, "original.pdf", job.file()));
                registerArtifact(ArtifactType.EXTRACTION_LOG, archive.archiveCopy(runDir, "extraction.json", workDir.resolve("extraction.json")));
            } catch (IOException | RuntimeException e) {
                log.error("Archivierung des REVIEW-Runs {} nicht möglich", runNumber, e);
            }
            try {
                writeLedger(data, plausibility, List.of());
            } catch (RuntimeException e) {
                log.error("Ledger-Eintrag für REVIEW-Run {} nicht möglich", runNumber, e);
            }
            try {
                Path reviewDir = Files.createDirectories(layout.manualReview(tenant).resolve(baseName));
                FileStore.moveNoOverwrite(job.file(), reviewDir.resolve("original.pdf"));
                FileStore.copyNoOverwrite(workDir.resolve("extraction.json"), reviewDir.resolve("extraction.json"));
                FileStore.writeNew(reviewDir.resolve("pruefung.txt"), ("Status: REVIEW\nRun: " + correlation + "\n\n" + reason + "\n").getBytes(StandardCharsets.UTF_8));
                cleanupWorkDir();
            } catch (IOException e) {
                log.error("Ablage in manual-review/ fehlgeschlagen", e);
            }
            ledger.finishRun(run.id(), clock.instant(), RunResult.REVIEW);
            log.info("Run {} zur manuellen Prüfung: {}", runNumber, reason);
            return new RunOutcome(run.id(), runNumber, RunResult.REVIEW, reason);
        }

        private RunOutcome fail(String reason, InvoiceData data) {
            return fail(reason, data, List.of(), List.of(), Map.of(), null);
        }

        private RunOutcome fail(String reason, InvoiceData data, List<GeneratedArtifact> generated, List<ValidationReport> reports,
                                Map<ValidationReport, GeneratedArtifact> reportArtifact, PlausibilityResult plausibility) {
            if (!reason.startsWith("Validierung")) {
                event(EventType.PROCESSING_FAILED, reason, Map.of());
            }
            // Archiv und Ledger (best effort, unabhängig von der Ablage in failed/)
            try {
                LocalDate date = data == null ? LocalDate.now(clock) : data.date(BusinessTerm.BT_2).orElse(LocalDate.now(clock));
                String number = data == null ? null : data.value(BusinessTerm.BT_1).orElse(null);
                Path runDir = archive.createRunDirectory(date, number, job.sha256(), runNumber);
                registerArtifact(ArtifactType.SOURCE_PDF, archive.archiveCopy(runDir, "original.pdf", job.file()));
                if (workDir != null && Files.exists(workDir.resolve("extraction.json"))) {
                    registerArtifact(ArtifactType.EXTRACTION_LOG, archive.archiveCopy(runDir, "extraction.json", workDir.resolve("extraction.json")));
                }
                for (GeneratedArtifact g : generated) {
                    if (Files.exists(g.path())) {
                        registerArtifact(artifactType(g.format()), archive.archiveCopy(runDir, g.archiveName(), g.path()));
                    }
                }
                if (!reports.isEmpty()) {
                    recordValidation(runDir, reports, reportArtifact);
                }
            } catch (IOException | RuntimeException e) {
                log.error("Archivierung des fehlgeschlagenen Runs {} nicht möglich", runNumber, e);
            }
            try {
                if (data != null && plausibility != null) {
                    writeLedger(data, plausibility, generated.stream().map(g -> g.format().name()).toList());
                }
            } catch (RuntimeException e) {
                log.error("Ledger-Eintrag für fehlgeschlagenen Run {} nicht möglich", runNumber, e);
            }
            // Ablage in failed/ (Original darf nie in processing/ verbleiben)
            try {
                Path failedDir = Files.createDirectories(layout.failed(tenant).resolve(baseName));
                FileStore.moveNoOverwrite(job.file(), failedDir.resolve("original.pdf"));
                if (workDir != null && Files.isDirectory(workDir)) {
                    try (Stream<Path> files = Files.list(workDir)) {
                        for (Path f : files.filter(Files::isRegularFile).toList()) {
                            try {
                                FileStore.moveNoOverwrite(f, failedDir.resolve(f.getFileName().toString()));
                            } catch (IOException e) {
                                log.warn("Arbeitsdatei {} konnte nicht nach failed/ verschoben werden: {}", f.getFileName(), e.toString());
                            }
                        }
                    }
                }
                FileStore.writeNew(failedDir.resolve("fehler.txt"), ("Status: FAILED\nRun: " + correlation + "\n\n" + reason + "\n").getBytes(StandardCharsets.UTF_8));
                cleanupWorkDir();
            } catch (IOException e) {
                log.error("Ablage in failed/ fehlgeschlagen", e);
            }
            ledger.finishRun(run.id(), clock.instant(), RunResult.FAILED);
            log.warn("Run {} fehlgeschlagen: {}", runNumber, reason);
            return new RunOutcome(run.id(), runNumber, RunResult.FAILED, reason);
        }

        // ------------------------------------------------------------ Hilfsfunktionen

        private void writeReport(ValidationReport r) throws IOException {
            String name = reportBaseName(r);
            if (r.reportXml() != null) {
                FileStore.writeNew(workDir.resolve(name + ".xml"), r.reportXml());
            }
            if (r.reportHtml() != null) {
                FileStore.writeNew(workDir.resolve(name + ".html"), r.reportHtml());
            }
            if (r.reportXml() == null && r.message() != null) {
                FileStore.writeNew(workDir.resolve(name + ".txt"), r.message().getBytes(StandardCharsets.UTF_8));
            }
        }

        private static String reportBaseName(ValidationReport r) {
            return "validation-" + r.validator().name().toLowerCase(Locale.ROOT) + "-" + r.target().toLowerCase(Locale.ROOT);
        }

        private void recordValidation(Path runDir, List<ValidationReport> reports, Map<ValidationReport, GeneratedArtifact> reportArtifact) throws IOException {
            for (ValidationReport r : reports) {
                String name = reportBaseName(r);
                Long xmlId = null;
                Long htmlId = null;
                Path xml = workDir.resolve(name + ".xml");
                Path html = workDir.resolve(name + ".html");
                if (Files.exists(xml)) {
                    xmlId = registerArtifact(ArtifactType.VALIDATION_XML, archive.archiveCopy(runDir, name + ".xml", xml));
                }
                if (Files.exists(html)) {
                    htmlId = registerArtifact(ArtifactType.VALIDATION_HTML, archive.archiveCopy(runDir, name + ".html", html));
                }
                ledger.createValidationResult(new ValidationResultRow(0, run.id(), null, r.validator().name(), r.target(),
                        r.outcome().name(), r.ruleset(), (int) r.errorCount(), (int) r.warningCount(), xmlId, htmlId, r.validatedAt()));
            }
        }

        private long registerArtifact(ArtifactType type, StoredFile stored) {
            return ledger.createArtifact(run.id(), type, archive.relativize(stored.path()), stored.sha256(), stored.sizeBytes(), clock.instant()).id();
        }

        private void writeLedger(InvoiceData data, PlausibilityResult plausibility, List<String> formats) {
            String formatsJson;
            try {
                formatsJson = json.writeValueAsString(formats);
            } catch (JsonProcessingException e) {
                formatsJson = formats.toString();
            }
            LedgerEntryRow entry = new LedgerEntryRow(0, run.id(), tenant.id(), data.documentType().name(),
                    data.value(BusinessTerm.BT_1).orElse(null), data.value(BusinessTerm.BT_2).orElse(null),
                    data.value(BusinessTerm.BT_44).orElse(null), data.value(BusinessTerm.BT_5).orElse(profile.definition().generation().defaultCurrency()),
                    data.value(BusinessTerm.BT_109).orElse(null), data.value(BusinessTerm.BT_110).orElse(null),
                    data.value(BusinessTerm.BT_112).orElse(null), data.value(BusinessTerm.BT_115).orElse(null),
                    formatsJson, job.sha256(), profile.name(), profile.sha256(), version.value(), data.businessCaseId(),
                    data.value(BusinessTerm.BT_9).orElse(null), data.value(BusinessTerm.BT_72).orElse(null),
                    data.value(BusinessTerm.BT_48).orElse(null), data.value(BusinessTerm.BT_46).orElse(null), clock.instant());
            List<LedgerTaxLineRow> tax = plausibility.taxLines().stream()
                    .map(t -> new LedgerTaxLineRow(0, 0, t.categoryCode() == null ? "S" : t.categoryCode(), t.rate().toPlainString(),
                            t.taxableAmount().toPlainString(), t.taxAmount().toPlainString()))
                    .toList();
            ledger.createLedgerEntry(entry, tax);
        }

        private byte[] extractionReport(ClassificationResult classification, InvoiceData data, PlausibilityResult plausibility) throws IOException {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("correlationId", correlation.value());
            report.put("tenant", tenant.id());
            report.put("profile", profile.name());
            report.put("profileHash", profile.sha256());
            report.put("applicationVersion", version.value());
            report.put("sourceFile", job.originalFilename());
            report.put("sourceSha256", job.sha256());
            report.put("classification", Map.of("documentType", String.valueOf(classification.documentType()),
                    "businessCase", String.valueOf(data.businessCaseId()), "pageCount", classification.pageCount(),
                    "reasons", classification.reasons()));
            report.put("fields", data.fields().values());
            report.put("lines", data.lines());
            report.put("plausibility", Map.of("passed", plausibility.passed(), "issues", plausibility.issues(), "taxLines", plausibility.taxLines()));
            return json.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
        }

        private void event(EventType type, String message, Map<String, ?> details) {
            String detailsJson;
            try {
                detailsJson = details == null || details.isEmpty() ? null : json.writeValueAsString(details);
            } catch (JsonProcessingException e) {
                detailsJson = String.valueOf(details);
            }
            ledger.appendEvent(run.id(), type, clock.instant(), "system", message, detailsJson);
        }

        private void cleanupWorkDir() {
            if (workDir == null || !Files.isDirectory(workDir)) {
                return;
            }
            try (Stream<Path> files = Files.walk(workDir)) {
                files.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        log.warn("Arbeitsdatei konnte nicht gelöscht werden: {}", p);
                    }
                });
            } catch (IOException e) {
                log.warn("Arbeitsverzeichnis konnte nicht aufgeräumt werden: {}", workDir);
            }
        }
    }

    static ArtifactType artifactType(OutputFormat format) {
        return switch (format) {
            case XRECHNUNG_CII -> ArtifactType.XRECHNUNG_CII;
            case XRECHNUNG_UBL -> ArtifactType.XRECHNUNG_UBL;
            case ZUGFERD_EN16931, ZUGFERD_XRECHNUNG -> ArtifactType.ZUGFERD_PDF;
        };
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1;
        }
    }

    static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    static Instant nowUtc(Clock clock) {
        return clock.instant().atOffset(ZoneOffset.UTC).toInstant();
    }

    static Optional<ValidatorKind> kind(String s) {
        try {
            return Optional.of(ValidatorKind.valueOf(s));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
