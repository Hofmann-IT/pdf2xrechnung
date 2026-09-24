package de.hofmannit.erechnung.watcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.Rows.ValidationResultRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.ledger.RunTrigger;
import de.hofmannit.erechnung.ledger.StatusProjection;
import de.hofmannit.erechnung.security.Sha256;
import de.hofmannit.erechnung.testsupport.TestInvoicePdf;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-End (Vorgabe Phase 2 / Abschnitt 52): Test-PDF in inbox/ → Watcher → Extraktion →
 * Mapping → Plausibilität → Erzeugung → KoSIT/Mustang-Validierung → output/ → Archiv →
 * Ledger/Events. Dazu Duplikat, Plausibilitätsabweichung (REVIEW) und Nicht-Rechnung (REJECTED).
 *
 * <p>Alle Verzeichnisse liegen unter target/test-data (ADR 0006: Tests nie im echten Archiv).
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboundPipelineEndToEndTest {

    static final Path ROOT = createRoot();

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "e2e-").toAbsolutePath();
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
        r.add("app.watcher.enabled", () -> "true");
        r.add("app.watcher.poll-interval", () -> "300ms");
        r.add("app.watcher.stable-checks", () -> "1");
    }

    @Autowired
    LedgerRepository ledger;

    /** Dieselben Bytes für Test 1 und Test 2: jede PDF-Erzeugung liefert sonst eine neue Datei-ID und damit einen neuen Hash. */
    static byte[] standardInvoiceBytes;

    private static byte[] standardInvoice() throws Exception {
        if (standardInvoiceBytes == null) {
            Path tmp = ROOT.resolve("standard-invoice.pdf");
            TestInvoicePdf.standardInvoice().writeTo(tmp);
            standardInvoiceBytes = Files.readAllBytes(tmp);
        }
        return standardInvoiceBytes;
    }

    @Test
    @Order(1)
    void validInvoiceProducesAllFormatsLedgerAndArchive() throws Exception {
        Path pdf = ROOT.resolve("inbox").resolve("Rechnung RE-2026-4711.pdf");
        Files.createDirectories(pdf.getParent());
        Files.write(pdf, standardInvoice());
        String sha = Sha256.ofFile(pdf);

        ProcessingRunRow run = awaitFinishedRun(sha, 1);
        assertThat(run.result()).as(reason(run)).isEqualTo(RunResult.SUCCESS);
        assertThat(run.trigger()).isEqualTo(RunTrigger.AUTO);
        assertThat(run.profileName()).isEqualTo("standard");
        assertThat(run.profileHash()).hasSize(64);
        assertThat(run.correlationId()).isEqualTo(sha.substring(0, 8) + "/run-001");

        // output/: alle konfigurierten Formate unter Template-Namen
        try (Stream<Path> s = Files.list(ROOT.resolve("output"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder(
                    "RE-2026-4711_20260924_Beispiel_GmbH_xrechnung-cii.xml",
                    "RE-2026-4711_20260924_Beispiel_GmbH_xrechnung-ubl.xml",
                    "RE-2026-4711_20260924_Beispiel_GmbH_zugferd.pdf");
        }
        // inbox/ und processing/ sind leer, Original liegt unverändert im Archiv
        assertThat(Files.exists(pdf)).isFalse();
        Path runDir = ROOT.resolve("archive").resolve("2026").resolve("09").resolve("RE-2026-4711").resolve("run-001");
        assertThat(runDir).isDirectory();
        assertThat(Sha256.ofFile(runDir.resolve("original.pdf"))).isEqualTo(sha);
        try (Stream<Path> s = Files.list(runDir)) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertThat(names).contains("original.pdf", "invoice-cii.xml", "invoice-ubl.xml", "invoice-zugferd.pdf", "extraction.json",
                    "validation-kosit-xrechnung_cii.xml", "validation-kosit-xrechnung_cii.html",
                    "validation-kosit-xrechnung_ubl.xml", "validation-mustang-zugferd_en16931.xml");
        }
        // Artefakte mit Hash registriert
        assertThat(ledger.artifacts(run.id())).anySatisfy(a -> {
            assertThat(a.path()).endsWith("run-001/original.pdf");
            assertThat(a.sha256()).isEqualTo(sha);
        });
        assertThat(ledger.artifacts(run.id())).extracting(a -> a.type().name())
                .contains("SOURCE_PDF", "XRECHNUNG_CII", "XRECHNUNG_UBL", "ZUGFERD_PDF", "EXTRACTION_LOG", "VALIDATION_XML", "VALIDATION_HTML");

        // Validierungsergebnisse getrennt je Validator, verpflichtende alle VALID
        List<ValidationResultRow> results = ledger.validationResults(run.id());
        assertThat(results).extracting(v -> v.validator() + "/" + v.targetFormat() + "=" + v.outcome())
                .contains("KOSIT/XRECHNUNG_CII=VALID", "KOSIT/XRECHNUNG_UBL=VALID", "MUSTANG/ZUGFERD_EN16931=VALID");
        assertThat(results).allSatisfy(v -> assertThat(v.errorCount()).isZero());

        // Ledger
        LedgerEntryRow entry = ledger.ledgerEntry(run.id()).orElseThrow();
        assertThat(entry.invoiceNumber()).isEqualTo("RE-2026-4711");
        assertThat(entry.invoiceDate()).isEqualTo("2026-09-24");
        assertThat(entry.customerName()).isEqualTo("Beispiel GmbH");
        assertThat(entry.netTotal()).isEqualTo("1560.00");
        assertThat(entry.taxTotal()).isEqualTo("296.40");
        assertThat(entry.grossTotal()).isEqualTo("1856.40");
        assertThat(entry.payableAmount()).isEqualTo("1856.40");
        assertThat(entry.generatedFormats()).contains("XRECHNUNG_CII", "XRECHNUNG_UBL", "ZUGFERD_EN16931");
        assertThat(ledger.taxLines(entry.id())).singleElement().satisfies(t -> {
            assertThat(t.vatRate()).isEqualTo("19");
            assertThat(t.taxableAmount()).isEqualTo("1560.00");
            assertThat(t.taxAmount()).isEqualTo("296.40");
        });

        // Events und Status-Projektion
        List<EventType> types = ledger.events(run.id()).stream().map(e -> e.type()).toList();
        assertThat(types).containsExactly(EventType.PROCESSING_STARTED, EventType.EXTRACTION_COMPLETED, EventType.PLAUSIBILITY_PASSED,
                EventType.GENERATION_COMPLETED, EventType.VALIDATION_SUCCEEDED, EventType.ARCHIVED);
        assertThat(StatusProjection.derive(ledger.events(run.id()))).isEqualTo(InvoiceStatus.ARCHIVED);
    }

    @Test
    @Order(2)
    void sameSourceIsNeverProcessedTwice() throws Exception {
        Path pdf = ROOT.resolve("inbox").resolve("Rechnung RE-2026-4711 (Kopie).pdf");
        Files.write(pdf, standardInvoice());
        String sha = Sha256.ofFile(pdf);
        SourceDocumentRow source = ledger.findSource("hofmann-it", sha).orElseThrow();
        long runId = ledger.findLatestRun(source.id()).orElseThrow().id();

        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() ->
                ledger.events(runId).stream().anyMatch(e -> e.type() == EventType.DUPLICATE_DETECTED));
        assertThat(ledger.findRuns(source.id())).hasSize(1);
        assertThat(Files.exists(pdf)).isFalse();
        try (Stream<Path> s = Files.list(ROOT.resolve("rejected").resolve("duplikate"))) {
            assertThat(s.map(p -> p.getFileName().toString()).toList()).anyMatch(n -> n.endsWith("Kopie).pdf"));
        }
    }

    @Test
    @Order(3)
    void plausibilityMismatchGoesToManualReview() throws Exception {
        Path pdf = ROOT.resolve("inbox").resolve("Rechnung RE-2026-4712.pdf");
        TestInvoicePdf.standardInvoice().invoiceNumber("RE-2026-4712")
                .totals(new TestInvoicePdf.Totals("1.560,00", "1.560,00", "19", "296,40", "1.900,00", "1.900,00"))
                .writeTo(pdf);
        String sha = Sha256.ofFile(pdf);

        ProcessingRunRow run = awaitFinishedRun(sha, 1);
        assertThat(run.result()).isEqualTo(RunResult.REVIEW);
        Path reviewDir = ROOT.resolve("manual-review").resolve(sha.substring(0, 8) + "_Rechnung RE-2026-4712");
        assertThat(reviewDir.resolve("original.pdf")).exists();
        assertThat(reviewDir.resolve("extraction.json")).exists();
        assertThat(Files.readString(reviewDir.resolve("pruefung.txt"))).contains("GROSS_TOTAL").contains("REVIEW");
        LedgerEntryRow entry = ledger.ledgerEntry(run.id()).orElseThrow();
        assertThat(entry.invoiceNumber()).isEqualTo("RE-2026-4712");
        assertThat(entry.generatedFormats()).isEqualTo("[]");
        assertThat(StatusProjection.derive(ledger.events(run.id()))).isEqualTo(InvoiceStatus.REVIEW);
        try (Stream<Path> s = Files.list(ROOT.resolve("output"))) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.contains("4712"));
        }
    }

    @Test
    @Order(4)
    void nonInvoiceIsRejectedWithReason() throws Exception {
        Path pdf = ROOT.resolve("inbox").resolve("Angebot A-2026-0099.pdf");
        TestInvoicePdf.standardInvoice().title("Angebot").invoiceNumber("A-2026-0099").singlePage().writeTo(pdf);
        String sha = Sha256.ofFile(pdf);

        ProcessingRunRow run = awaitFinishedRun(sha, 1);
        assertThat(run.result()).isEqualTo(RunResult.REJECTED);
        Path rejected = ROOT.resolve("rejected").resolve(sha.substring(0, 8) + "_Angebot A-2026-0099.pdf");
        assertThat(rejected).exists();
        assertThat(Files.readString(rejected.resolveSibling(sha.substring(0, 8) + "_Angebot A-2026-0099.txt"))).contains("Nicht-Rechnung");
        assertThat(StatusProjection.derive(ledger.events(run.id()))).isEqualTo(InvoiceStatus.REJECTED);
        assertThat(ledger.ledgerEntry(run.id())).isEmpty();
    }

    @Autowired
    StartupRecovery recovery;

    @Test
    @Order(5)
    void restartRecoveryFinishesAbortedRunAndStartsRecoveryRun() throws Exception {
        // Simulierter Absturz: Datei liegt in processing/, Run 1 ist offen (kein finished_at).
        Path tmp = ROOT.resolve("recovery-invoice.pdf");
        TestInvoicePdf.standardInvoice().invoiceNumber("RE-2026-4713").writeTo(tmp);
        String sha = Sha256.ofFile(tmp);
        Path processing = ROOT.resolve("processing").resolve(sha.substring(0, 8) + "_Rechnung RE-2026-4713.pdf");
        Files.createDirectories(processing.getParent());
        Files.move(tmp, processing);
        SourceDocumentRow source = ledger.createSource("hofmann-it", sha, "Rechnung RE-2026-4713.pdf", Files.size(processing), java.time.Instant.now());
        ProcessingRunRow aborted = ledger.createRun(source.id(), 1, RunTrigger.AUTO, null, null, null, "standard", "0".repeat(64), "test",
                sha.substring(0, 8) + "/run-001", java.time.Instant.now());
        ledger.appendEvent(aborted.id(), EventType.PROCESSING_STARTED, java.time.Instant.now(), "system", "vor Absturz", null);

        assertThat(recovery.recover()).isEqualTo(1);

        ProcessingRunRow recovered = awaitFinishedRun(sha, 2);
        assertThat(recovered.trigger()).isEqualTo(RunTrigger.RESTART_RECOVERY);
        assertThat(recovered.parentRunId()).isEqualTo(aborted.id());
        assertThat(recovered.result()).as(reason(recovered)).isEqualTo(RunResult.SUCCESS);
        ProcessingRunRow first = ledger.findRun(aborted.id()).orElseThrow();
        assertThat(first.result()).isEqualTo(RunResult.FAILED);
        assertThat(ledger.events(first.id())).extracting(e -> e.type()).containsExactly(EventType.PROCESSING_STARTED, EventType.PROCESSING_FAILED);
        assertThat(ROOT.resolve("archive").resolve("2026").resolve("09").resolve("RE-2026-4713").resolve("run-002").resolve("original.pdf")).exists();
        assertThat(Files.exists(processing)).isFalse();
    }

    private ProcessingRunRow awaitFinishedRun(String sha, int runNumber) {
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).until(() ->
                ledger.findSource("hofmann-it", sha)
                        .flatMap(s -> ledger.findRuns(s.id()).stream().filter(r -> r.runNumber() == runNumber).findFirst())
                        .map(r -> r.finishedAt() != null).orElse(false));
        SourceDocumentRow source = ledger.findSource("hofmann-it", sha).orElseThrow();
        return ledger.findRuns(source.id()).stream().filter(r -> r.runNumber() == runNumber).findFirst().orElseThrow();
    }

    private String reason(ProcessingRunRow run) {
        return ledger.events(run.id()).stream().map(e -> e.type() + ": " + e.message()).toList().toString();
    }

    static Map<String, String> env() {
        return System.getenv();
    }
}
