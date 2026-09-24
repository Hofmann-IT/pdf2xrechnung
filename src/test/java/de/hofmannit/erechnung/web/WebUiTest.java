package de.hofmannit.erechnung.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.export.BelegtransferService;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.PdfTextExtractor;
import de.hofmannit.erechnung.generation.EInvoiceGenerator;
import de.hofmannit.erechnung.generation.GeneratedArtifact;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.ledger.RunTrigger;
import de.hofmannit.erechnung.mapping.ClassificationResult;
import de.hofmannit.erechnung.mapping.Classifier;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.MappingEngine;
import de.hofmannit.erechnung.model.ArtifactType;
import de.hofmannit.erechnung.model.OutputFormat;
import de.hofmannit.erechnung.security.Sha256;
import de.hofmannit.erechnung.testsupport.TestInvoicePdf;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Web-Oberfläche (Phase 5): alle Seiten werden serverseitig gerendert und mit MockMvc geprüft.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebUiTest {

    static final Path ROOT = createRoot();

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "web-").toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        for (String d : List.of("inbox", "processing", "output", "failed", "manual-review", "rejected", "archive", "data", "inbound-validation")) {
            r.add("app.directories." + d, () -> ROOT.resolve(d).toString());
        }
        r.add("app.logging.directory", () -> ROOT.resolve("logs").toString());
        r.add("app.watcher.enabled", () -> "false");
    }

    @Autowired MockMvc mvc;
    @Autowired LedgerRepository ledger;
    @Autowired ExportRepository exportRepository;
    @Autowired BelegtransferService belegtransfer;
    @Autowired PdfTextExtractor extractor;
    @Autowired Classifier classifier;
    @Autowired MappingEngine mappingEngine;
    @Autowired EInvoiceGenerator generator;
    @Autowired ProfileRegistry registry;

    static byte[] pdfBytes;
    static byte[] ciiBytes;
    static byte[] zugferdBytes;
    static long runId;
    static boolean prepared;

    @BeforeAll
    static void init() throws Exception {
        Files.createDirectories(ROOT);
    }

    private synchronized void prepare() throws Exception {
        if (prepared) {
            return;
        }
        Path work = Files.createDirectories(ROOT.resolve("fixtures"));
        Path pdf = work.resolve("source.pdf");
        TestInvoicePdf.standardInvoice().writeTo(pdf);
        pdfBytes = Files.readAllBytes(pdf);
        LoadedProfile profile = registry.profile("standard").orElseThrow();
        ExtractedDocument doc = extractor.extract(pdf);
        ClassificationResult c = classifier.classify(doc, profile.definition());
        InvoiceData data = mappingEngine.map(doc, profile.definition(), c, registry.tenant("hofmann-it").orElseThrow().fixedValues());
        for (GeneratedArtifact a : generator.generate(data, profile.definition(), pdf, work, "fixture")) {
            if (a.format() == OutputFormat.XRECHNUNG_CII) {
                ciiBytes = Files.readAllBytes(a.path());
            }
            if (a.format() == OutputFormat.ZUGFERD_EN16931 || a.format() == OutputFormat.ZUGFERD_XRECHNUNG) {
                zugferdBytes = Files.readAllBytes(a.path());
            }
        }
        // Verarbeiteter Run mit Ledger, Events und Artefakt im Archiv (ohne Watcher)
        String sha = Sha256.ofBytes(pdfBytes);
        SourceDocumentRow source = ledger.createSource("hofmann-it", sha, "Rechnung RE-2026-4711.pdf", pdfBytes.length, Instant.now());
        ProcessingRunRow run = ledger.createRun(source.id(), 1, RunTrigger.AUTO, null, null, null, "standard", profile.sha256(), "test",
                sha.substring(0, 8) + "/run-001", Instant.now());
        runId = run.id();
        for (EventType t : List.of(EventType.PROCESSING_STARTED, EventType.EXTRACTION_COMPLETED, EventType.PLAUSIBILITY_PASSED,
                EventType.GENERATION_COMPLETED, EventType.VALIDATION_SUCCEEDED, EventType.ARCHIVED)) {
            ledger.appendEvent(run.id(), t, Instant.now(), "system", t.name().toLowerCase(), "{\"k\":\"v\"}");
        }
        Path archiveDir = Files.createDirectories(ROOT.resolve("archive").resolve("2026").resolve("09").resolve("RE-2026-4711").resolve("run-001"));
        Files.write(archiveDir.resolve("original.pdf"), pdfBytes);
        Files.write(archiveDir.resolve("invoice-cii.xml"), ciiBytes);
        ledger.createArtifact(run.id(), ArtifactType.SOURCE_PDF, "2026/09/RE-2026-4711/run-001/original.pdf", sha, pdfBytes.length, Instant.now());
        ledger.createArtifact(run.id(), ArtifactType.XRECHNUNG_CII, "2026/09/RE-2026-4711/run-001/invoice-cii.xml", Sha256.ofBytes(ciiBytes), ciiBytes.length, Instant.now());
        Files.write(archiveDir.resolve("RE-2026-4711_zugferd.pdf"), zugferdBytes);
        ledger.createArtifact(run.id(), ArtifactType.ZUGFERD_PDF, "2026/09/RE-2026-4711/run-001/RE-2026-4711_zugferd.pdf", Sha256.ofBytes(zugferdBytes), zugferdBytes.length, Instant.now());
        ledger.createLedgerEntry(new LedgerEntryRow(0, run.id(), "hofmann-it", "INVOICE", "RE-2026-4711", "2026-09-24", "Beispiel GmbH", "EUR",
                "1560.00", "296.40", "1856.40", "1856.40", "[\"XRECHNUNG_CII\"]", sha, "standard", profile.sha256(), "test", "DOMESTIC_STANDARD",
                "2026-10-08", null, null, null, Instant.now()), List.of(new LedgerTaxLineRow(0, 0, "S", "19.00", "1560.00", "296.40")));
        ledger.finishRun(run.id(), Instant.now(), RunResult.SUCCESS);
        // Vorgang in manual-review/
        Path review = Files.createDirectories(ROOT.resolve("manual-review").resolve("abcdef12_Rechnung RE-2026-4712"));
        Files.write(review.resolve("original.pdf"), pdfBytes);
        Files.writeString(review.resolve("pruefung.txt"), "Status: REVIEW\nRun: abcdef12/run-001\n\nGROSS_TOTAL: BT-109 + BT-110 ≠ BT-112 (Abweichung 43.60)\n", StandardCharsets.UTF_8);
        Files.writeString(review.resolve("extraction.json"), """
                {"fields":[{"businessTerm":"BT_1","lineNumber":null,"value":"RE-2026-4712","ruleId":"fields[0]:BT-1","ruleType":"ANCHOR","page":1,
                 "boundingBox":{"x":450.0,"y":142.0,"width":60.0,"height":7.0},"sourceText":"RE-2026-4712","transformation":"anchor","status":"OK","message":null}],
                 "lines":[],"plausibility":{"passed":false,"issues":[{"check":"GROSS_TOTAL","message":"BT-109 + BT-110 ≠ BT-112","expected":"1856.40","actual":"1900.00"}],"taxLines":[]}}
                """, StandardCharsets.UTF_8);
        prepared = true;
    }

    @Test
    void dashboardRenders() throws Exception {
        prepare();
        String html = body(mvc.perform(get("/")).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("Dashboard").contains("Dateien in der Inbox").contains("Manuelle Prüfung").contains("RE-2026-4711")
                .contains("Umwandeln").contains("Validieren").contains("Archivieren").contains("Versenden");
        assertThat(html).contains("/css/app.css").contains("htmx.min.js");
    }

    @Test
    void invoiceListFiltersAndFragment() throws Exception {
        prepare();
        String html = body(mvc.perform(get("/rechnungen")).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("RE-2026-4711").contains("Beispiel GmbH").contains("1.856,40 EUR").contains("Archiviert").contains("XRechnung CII");

        String filtered = body(mvc.perform(get("/rechnungen").param("status", "REVIEW")).andExpect(status().isOk()).andReturn());
        assertThat(filtered).doesNotContain("RE-2026-4711").contains("Keine Einträge");

        String search = body(mvc.perform(get("/rechnungen").param("q", "Beispiel")).andExpect(status().isOk()).andReturn());
        assertThat(search).contains("RE-2026-4711");

        String fragment = body(mvc.perform(get("/rechnungen").header("HX-Request", "true")).andExpect(status().isOk()).andReturn());
        assertThat(fragment).contains("RE-2026-4711").doesNotContain("<html");
    }

    /** Läuft vor dem Einstellungstest, weil dieser Einstellungen dauerhaft (append-only) speichert. */
    @Test
    @Order(1)
    void exportPagePreviewAndCsvDownload() throws Exception {
        prepare();
        String page = body(mvc.perform(get("/rechnungen/export")).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("Export Rechnungsausgangsbuch").contains("Rechnungsausgangsbuch (CSV)")
                .doesNotContain("DATEV-Format Buchungsstapel (EXTF)")   // in config/tenant.yaml deaktiviert
                .contains("kein DATEV-Buchungsstapel konfiguriert");

        String preview = body(mvc.perform(get("/rechnungen/export/vorschau").param("tenant", "hofmann-it").param("variant", "CSV")
                .param("user", "uwe").header("HX-Request", "true")).andExpect(status().isOk()).andReturn());
        assertThat(preview).contains("rechnungsausgangsbuch_hofmann-it_anfang_ende.csv").contains("Herunterladen").contains("user=uwe").doesNotContain("<html");
        assertThat(exportRepository.exportLog("hofmann-it", 10)).as("Vorschau wird nicht protokolliert").isEmpty();

        String refused = body(mvc.perform(get("/rechnungen/export/vorschau").param("tenant", "hofmann-it").param("variant", "DATEV_BUCHUNGSSTAPEL")
                .param("von", "2026-09-01").param("bis", "2026-09-30").param("user", "uwe")).andExpect(status().isOk()).andReturn());
        assertThat(refused).contains("nicht aktiviert");

        // Download ohne Benutzer wird abgewiesen (Parameter fehlt)
        mvc.perform(get("/rechnungen/export/download").param("tenant", "hofmann-it").param("variant", "CSV")).andExpect(status().is4xxClientError());

        MvcResult download = mvc.perform(get("/rechnungen/export/download").param("tenant", "hofmann-it").param("variant", "CSV").param("user", "uwe"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"rechnungsausgangsbuch_hofmann-it_anfang_ende.csv\""))
                .andReturn();
        String csv = new String(download.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("﻿Mandant;Rechnungsnummer").contains("\"RE-2026-4711\"").contains("\"Beispiel GmbH\"").contains("\"1856,40\"")
                .contains(";Fälligkeit;").contains("\"08.10.2026\"");
        assertThat(download.getResponse().getHeader("X-Content-SHA256")).isEqualTo(Sha256.ofBytes(download.getResponse().getContentAsByteArray()));

        // Exportprotokoll (V2) und Anzeige auf der Exportseite
        var log = exportRepository.exportLog("hofmann-it", 10);
        assertThat(log).hasSize(1);
        assertThat(log.get(0).createdBy()).isEqualTo("uwe");
        assertThat(log.get(0).sha256()).isEqualTo(download.getResponse().getHeader("X-Content-SHA256"));
        assertThat(log.get(0).variant()).isEqualTo("CSV");
        String pageAfter = body(mvc.perform(get("/rechnungen/export")).andExpect(status().isOk()).andReturn());
        assertThat(pageAfter).contains("Exportprotokoll").contains("rechnungsausgangsbuch_hofmann-it_anfang_ende.csv").contains("aus config/tenant.yaml");
    }

    @Test
    @Order(2)
    void exportSettingsPageSavesValidatedHistory() throws Exception {
        prepare();
        String page = body(mvc.perform(get("/rechnungen/export/einstellungen").param("tenant", "hofmann-it")).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("Export-Einstellungen").contains("noch nie in der Oberfläche gespeichert")
                .contains("DOMESTIC_STANDARD:19 = 8400").contains("name=\"consultantNumber\"");

        // Ungültig (Beraternummer zu kurz): Formular kommt mit Meldung und Eingaben zurück, nichts gespeichert
        String invalid = body(mvc.perform(post("/rechnungen/export/einstellungen").param("tenant", "hofmann-it").param("user", "uwe")
                .param("enabled", "on").param("consultantNumber", "12").param("clientNumber", "7").param("fiscalYearStart", "01-01")
                .param("accountLength", "4").param("chartOfAccounts", "04").param("debtorStrategy", "COLLECTIVE")
                .param("collectiveDebtorAccount", "10000").param("revenueAccounts", "DOMESTIC_STANDARD:19 = 4400")
                .param("lockRecords", "on").param("bookingTextTemplate", "Rechnung {invoiceNumber}")).andExpect(status().isOk()).andReturn());
        assertThat(invalid).contains("Beraternummer").contains("value=\"12\"").contains("DOMESTIC_STANDARD:19 = 4400");
        assertThat(exportRepository.settingsHistory("hofmann-it")).isEmpty();

        // Zuordnungszeile ohne '=' wird gemeldet
        String badLine = body(mvc.perform(post("/rechnungen/export/einstellungen").param("tenant", "hofmann-it").param("user", "uwe")
                .param("enabled", "on").param("consultantNumber", "29098").param("clientNumber", "7").param("accountLength", "4")
                .param("chartOfAccounts", "04").param("collectiveDebtorAccount", "10000").param("revenueAccounts", "DOMESTIC_STANDARD:19 4400")
                .param("bookingTextTemplate", "x")).andExpect(status().isOk()).andReturn());
        assertThat(badLine).contains("Schlüssel = Konto");

        // Gültig: SKR04 mit Konto je Kunde und BU-Schlüssel → Redirect, Historie, Export nutzt die neuen Werte
        mvc.perform(post("/rechnungen/export/einstellungen").param("tenant", "hofmann-it").param("user", "uwe").param("note", "laut StB")
                .param("enabled", "on").param("consultantNumber", "29098").param("clientNumber", "55003").param("fiscalYearStart", "01-01")
                .param("accountLength", "4").param("chartOfAccounts", "04").param("debtorStrategy", "PER_CUSTOMER")
                .param("customerAccounts", "Beispiel GmbH = 10001\n# Kommentar\n")
                .param("revenueAccounts", "DOMESTIC_STANDARD:19 = 4400\nEU_REVERSE_CHARGE = 4336 ; 0094\n")
                .param("origin", "RE").param("exportedBy", "Test").param("lockRecords", "on").param("bookingTextTemplate", "Rechnung {invoiceNumber}"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/rechnungen/export/einstellungen?tenant=hofmann-it"));
        var history = exportRepository.settingsHistory("hofmann-it");
        assertThat(history).hasSize(1);
        assertThat(history.get(0).createdBy()).isEqualTo("uwe");
        assertThat(history.get(0).chartOfAccounts()).isEqualTo("04");
        assertThat(history.get(0).customerAccountsJson()).contains("\"Beispiel GmbH\":\"10001\"");
        assertThat(history.get(0).revenueAccountsJson()).contains("\"buKey\":\"0094\"");

        String after = body(mvc.perform(get("/rechnungen/export/einstellungen").param("tenant", "hofmann-it")).andExpect(status().isOk()).andReturn());
        assertThat(after).contains("in der Oberfläche gespeicherte Einstellungen").contains("EU_REVERSE_CHARGE = 4336 ; 0094").contains("laut StB");

        String exportPage = body(mvc.perform(get("/rechnungen/export").param("tenant", "hofmann-it")).andExpect(status().isOk()).andReturn());
        assertThat(exportPage).contains("DATEV-Format Buchungsstapel (EXTF)").contains("aus der Datenbank");
        MvcResult datev = mvc.perform(get("/rechnungen/export/download").param("tenant", "hofmann-it").param("variant", "DATEV_BUCHUNGSSTAPEL")
                .param("von", "2026-09-01").param("bis", "2026-09-30").param("user", "uwe")).andExpect(status().isOk()).andReturn();
        String extf = new String(datev.getResponse().getContentAsByteArray(), java.nio.charset.Charset.forName("windows-1252"));
        assertThat(extf).startsWith("\"EXTF\";700;21;\"Buchungsstapel\";13;").contains(";29098;55003;").contains(";10001;4400;");
    }

    @Test
    @Order(3)
    void belegtransferCopiesZugferdWithoutOverwriting() throws Exception {
        prepare();
        // Nicht aktiviert (Einstellungen aus Order(2) ohne Belegtransfer): manuelle Übergabe wird abgelehnt
        mvc.perform(post("/rechnungen/" + runId + "/belegtransfer").param("user", "uwe"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(belegtransfer.afterSuccessfulRun(runId)).isEmpty();
        assertThat(exportRepository.transfers(runId)).isEmpty();

        // UNC-Pfad wird abgewiesen
        String unc = body(mvc.perform(post("/rechnungen/export/einstellungen").param("tenant", "hofmann-it").param("user", "uwe")
                .param("consultantNumber", "29098").param("clientNumber", "55003").param("accountLength", "4").param("chartOfAccounts", "04")
                .param("collectiveDebtorAccount", "10000").param("revenueAccounts", "DOMESTIC_STANDARD:19 = 4400").param("bookingTextTemplate", "x")
                .param("belegtransferEnabled", "on").param("belegtransferDirectory", "\\\\server\\datev\\belege")).andExpect(status().isOk()).andReturn());
        assertThat(unc).contains("Netzwerkpfad");

        Path dir = Files.createDirectories(ROOT.resolve("belegtransfer"));
        mvc.perform(post("/rechnungen/export/einstellungen").param("tenant", "hofmann-it").param("user", "uwe").param("note", "Belegtransfer an")
                .param("enabled", "on").param("consultantNumber", "29098").param("clientNumber", "55003").param("accountLength", "4")
                .param("chartOfAccounts", "04").param("debtorStrategy", "COLLECTIVE").param("collectiveDebtorAccount", "10000")
                .param("revenueAccounts", "DOMESTIC_STANDARD:19 = 4400").param("lockRecords", "on").param("bookingTextTemplate", "Rechnung {invoiceNumber}")
                .param("belegtransferEnabled", "on").param("belegtransferDirectory", dir.toString()))
                .andExpect(status().is3xxRedirection());
        String settingsPage = body(mvc.perform(get("/rechnungen/export/einstellungen").param("tenant", "hofmann-it")).andReturn());
        assertThat(settingsPage).contains("name=\"belegtransferEnabled\" checked").contains(dir.toString().replace("\\", "\\"));

        // Erste Übergabe kopiert, zweite erkennt die identische Datei
        mvc.perform(post("/rechnungen/" + runId + "/belegtransfer").param("user", "uwe"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        Path copied = dir.resolve("RE-2026-4711_zugferd.pdf");
        assertThat(copied).exists();
        assertThat(Sha256.ofFile(copied)).isEqualTo(Sha256.ofBytes(zugferdBytes));
        assertThat(Files.list(dir).filter(p -> p.getFileName().toString().endsWith(".part")).count()).isZero();
        assertThat(belegtransfer.afterSuccessfulRun(runId)).hasValueSatisfying(t -> assertThat(t.outcome()).isEqualTo("SKIPPED"));
        var transfers = exportRepository.transfers(runId);
        assertThat(transfers).hasSize(2);
        assertThat(transfers.get(0).outcome()).isEqualTo("COPIED");
        assertThat(transfers.get(0).actor()).isEqualTo("uwe");
        assertThat(transfers.get(1).actor()).isEqualTo("system");

        // Zieldatei mit anderem Inhalt: nie überschreiben, eindeutiger Name mit Hash-Präfix
        Files.write(copied, "fremder Inhalt".getBytes(StandardCharsets.UTF_8));
        var third = belegtransfer.transferManually(runId, "uwe");
        assertThat(third.outcome()).isEqualTo("COPIED");
        assertThat(third.targetPath()).endsWith("_" + Sha256.ofBytes(zugferdBytes).substring(0, 8) + ".pdf");
        assertThat(Path.of(third.targetPath())).exists();
        assertThat(Files.readString(copied, StandardCharsets.UTF_8)).isEqualTo("fremder Inhalt");

        // Verzeichnis nicht vorhanden: FAILED, nichts geworfen, Run und Status unverändert
        mvc.perform(post("/rechnungen/export/einstellungen").param("tenant", "hofmann-it").param("user", "uwe")
                .param("enabled", "on").param("consultantNumber", "29098").param("clientNumber", "55003").param("accountLength", "4")
                .param("chartOfAccounts", "04").param("collectiveDebtorAccount", "10000").param("revenueAccounts", "DOMESTIC_STANDARD:19 = 4400")
                .param("bookingTextTemplate", "x").param("belegtransferEnabled", "on").param("belegtransferDirectory", ROOT.resolve("gibt-es-nicht").toString()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/rechnungen/" + runId + "/belegtransfer").param("user", "uwe"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(exportRepository.transfers(runId)).hasSize(4);
        assertThat(exportRepository.transfers(runId).get(3).outcome()).isEqualTo("FAILED");
        assertThat(ledger.findInvoice(runId).orElseThrow().status()).isEqualTo(InvoiceStatus.ARCHIVED);

        String detail = body(mvc.perform(get("/rechnungen/" + runId)).andExpect(status().isOk()).andReturn());
        assertThat(detail).contains("DATEV Belegtransfer").contains("COPIED").contains("FAILED").contains("An Belegtransfer übergeben");
    }

    @Test
    void invoiceExportFieldsAreSavedPerSourceDocument() throws Exception {
        prepare();
        String before = body(mvc.perform(get("/rechnungen/" + runId)).andExpect(status().isOk()).andReturn());
        assertThat(before).contains("DATEV-Zusatzfelder").contains("08.10.2026");

        // #115 ohne #116 wird abgelehnt
        mvc.perform(post("/rechnungen/" + runId + "/export-felder").param("user", "uwe").param("serviceDate", "2026-09-15"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(exportRepository.invoiceFieldHistory(sourceId())).isEmpty();

        mvc.perform(post("/rechnungen/" + runId + "/export-felder").param("user", "uwe").param("serviceDate", "2026-09-15")
                .param("taxPeriodDate", "2026-09-15").param("buyerVatId", "de 133546770").param("note", "StB"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        var fields = exportRepository.latestInvoiceFields(sourceId()).orElseThrow();
        assertThat(fields.serviceDate()).isEqualTo("2026-09-15");
        assertThat(fields.buyerVatId()).isEqualTo("DE133546770");
        String after = body(mvc.perform(get("/rechnungen/" + runId)).andExpect(status().isOk()).andReturn());
        assertThat(after).contains("15.09.2026").contains("DE133546770").contains("Historie (1)");
    }

    private long sourceId() {
        return ledger.findInvoice(runId).orElseThrow().source().id();
    }

    @Test
    void invoiceDetailShowsTimelineExtractionArtifactsAndHistory() throws Exception {
        prepare();
        String html = body(mvc.perform(get("/rechnungen/" + runId)).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("RE-2026-4711").contains("Archiviert").contains("Source SHA-256").contains("Profil-Hash")
                .contains("Erkannt").contains("Extrahiert").contains("Plausibilisiert").contains("Erzeugt").contains("Validiert")
                .contains("original.pdf").contains("invoice-cii.xml").contains("PROCESSING_STARTED").contains("Reprocess");

        var artifacts = ledger.artifacts(runId);
        MvcResult download = mvc.perform(get("/rechnungen/" + runId + "/artefakt/" + artifacts.get(0).id()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"original.pdf\""))
                .andReturn();
        assertThat(download.getResponse().getContentAsByteArray()).isEqualTo(pdfBytes);

        mvc.perform(get("/rechnungen/999999")).andExpect(status().isNotFound());
        String notFound = body(mvc.perform(get("/rechnungen/999999")).andReturn());
        assertThat(notFound).contains("Nicht gefunden");
    }

    @Test
    void reprocessOfArchivedInvoiceRequiresConfirmationInUi() throws Exception {
        prepare();
        MvcResult r = mvc.perform(post("/rechnungen/" + runId + "/reprocess").param("user", "uwe").param("reason", "Test"))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(r.getFlashMap().get("error").toString()).contains("Bestätigung");
        assertThat(ledger.findRuns(ledger.findInvoice(runId).orElseThrow().source().id())).hasSize(1);
    }

    @Test
    void inboundValidationPageAndUpload() throws Exception {
        prepare();
        String page = body(mvc.perform(get("/pruefen")).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("E-Rechnung hier ablegen oder Datei auswählen").contains("verlässt dieses System nicht");

        MockMultipartFile file = new MockMultipartFile("file", "eingang.xml", "application/xml", ciiBytes);
        String result = body(mvc.perform(multipart("/pruefen").file(file)).andExpect(status().isOk()).andReturn());
        assertThat(result).contains("E-Rechnung gültig").contains("✓").contains("XRechnung").contains("KOSIT").contains("MUSTANG")
                .contains("SHA-256").contains(Sha256.ofBytes(ciiBytes));
        String token = result.substring(result.indexOf("/pruefen/") + 9, result.indexOf("/report.html"));
        String report = body(mvc.perform(get("/pruefen/" + token + "/report.html")).andExpect(status().isOk()).andReturn());
        assertThat(report).contains("E-Rechnung gültig");
        mvc.perform(get("/pruefen/" + token + "/report-kosit.xml")).andExpect(status().isOk());
        mvc.perform(get("/pruefen/" + token + "/zusammenfassung.txt")).andExpect(status().isOk());

        MockMultipartFile broken = new MockMultipartFile("file", "kaputt.xml", "application/xml", "<Invoice>".getBytes(StandardCharsets.UTF_8));
        String bad = body(mvc.perform(multipart("/pruefen").file(broken).header("HX-Request", "true")).andExpect(status().isOk()).andReturn());
        assertThat(bad).contains("E-Rechnung nicht prüfbar").doesNotContain("<html");
    }

    @Test
    void reviewPagesShowReasonExtractionAndPdf() throws Exception {
        prepare();
        String list = body(mvc.perform(get("/pruefung")).andExpect(status().isOk()).andReturn());
        assertThat(list).contains("abcdef12_Rechnung RE-2026-4712").contains("GROSS_TOTAL");
        String detail = body(mvc.perform(get("/pruefung/hofmann-it/abcdef12_Rechnung RE-2026-4712")).andExpect(status().isOk()).andReturn());
        assertThat(detail).contains("Plausibilitätsfehler").contains("RE-2026-4712").contains("x=450").contains("original.pdf");
        mvc.perform(get("/pruefung/hofmann-it/abcdef12_Rechnung RE-2026-4712/original.pdf")).andExpect(status().isOk());
        mvc.perform(get("/pruefung/hofmann-it/..")).andExpect(status().isNotFound());
    }

    @Test
    void profilePagesAndProfileTest() throws Exception {
        prepare();
        String page = body(mvc.perform(get("/profile")).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("standard").contains("Profil testen").contains("DOMESTIC_STANDARD");
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf", "application/pdf", pdfBytes);
        String result = body(mvc.perform(multipart("/profile/test").file(file).param("tenant", "hofmann-it").header("HX-Request", "true"))
                .andExpect(status().isOk()).andReturn());
        assertThat(result).contains("RE-2026-4711").contains("bestanden").contains("BT-1").contains("fields[0]:BT-1").contains("Positionen (3)");
        assertThat(ledger.countOpenRuns()).isZero();
    }

    @Test
    void statusPage() throws Exception {
        prepare();
        String html = body(mvc.perform(get("/status")).andExpect(status().isOk()).andReturn());
        assertThat(html).contains("Systemstatus").contains("KoSIT").contains("inbound-validation").contains("hofmann-it").contains("SMTP");
    }

    /** Legt gerenderte Seiten als statische HTML-Schnappschüsse unter target/ui-snapshots ab (Sichtkontrolle). */
    @Test
    void writeUiSnapshots() throws Exception {
        prepare();
        Path out = Files.createDirectories(Path.of("target", "ui-snapshots"));
        Files.copy(Path.of("src", "main", "resources", "static", "css", "app.css"), out.resolve("app.css"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        snapshot(out, "dashboard.html", body(mvc.perform(get("/")).andReturn()));
        snapshot(out, "rechnungen.html", body(mvc.perform(get("/rechnungen")).andReturn()));
        snapshot(out, "rechnung-detail.html", body(mvc.perform(get("/rechnungen/" + runId)).andReturn()));
        snapshot(out, "export.html", body(mvc.perform(get("/rechnungen/export/vorschau").param("tenant", "hofmann-it").param("variant", "CSV").param("user", "uwe")).andReturn()));
        snapshot(out, "export-einstellungen.html", body(mvc.perform(get("/rechnungen/export/einstellungen").param("tenant", "hofmann-it")).andReturn()));
        snapshot(out, "pruefen.html", body(mvc.perform(get("/pruefen")).andReturn()));
        MockMultipartFile file = new MockMultipartFile("file", "eingang.xml", "application/xml", ciiBytes);
        snapshot(out, "pruefen-ergebnis.html", body(mvc.perform(multipart("/pruefen").file(file)).andReturn()));
        snapshot(out, "pruefung.html", body(mvc.perform(get("/pruefung")).andReturn()));
        snapshot(out, "pruefung-detail.html", body(mvc.perform(get("/pruefung/hofmann-it/abcdef12_Rechnung RE-2026-4712")).andReturn()));
        snapshot(out, "profile.html", body(mvc.perform(get("/profile")).andReturn()));
        snapshot(out, "status.html", body(mvc.perform(get("/status")).andReturn()));
        assertThat(out.resolve("dashboard.html")).exists();
    }

    private static void snapshot(Path dir, String name, String html) throws IOException {
        Files.writeString(dir.resolve(name), html.replace("/css/app.css", "app.css"), StandardCharsets.UTF_8);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
