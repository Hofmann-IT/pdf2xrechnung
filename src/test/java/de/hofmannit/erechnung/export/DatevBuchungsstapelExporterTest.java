package de.hofmannit.erechnung.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import de.hofmannit.erechnung.configuration.TenantProperties;
import de.hofmannit.erechnung.configuration.TenantProperties.Datev;
import de.hofmannit.erechnung.configuration.TenantProperties.DebtorStrategy;
import de.hofmannit.erechnung.configuration.TenantProperties.RevenueAccount;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.export.ExportService.Variant;
import de.hofmannit.erechnung.ledger.EventType;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.ledger.Rows.ProcessingRunRow;
import de.hofmannit.erechnung.ledger.Rows.SourceDocumentRow;
import de.hofmannit.erechnung.ledger.RunResult;
import de.hofmannit.erechnung.ledger.RunTrigger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Export gegen ein Ledger mit Rechnung, Gutschrift, Reprocess und REVIEW-Run. Die erzeugte
 * DATEV-Datei wird Feld für Feld gegen die regulären Ausdrücke der DATEV-Dokumentation geprüft
 * (docs/datev-format-referenz.md).
 */
@SpringBootTest
class DatevBuchungsstapelExporterTest {

    static final Path ROOT = createRoot();

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "export-").toAbsolutePath();
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
        // Testmandant mit DATEV-Konfiguration (eine indexierte Liste wird vollständig aus der
        // höchstrangigen Quelle gebunden, daher alle Pflichtfelder hier)
        r.add("tenants[0].id", () -> "hofmann-it");
        r.add("tenants[0].name", () -> "Hofmann IT (Test)");
        r.add("tenants[0].enabled", () -> "true");
        r.add("tenants[0].profiles[0]", () -> "standard");
        r.add("tenants[0].export.datev.enabled", () -> "true");
        r.add("tenants[0].export.datev.consultant-number", () -> "29098");
        r.add("tenants[0].export.datev.client-number", () -> "55003");
        r.add("tenants[0].export.datev.fiscal-year-start", () -> "01-01");
        r.add("tenants[0].export.datev.account-length", () -> "4");
        r.add("tenants[0].export.datev.chart-of-accounts", () -> "03");
        r.add("tenants[0].export.datev.debtor-strategy", () -> "COLLECTIVE");
        r.add("tenants[0].export.datev.collective-debtor-account", () -> "10000");
        r.add("tenants[0].export.datev.revenue-accounts.[DOMESTIC_STANDARD:19].account", () -> "8400");
        r.add("tenants[0].export.datev.revenue-accounts.[DOMESTIC_STANDARD:7].account", () -> "8300");
        r.add("tenants[0].export.datev.revenue-accounts.[EU_REVERSE_CHARGE].account", () -> "8336");
        r.add("tenants[0].export.datev.revenue-accounts.[EU_REVERSE_CHARGE].bu-key", () -> "0094");
        r.add("tenants[0].export.datev.dictation-shortcut", () -> "UH");
        r.add("tenants[0].export.datev.exported-by", () -> "Test");
    }

    @Autowired LedgerRepository ledger;
    @Autowired ExportRepository exportRepository;
    @Autowired ExportService exports;
    @Autowired ExportSettingsService settingsService;
    @Autowired ProfileRegistry registry;

    // Regeln aus der DATEV-Dokumentation (Kopfzeile)
    static final Pattern[] HEADER_RULES = {
            Pattern.compile("^\"(EXTF|DTVF)\"$"), Pattern.compile("^(700)$"), Pattern.compile("^(16|20|21|46|48|65)$"),
            Pattern.compile("^\"(Buchungsstapel|Wiederkehrende Buchungen|Debitoren/Kreditoren|Kontenbeschriftungen|Zahlungsbedingungen|Diverse Adressen)\"$"),
            Pattern.compile("^(2|4|5|13)$"),
            Pattern.compile("^([2])([0])([0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[1-2][0-9]|3[0-1])(2[0-3]|[01][0-9])([0-5][0-9])([0-5][0-9][0-9][0-9][0-9])$"),
            Pattern.compile("^$"), Pattern.compile("^\"\\w{0,2}\"$"), Pattern.compile("^\"\\w{0,25}\"$"), Pattern.compile("^\"\\w{0,25}\"$"),
            Pattern.compile("^(\\d{4,6}|\\d{7})$"), Pattern.compile("^\\d{1,5}$"),
            Pattern.compile("^([2])([0])([0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[1-2][0-9]|3[0-1])$"), Pattern.compile("^[4-8]$"),
            Pattern.compile("^([2])([0])([0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[1-2][0-9]|3[0-1])$"),
            Pattern.compile("^([2])([0])([0-9]{2})(0[1-9]|1[0-2])(0[1-9]|[1-2][0-9]|3[0-1])$"),
            Pattern.compile("^\"[\\w.\\-/ ]{0,30}\"$"), Pattern.compile("^\"([A-Z]{2}){0,2}\"$"), Pattern.compile("^[1-2]$"),
            Pattern.compile("^(0|30|40|50|64)$"), Pattern.compile("^(0|1)$"), Pattern.compile("^\"([A-Z]{3})\"$"), Pattern.compile("^$"),
            Pattern.compile("^\"\"$"), Pattern.compile("^$"), Pattern.compile("^$"), Pattern.compile("^\"(\\d{2}){0,2}\"$"),
            Pattern.compile("^\\d{0,4}$"), Pattern.compile("^$"), Pattern.compile("^\"\"$"), Pattern.compile("^\".{0,16}\"$")};

    // JUnit erzeugt je Testmethode eine neue Instanz; das Ledger wird nur einmal befüllt.
    private static boolean seeded;

    private void seed() {
        if (seeded) {
            return;
        }
        seeded = true;
        String sha = "1".repeat(64);
        String reviewSha = "2".repeat(64);
        // Rechnung, Run 1 erfolgreich, danach Reprocess Run 2 erfolgreich → nur Run 2 wird exportiert
        SourceDocumentRow s1 = ledger.createSource("hofmann-it", sha, "RE-1.pdf", 10, Instant.now());
        ProcessingRunRow r1 = run(s1.id(), 1, null);
        entry(r1, "INVOICE", "RE/2026-0001", "2026-09-24", "Beispiel GmbH", "1560.00", "296.40", "1856.40", "DOMESTIC_STANDARD",
                List.of(tax("S", "19", "1560.00", "296.40")));
        ProcessingRunRow r2 = run(s1.id(), 2, r1.id());
        entry(r2, "INVOICE", "RE/2026-0001 A", "2026-09-24", "Beispiel GmbH", "1560.00", "296.40", "1856.40", "DOMESTIC_STANDARD",
                List.of(tax("S", "19", "1560.00", "296.40")));
        // Gutschrift mit zwei Steuersätzen
        SourceDocumentRow s2 = ledger.createSource("hofmann-it", "3".repeat(64), "GS-1.pdf", 10, Instant.now());
        ProcessingRunRow r3 = run(s2.id(), 1, null);
        entry(r3, "CREDIT_NOTE", "GS-2026-0002", "2026-09-25", "Muster AG", "300.00", "45.60", "345.60", "DOMESTIC_STANDARD",
                List.of(tax("S", "19", "200.00", "38.00"), tax("S", "7", "100.00", "7.00")));
        // Reverse Charge
        SourceDocumentRow s3 = ledger.createSource("hofmann-it", "4".repeat(64), "RE-3.pdf", 10, Instant.now());
        ProcessingRunRow r4 = run(s3.id(), 1, null);
        // USt-IdNr. des Käufers (BT-48) und Fälligkeit (BT-9) aus der Extraktion
        entry(r4, "INVOICE", "RE-2026-0003", "2026-09-26", "Société Exemple", "500.00", "0.00", "500.00", "EU_REVERSE_CHARGE",
                "2026-10-26", "FR12345678901", List.of(tax("AE", "0", "500.00", "0.00")));
        // Benutzer ergänzt für die Gutschrift Leistungsdatum + Steuerperiode und eine Fälligkeit (V2)
        try {
            settingsService.saveInvoiceFields(s2.id(), "2026-09-20", "2026-09-20", "2026-10-25", null, "uwe", "vom Steuerberater bestätigt");
        } catch (ExportException ex) {
            throw new IllegalStateException(ex);
        }
        // REVIEW-Run ohne Erfolg → übersprungen
        SourceDocumentRow s4 = ledger.createSource("hofmann-it", reviewSha, "RE-4.pdf", 10, Instant.now());
        ProcessingRunRow r5 = ledger.createRun(s4.id(), 1, RunTrigger.AUTO, null, null, null, "standard", "0".repeat(64), "test", "22222222/run-001", Instant.now());
        ledger.appendEvent(r5.id(), EventType.REVIEW_REQUIRED, Instant.now(), "system", "review", null);
        ledger.createLedgerEntry(new LedgerEntryRow(0, r5.id(), "hofmann-it", "INVOICE", "RE-2026-0004", "2026-09-27", "Kunde", "EUR",
                null, null, null, null, "[]", reviewSha, "standard", "0".repeat(64), "test", "DOMESTIC_STANDARD", null, null, null, null,
                Instant.now()), List.of());
        ledger.finishRun(r5.id(), Instant.now(), RunResult.REVIEW);
    }

    private ProcessingRunRow run(long sourceId, int number, Long parent) {
        ProcessingRunRow r = ledger.createRun(sourceId, number, parent == null ? RunTrigger.AUTO : RunTrigger.MANUAL_REPROCESS, parent,
                parent == null ? null : "uwe", parent == null ? null : "test", "standard", "0".repeat(64), "test",
                "x/run-00" + number, Instant.now());
        ledger.appendEvent(r.id(), EventType.PROCESSING_STARTED, Instant.now(), "system", "start", null);
        ledger.appendEvent(r.id(), EventType.ARCHIVED, Instant.now(), "system", "archived", null);
        ledger.finishRun(r.id(), Instant.now(), RunResult.SUCCESS);
        return r;
    }

    private void entry(ProcessingRunRow r, String type, String number, String date, String customer, String net, String tax, String gross,
                       String businessCase, List<LedgerTaxLineRow> lines) {
        entry(r, type, number, date, customer, net, tax, gross, businessCase, null, null, lines);
    }

    private void entry(ProcessingRunRow r, String type, String number, String date, String customer, String net, String tax, String gross,
                       String businessCase, String dueDate, String buyerVatId, List<LedgerTaxLineRow> lines) {
        ledger.createLedgerEntry(new LedgerEntryRow(0, r.id(), "hofmann-it", type, number, date, customer, "EUR", net, tax, gross, gross,
                "[\"XRECHNUNG_CII\"]", "0".repeat(64), "standard", "0".repeat(64), "test", businessCase, dueDate, null, buyerVatId, null,
                Instant.now()), lines);
    }

    private static LedgerTaxLineRow tax(String cat, String rate, String base, String tax) {
        return new LedgerTaxLineRow(0, 0, cat, rate, base, tax);
    }

    @Test
    void datevFileMatchesDocumentedFieldRules() throws Exception {
        seed();
        assertThat(exports.availableVariants("hofmann-it")).containsExactly(Variant.CSV, Variant.DATEV_BUCHUNGSSTAPEL);
        ExportResult r = exports.export(Variant.DATEV_BUCHUNGSSTAPEL, "hofmann-it", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "uwe");
        assertThat(r.fileName()).startsWith("EXTF_Buchungsstapel_hofmann-it_2026-09-01_2026-09-30").endsWith(".csv");
        assertThat(exportRepository.exportLog("hofmann-it", 5)).anySatisfy(l -> {
            assertThat(l.variant()).isEqualTo("DATEV_BUCHUNGSSTAPEL");
            assertThat(l.sha256()).isEqualTo(r.sha256());
            assertThat(l.createdBy()).isEqualTo("uwe");
            assertThat(l.dateFrom()).isEqualTo("2026-09-01");
        });
        String text = new String(r.content(), Charset.forName("windows-1252"));
        String[] lines = text.split("\r\n");
        assertThat(lines.length).isEqualTo(2 + 4); // Kopf, Überschrift, RE/2026-0001 (1), GS (2), RC (1)
        assertThat(r.invoices()).isEqualTo(3);
        assertThat(r.records()).isEqualTo(4);

        // Kopfzeile: 31 Felder, jedes gegen die DATEV-Regel
        String[] header = lines[0].split(";", -1);
        assertThat(header).hasSize(31);
        for (int i = 0; i < 31; i++) {
            assertThat(header[i]).as("Kopfzeile Feld " + (i + 1)).matches(HEADER_RULES[i]);
        }
        assertThat(header[10]).isEqualTo("29098");
        assertThat(header[11]).isEqualTo("55003");
        assertThat(header[12]).isEqualTo("20260101");
        assertThat(header[14]).isEqualTo("20260901");
        assertThat(header[15]).isEqualTo("20260930");
        assertThat(header[16]).isEqualTo("\"Rechnungsausgang 09/2026\"");
        assertThat(header[17]).isEqualTo("\"UH\"");
        assertThat(header[26]).isEqualTo("\"03\"");

        // Überschriftszeile: 125 Spalten, erste und letzte laut Dokumentation
        String[] columns = lines[1].split(";", -1);
        assertThat(columns).hasSize(125);
        assertThat(columns[0]).isEqualTo("Umsatz (ohne Soll/Haben-Kz)");
        assertThat(columns[6]).isEqualTo("Konto");
        assertThat(columns[10]).isEqualTo("Belegfeld 1");
        assertThat(columns[113]).isEqualTo("Festschreibung");
        assertThat(columns[124]).isEqualTo("Abw. Skontokonto");

        // Buchungen
        List<String[]> records = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            String[] f = lines[i].split(";", -1);
            assertThat(f).as("Zeile " + (i + 1)).hasSize(125);
            assertThat(f[0]).matches("^(?!0{1,10},00)\\d{1,10},\\d{2}$");
            assertThat(f[1]).matches("^\"(S|H)\"$");
            assertThat(f[2]).matches("^\"([A-Z]{3})\"$");
            assertThat(f[6]).matches("^(?!0{1,9}$)(\\d{1,9})$");
            assertThat(f[7]).matches("^(?!0{1,9}$)(\\d{1,9})$");
            assertThat(f[8]).matches("^(\"\\d{4}\")$|^\"\"$");
            assertThat(f[9]).matches("^(\\d{4})$");
            assertThat(f[10]).matches("^(\"[\\w$&%*+\\-/]{0,36}\")$");
            assertThat(f[13]).matches("^(\".{0,60}\")$");
            assertThat(f[113]).matches("^(0|1)$");
            for (int k : new int[] {114, 115, 116}) {
                assertThat(f[k]).matches("^$|^((0[1-9]|[1-2]\\d|3[0-1])(0[1-9]|1[0-2])([2])([0])(\\d{2}))$");
            }
            assertThat(f[39]).matches("^(\".{0,15}\")$");
            records.add(f);
        }
        // Rechnung "RE/2026-0001 A" (aus Run 2): Debitor Soll, Erlöskonto 8400, Brutto 1856,40;
        // Belegfeld 1: '/' ist erlaubt, das Leerzeichen nicht → '-'
        String[] inv = records.get(0);
        assertThat(inv[0]).isEqualTo("1856,40");
        assertThat(inv[1]).isEqualTo("\"S\"");
        assertThat(inv[6]).isEqualTo("10000");
        assertThat(inv[7]).isEqualTo("8400");
        assertThat(inv[9]).isEqualTo("2409");
        assertThat(inv[10]).isEqualTo("\"RE/2026-0001-A\"");
        assertThat(inv[13]).isEqualTo("\"Rechnung RE/2026-0001 A Beispiel GmbH\"");
        assertThat(r.warnings()).anySatisfy(w -> assertThat(w).contains("RE/2026-0001 A").contains("RE/2026-0001-A"));
        // Rechnung ohne Zusatzfelder: #40, #115, #116, #117 leer
        assertThat(inv[39]).isEqualTo("\"\"");
        assertThat(inv[114]).isEmpty();
        assertThat(inv[115]).isEmpty();
        assertThat(inv[116]).isEmpty();
        // Gutschrift: zwei Buchungen im Haben, 19 % und 7 %; Benutzerfelder (V2) in #115/#116/#117
        assertThat(records.get(1)[1]).isEqualTo("\"H\"");
        assertThat(records.get(1)[0]).isEqualTo("238,00");
        assertThat(records.get(1)[7]).isEqualTo("8400");
        assertThat(records.get(1)[114]).isEqualTo("20092026");
        assertThat(records.get(1)[115]).isEqualTo("20092026");
        assertThat(records.get(1)[116]).isEqualTo("25102026");
        assertThat(records.get(2)[0]).isEqualTo("107,00");
        assertThat(records.get(2)[7]).isEqualTo("8300");
        assertThat(records.get(2)[114]).isEqualTo("20092026");
        // Reverse Charge: Erlöskonto ohne Steuersatz-Schlüssel, BU-Schlüssel gesetzt, #40 aus BT-48, #117 aus BT-9
        assertThat(records.get(3)[7]).isEqualTo("8336");
        assertThat(records.get(3)[8]).isEqualTo("\"0094\"");
        assertThat(records.get(3)[0]).isEqualTo("500,00");
        assertThat(records.get(3)[39]).isEqualTo("\"FR12345678901\"");
        assertThat(records.get(3)[116]).isEqualTo("26102026");
        assertThat(records.get(3)[114]).isEmpty();
        // Übersprungen: Run 1 (ersetzt) und REVIEW
        assertThat(r.skipped()).anySatisfy(s -> assertThat(s).contains("ersetzt"));
        assertThat(r.skipped()).anySatisfy(s -> assertThat(s).contains("REVIEW"));
        assertThat(text).doesNotContain("RE-2026-0004");
    }

    @Test
    void csvExportContainsAllLedgerColumns() throws Exception {
        seed();
        ExportResult r = exports.preview(Variant.CSV, "hofmann-it", null, null);
        String text = new String(r.content(), StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFFMandant;Rechnungsnummer;Rechnungsdatum;");
        assertThat(text).contains("\"RE/2026-0001 A\"").contains("\"1856,40\"").contains("\"S 19%: Basis 1560,00 Steuer 296,40\"")
                .contains("\"CREDIT_NOTE\"").contains("\"EU_REVERSE_CHARGE\"").contains("MANUAL_REPROCESS")
                .contains("\"FR12345678901\"").contains("\"26.10.2026\"");
        assertThat(text).doesNotContain("RE-2026-0004");
        assertThat(r.invoices()).isEqualTo(3);
    }

    @Test
    void datevExportRefusesPeriodsAcrossFiscalYearsAndUnconfiguredTenants() {
        seed();
        assertThatThrownBy(() -> exports.preview(Variant.DATEV_BUCHUNGSSTAPEL, "hofmann-it", LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 31)))
                .isInstanceOf(ExportException.class).hasMessageContaining("Wirtschaftsjahr");
        assertThatThrownBy(() -> exports.preview(Variant.DATEV_BUCHUNGSSTAPEL, "hofmann-it", null, null))
                .isInstanceOf(ExportException.class).hasMessageContaining("Zeitraum");
        assertThatThrownBy(() -> exports.preview(Variant.CSV, "unbekannt", null, null))
                .isInstanceOf(ExportException.class).hasMessageContaining("Unbekannter Mandant");
        assertThatThrownBy(() -> exports.export(Variant.CSV, "hofmann-it", null, null, " "))
                .isInstanceOf(ExportException.class).hasMessageContaining("Benutzername");
    }

    @Test
    void settingsFromDatabaseOverrideYamlAndAreValidated() throws Exception {
        seed();
        assertThat(settingsService.effective("hofmann-it").source()).isEqualTo(ExportSettingsService.Source.YAML);
        Datev yaml = settingsService.effective("hofmann-it").datev();

        // Ungültige Einstellungen werden nicht gespeichert
        Datev bad = new Datev(true, "12", yaml.clientNumber(), yaml.fiscalYearStart(), yaml.accountLength(), yaml.chartOfAccounts(),
                yaml.debtorStrategy(), yaml.collectiveDebtorAccount(), yaml.customerAccounts(), yaml.revenueAccounts(),
                yaml.origin(), yaml.exportedBy(), yaml.dictationShortcut(), yaml.lockRecords(), yaml.bookingTextTemplate());
        assertThatThrownBy(() -> settingsService.save("hofmann-it", bad, "uwe", null))
                .isInstanceOf(ExportException.class).hasMessageContaining("Beraternummer");
        assertThat(settingsService.effective("hofmann-it").source()).isEqualTo(ExportSettingsService.Source.YAML);

        // Gültige Einstellungen mit anderem Sammeldebitor wirken sofort auf den Export
        Datev changed = new Datev(true, yaml.consultantNumber(), yaml.clientNumber(), yaml.fiscalYearStart(), yaml.accountLength(),
                yaml.chartOfAccounts(), yaml.debtorStrategy(), "11000", yaml.customerAccounts(), yaml.revenueAccounts(),
                yaml.origin(), yaml.exportedBy(), yaml.dictationShortcut(), yaml.lockRecords(), yaml.bookingTextTemplate());
        settingsService.save("hofmann-it", changed, "uwe", "Konto laut Steuerberater");
        ExportSettingsService.Effective eff = settingsService.effective("hofmann-it");
        assertThat(eff.source()).isEqualTo(ExportSettingsService.Source.DATABASE);
        assertThat(eff.datev().collectiveDebtorAccount()).isEqualTo("11000");
        assertThat(eff.datev().revenueAccounts()).containsKey("DOMESTIC_STANDARD:19");
        assertThat(eff.datev().revenueAccounts().get("EU_REVERSE_CHARGE").buKey()).isEqualTo("0094");
        ExportResult r = exports.preview(Variant.DATEV_BUCHUNGSSTAPEL, "hofmann-it", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        String text = new String(r.content(), Charset.forName("windows-1252"));
        assertThat(text).contains(";11000;8400;").doesNotContain(";10000;");
        assertThat(exportRepository.settingsHistory("hofmann-it")).hasSize(1);

        // Zurück auf die YAML-Werte: neuer Datensatz, Historie bleibt (append-only)
        settingsService.save("hofmann-it", yaml, "uwe", "zurück auf Beispielwerte");
        assertThat(exportRepository.settingsHistory("hofmann-it")).hasSize(2);
        assertThat(settingsService.effective("hofmann-it").datev().collectiveDebtorAccount()).isEqualTo("10000");

        // Rechnungsfelder: #115 nur mit #116, USt-IdNr. mit Länderkürzel
        assertThatThrownBy(() -> settingsService.saveInvoiceFields(1, "2026-09-01", null, null, null, "uwe", null))
                .isInstanceOf(ExportException.class).hasMessageContaining("#116");
        assertThatThrownBy(() -> settingsService.saveInvoiceFields(1, null, null, null, "12345", "uwe", null))
                .isInstanceOf(ExportException.class).hasMessageContaining("USt-IdNr.");
        assertThatThrownBy(() -> settingsService.saveInvoiceFields(1, null, null, "01.10.2026", null, "uwe", null))
                .isInstanceOf(ExportException.class).hasMessageContaining("JJJJ-MM-TT");
    }

    @Test
    void perCustomerDebtorAndMissingRevenueAccountAreReported() {
        Datev cfg = new Datev(true, "1001", "1", "01-01", 4, "03", DebtorStrategy.PER_CUSTOMER, null,
                Map.of("Beispiel GmbH", "10001"), Map.of("DOMESTIC_STANDARD:19", new RevenueAccount("8400", null)),
                "RE", "", "", true, "Rechnung {invoiceNumber}");
        LedgerEntryRow known = new LedgerEntryRow(0, 1, "t", "INVOICE", "1", "2026-01-01", "Beispiel GmbH", "EUR", "1", "0", "1", "1", "[]",
                "0".repeat(64), "p", "h", "v", "DOMESTIC_STANDARD", null, null, null, null, Instant.now());
        LedgerEntryRow unknown = new LedgerEntryRow(0, 1, "t", "INVOICE", "1", "2026-01-01", "Andere GmbH", "EUR", "1", "0", "1", "1", "[]",
                "0".repeat(64), "p", "h", "v", "DOMESTIC_STANDARD", null, null, null, null, Instant.now());
        assertThat(DatevBuchungsstapelExporter.debtorAccount(cfg, known)).isEqualTo("10001");
        assertThat(DatevBuchungsstapelExporter.debtorAccount(cfg, unknown)).isNull();
        assertThat(DatevBuchungsstapelExporter.revenueAccount(cfg, "DOMESTIC_STANDARD", "19.00").account()).isEqualTo("8400");
        assertThat(DatevBuchungsstapelExporter.revenueAccount(cfg, "DOMESTIC_STANDARD", "7")).isNull();
        assertThat(DatevBuchungsstapelExporter.normalizeRate("19.00")).isEqualTo("19");
        assertThat(DatevBuchungsstapelExporter.normalizeRate("5.5")).isEqualTo("5.5");
        List<String> w = new ArrayList<>();
        assertThat(DatevBuchungsstapelExporter.documentField("RE 2026/0001:A", w)).isEqualTo("RE-2026/0001-A");
        assertThat(DatevBuchungsstapelExporter.documentField("RE-2026/0001_A$", w)).isEqualTo("RE-2026/0001_A$");
        assertThat(w).hasSize(1);
        assertThat(DatevBuchungsstapelExporter.fiscalYearStart(cfg, LocalDate.of(2026, 9, 24))).isEqualTo(LocalDate.of(2026, 1, 1));
        Datev july = new Datev(true, "1001", "1", "07-01", 4, "03", DebtorStrategy.COLLECTIVE, "10000", Map.of(), Map.of(), "RE", "", "", true, null);
        assertThat(DatevBuchungsstapelExporter.fiscalYearStart(july, LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2025, 7, 1));
    }

    @Test
    void invalidDatevConfigurationIsRejectedAtStartup() {
        Datev bad = new Datev(true, "12", "abc", "13-01", 3, "3", DebtorStrategy.COLLECTIVE, null, Map.of(), Map.of(), "RECH", "", "uh", true, null);
        Tenant t = new Tenant("t", "T", true, "", List.of("standard"), Map.of(), new TenantProperties.Export(bad, new TenantProperties.Belegtransfer(true, "relativ/pfad")));
        List<String> errors = new ArrayList<>();
        ProfileRegistry.validateDatev(t, errors);
        assertThat(errors).hasSizeGreaterThanOrEqualTo(8);
        assertThat(String.join("\n", errors)).contains("Beraternummer").contains("Mandantennummer").contains("fiscalYearStart")
                .contains("Sachkontenlänge").contains("Sachkontenrahmen").contains("collectiveDebtorAccount").contains("revenueAccounts")
                .contains("Herkunft").contains("Diktatkürzel").contains("belegtransfer.directory muss ein absoluter");
    }
}
