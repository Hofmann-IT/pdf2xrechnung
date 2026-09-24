package de.hofmannit.erechnung.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import de.hofmannit.erechnung.cli.CalibrationService;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.ledger.RunTrigger;
import de.hofmannit.erechnung.ledger.StatusProjection;
import de.hofmannit.erechnung.security.Sha256;
import de.hofmannit.erechnung.testsupport.TestInvoicePdf;
import de.hofmannit.erechnung.watcher.ReprocessService;
import de.hofmannit.erechnung.watcher.ReprocessService.ReprocessException;
import de.hofmannit.erechnung.watcher.RunOutcome;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Phase 4 End-to-End: automatischer Versand an GreenMail, erneuter manueller Versand,
 * Reprocess mit Statusregeln (ohne erneuten Versand) und Kalibrierungs-CLI.
 */
@SpringBootTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DispatchAndReprocessEndToEndTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    static final Path ROOT = createRoot();

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            Path root = Files.createTempDirectory(base, "dispatch-").toAbsolutePath();
            // Profil mit aktiviertem Versand: Kopie des Standardprofils mit E-Mail-Block
            Path profiles = Files.createDirectories(root.resolve("profiles"));
            String yaml = Files.readString(Path.of("profiles", "standard.yaml"), StandardCharsets.UTF_8);
            String withMail = yaml
                    .replace("  email:\n    enabled: false\n    to: []", "  email:\n    enabled: true\n    to: [\"buchhaltung@example.invalid\"]")
                    .replace("    attachments:\n      - ZUGFERD_PDF", "    attachments:\n      - ZUGFERD_PDF\n      - XRECHNUNG_CII");
            if (withMail.equals(yaml)) {
                throw new IllegalStateException("Testprofil konnte nicht abgeleitet werden");
            }
            Files.writeString(profiles.resolve("standard.yaml"), withMail, StandardCharsets.UTF_8);
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        for (String d : List.of("inbox", "processing", "output", "failed", "manual-review", "rejected", "archive", "data", "inbound-validation")) {
            r.add("app.directories." + d, () -> ROOT.resolve(d).toString());
        }
        r.add("app.directories.profiles", () -> ROOT.resolve("profiles").toString());
        r.add("app.logging.directory", () -> ROOT.resolve("logs").toString());
        r.add("app.watcher.enabled", () -> "true");
        r.add("app.watcher.poll-interval", () -> "300ms");
        r.add("app.watcher.stable-checks", () -> "1");
        r.add("app.smtp.enabled", () -> "true");
        r.add("app.smtp.host", () -> "127.0.0.1");
        r.add("app.smtp.port", () -> String.valueOf(ServerSetupTest.SMTP.getPort()));
        r.add("app.smtp.starttls", () -> "false");
        r.add("app.smtp.ssl", () -> "false");
        r.add("app.smtp.auth", () -> "false");
        r.add("app.smtp.from", () -> "rechnung@example.invalid");
    }

    @Autowired LedgerRepository ledger;
    @Autowired PostProcessService postProcess;
    @Autowired ReprocessService reprocess;
    @Autowired CalibrationService calibration;

    static String sha;
    static long runId;

    @Test
    @Order(1)
    void successfulRunIsDispatchedAutomatically() throws Exception {
        Path pdf = ROOT.resolve("inbox").resolve("Rechnung RE-2026-4711.pdf");
        Files.createDirectories(pdf.getParent());
        TestInvoicePdf.standardInvoice().writeTo(pdf);
        sha = Sha256.ofFile(pdf);

        ProcessingRunRow run = awaitFinishedRun(sha, 1);
        runId = run.id();
        assertThat(run.result()).as(events(run.id()).toString()).isEqualTo(RunResult.SUCCESS);
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> greenMail.getReceivedMessages().length >= 1);

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        MimeMessage mail = received[0];
        assertThat(mail.getSubject()).isEqualTo("Rechnung RE-2026-4711 vom 24.09.2026");
        assertThat(mail.getAllRecipients()[0].toString()).isEqualTo("buchhaltung@example.invalid");
        assertThat(mail.getFrom()[0].toString()).isEqualTo("rechnung@example.invalid");
        List<String> attachmentNames = attachmentNames(mail);
        assertThat(attachmentNames).containsExactlyInAnyOrder("invoice-zugferd.pdf", "invoice-cii.xml");

        List<EventType> types = events(run.id());
        assertThat(types).containsSubsequence(EventType.ARCHIVED, EventType.DISPATCH_ATTEMPTED, EventType.DISPATCH_SUCCEEDED);
        assertThat(StatusProjection.derive(ledger.events(run.id()))).isEqualTo(InvoiceStatus.DISPATCHED);
        assertThat(ledger.events(run.id()).stream().filter(e -> e.type() == EventType.DISPATCH_SUCCEEDED).findFirst().orElseThrow().detailsJson())
                .contains("messageId").contains("buchhaltung@example.invalid");
    }

    @Test
    @Order(2)
    void manualRedispatchAppendsNewEventsAndNeverOverwrites() throws Exception {
        // GreenMail leert den Posteingang zwischen den Testmethoden; hier zählt nur der neue Versand.
        PostProcessService.DispatchOutcome outcome = postProcess.dispatchManually(runId, "uwe");
        assertThat(outcome.success()).isTrue();
        Awaitility.await().atMost(Duration.ofSeconds(30)).until(() -> greenMail.getReceivedMessages().length >= 1);
        assertThat(greenMail.getReceivedMessages()[0].getSubject()).isEqualTo("Rechnung RE-2026-4711 vom 24.09.2026");
        List<EventType> types = events(runId);
        assertThat(types.stream().filter(t -> t == EventType.DISPATCH_ATTEMPTED).count()).isEqualTo(2);
        assertThat(types.stream().filter(t -> t == EventType.DISPATCH_SUCCEEDED).count()).isEqualTo(2);
        assertThat(ledger.events(runId).get(ledger.events(runId).size() - 1).actor()).isEqualTo("uwe");
    }

    @Test
    @Order(3)
    void reprocessOfDispatchedInvoiceRequiresConfirmationAndNeverResends() throws Exception {
        int mailsBefore = greenMail.getReceivedMessages().length;

        assertThatThrownBy(() -> reprocess.reprocess("hofmann-it", sha.substring(0, 12), "uwe", "Profil geprüft", false))
                .isInstanceOf(ReprocessException.class)
                .matches(e -> ((ReprocessException) e).isConfirmationRequired())
                .hasMessageContaining("DISPATCHED");
        assertThatThrownBy(() -> reprocess.reprocess("hofmann-it", sha, "", "x", true)).isInstanceOf(ReprocessException.class);
        assertThatThrownBy(() -> reprocess.reprocess("hofmann-it", sha, "uwe", " ", true)).isInstanceOf(ReprocessException.class);

        RunOutcome outcome = reprocess.reprocess("hofmann-it", sha.substring(0, 12), "uwe", "Profil geprüft, technische Neuverarbeitung", true);
        assertThat(outcome.result()).as(outcome.message()).isEqualTo(RunResult.SUCCESS);
        assertThat(outcome.runNumber()).isEqualTo(2);

        SourceDocumentRow source = ledger.findSource("hofmann-it", sha).orElseThrow();
        List<ProcessingRunRow> runs = ledger.findRuns(source.id());
        assertThat(runs).hasSize(2);
        ProcessingRunRow second = runs.get(1);
        assertThat(second.trigger()).isEqualTo(RunTrigger.MANUAL_REPROCESS);
        assertThat(second.parentRunId()).isEqualTo(runId);
        assertThat(second.requestedBy()).isEqualTo("uwe");
        assertThat(second.reason()).contains("technische Neuverarbeitung");
        assertThat(second.profileHash()).hasSize(64);
        assertThat(second.applicationVersion()).isNotBlank();

        // Events: Anforderung am Vorgänger, Start/Abschluss am neuen Run, kein Versand
        assertThat(events(runId)).contains(EventType.REPROCESS_REQUESTED);
        List<EventType> secondTypes = events(second.id());
        assertThat(secondTypes).startsWith(EventType.REPROCESS_STARTED).contains(EventType.ARCHIVED, EventType.REPROCESS_COMPLETED);
        assertThat(secondTypes).doesNotContain(EventType.DISPATCH_ATTEMPTED, EventType.DISPATCH_SUCCEEDED);
        assertThat(greenMail.getReceivedMessages().length).as("Reprocess versendet nie automatisch").isEqualTo(mailsBefore);

        // Neue Artefakte, alte unverändert
        Path archiveDoc = ROOT.resolve("archive").resolve("2026").resolve("09").resolve("RE-2026-4711");
        assertThat(archiveDoc.resolve("run-001").resolve("invoice-cii.xml")).exists();
        assertThat(archiveDoc.resolve("run-002").resolve("invoice-cii.xml")).exists();
        try (Stream<Path> s = Files.list(ROOT.resolve("output"))) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertThat(names).contains("RE-2026-4711_20260924_Beispiel_GmbH_xrechnung-cii.xml",
                    "RE-2026-4711_20260924_Beispiel_GmbH_run-002_xrechnung-cii.xml");
        }
        assertThat(StatusProjection.derive(ledger.events(runId))).isEqualTo(InvoiceStatus.DISPATCHED);
        assertThat(StatusProjection.derive(ledger.events(second.id()))).isEqualTo(InvoiceStatus.ARCHIVED);
    }

    @Test
    @Order(4)
    void reprocessOfReviewRunNeedsNoConfirmation() throws Exception {
        Path pdf = ROOT.resolve("inbox").resolve("Rechnung RE-2026-4712.pdf");
        TestInvoicePdf.standardInvoice().invoiceNumber("RE-2026-4712")
                .totals(new TestInvoicePdf.Totals("1.560,00", "1.560,00", "19", "296,40", "1.900,00", "1.900,00"))
                .writeTo(pdf);
        String reviewSha = Sha256.ofFile(pdf);
        ProcessingRunRow first = awaitFinishedRun(reviewSha, 1);
        assertThat(first.result()).isEqualTo(RunResult.REVIEW);

        RunOutcome outcome = reprocess.reprocess("hofmann-it", reviewSha, "uwe", "erneuter Versuch nach Prüfung", false);
        assertThat(outcome.runNumber()).isEqualTo(2);
        assertThat(outcome.result()).isEqualTo(RunResult.REVIEW);
        assertThat(ledger.findRuns(ledger.findSource("hofmann-it", reviewSha).orElseThrow().id())).hasSize(2);
    }

    @Test
    @Order(5)
    void calibrationReportsEveryFieldWithoutSideEffects() throws Exception {
        Path samples = Files.createDirectories(ROOT.resolve("samples"));
        TestInvoicePdf.standardInvoice().invoiceNumber("RE-2026-9999").writeTo(samples.resolve("beispiel-1.pdf"));
        TestInvoicePdf.standardInvoice().title("Angebot").singlePage().writeTo(samples.resolve("beispiel-2-angebot.pdf"));
        int runsBefore = countRuns();

        String report = calibration.calibrate(samples, Path.of("profiles", "standard.yaml"), "hofmann-it");

        assertThat(report).contains("beispiel-1.pdf").contains("beispiel-2-angebot.pdf");
        assertThat(report).contains("BT-1 ").contains("gefunden").contains("RE-2026-9999").contains("fields[0]:BT-1");
        assertThat(report).contains("BT-86 ").contains("fehlt");
        assertThat(report).contains("Positionen: 3").contains("Plausibilität: bestanden");
        assertThat(report).contains("NICHT-RECHNUNG");
        assertThat(report).contains("Ergebnis: 1 von 2");
        assertThat(countRuns()).as("Kalibrierung erzeugt keinen Run").isEqualTo(runsBefore);
        assertThat(Files.exists(ROOT.resolve("archive").resolve("2026").resolve("09").resolve("RE-2026-9999"))).isFalse();
    }

    private int countRuns() {
        int n = 0;
        for (String s : List.of(sha)) {
            n += ledger.findSource("hofmann-it", s).map(src -> ledger.findRuns(src.id()).size()).orElse(0);
        }
        return n;
    }

    private static List<String> attachmentNames(MimeMessage mail) throws Exception {
        List<String> names = new ArrayList<>();
        Object content = mail.getContent();
        if (content instanceof Multipart mp) {
            collect(mp, names);
        }
        return names;
    }

    private static void collect(Multipart mp, List<String> names) throws Exception {
        for (int i = 0; i < mp.getCount(); i++) {
            BodyPart part = mp.getBodyPart(i);
            if (part.getFileName() != null) {
                names.add(part.getFileName());
            } else if (part.getContent() instanceof Multipart nested) {
                collect(nested, names);
            }
        }
    }

    private List<EventType> events(long id) {
        return ledger.events(id).stream().map(e -> e.type()).toList();
    }

    private ProcessingRunRow awaitFinishedRun(String sha, int runNumber) {
        Awaitility.await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofMillis(500)).until(() ->
                ledger.findSource("hofmann-it", sha)
                        .flatMap(s -> ledger.findRuns(s.id()).stream().filter(r -> r.runNumber() == runNumber).findFirst())
                        .map(r -> r.finishedAt() != null).orElse(false));
        SourceDocumentRow source = ledger.findSource("hofmann-it", sha).orElseThrow();
        return ledger.findRuns(source.id()).stream().filter(r -> r.runNumber() == runNumber).findFirst().orElseThrow();
    }
}
