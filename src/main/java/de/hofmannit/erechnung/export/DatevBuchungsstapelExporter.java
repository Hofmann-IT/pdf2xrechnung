package de.hofmannit.erechnung.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import de.hofmannit.erechnung.configuration.TenantProperties.Datev;
import de.hofmannit.erechnung.configuration.TenantProperties.DebtorStrategy;
import de.hofmannit.erechnung.configuration.TenantProperties.RevenueAccount;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.export.ExportSelection.Selected;
import de.hofmannit.erechnung.export.ExportSelection.Selection;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.InvoiceExportFieldRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.security.Sha256;

import org.springframework.stereotype.Component;

/**
 * DATEV-Format „Buchungsstapel" (EXTF, Version 700, Formatversion 13). Feldregeln und Quelle:
 * {@code docs/datev-format-referenz.md}. Jede Kopfzeile und jede Buchung wird vor der Ausgabe
 * gegen die dort dokumentierten regulären Ausdrücke geprüft; Verstöße brechen den Export ab.
 *
 * <p>Buchungslogik je Rechnung und Steuerzeile: Konto (#7) = Debitor im Soll ("S", bei
 * Gutschrift "H"), Gegenkonto (#8) = Erlöskonto laut Konfiguration, Umsatz (#1) = Brutto der
 * Steuerzeile (Basis + Steuer). Die Umsatzsteuer wird von DATEV über Automatikkonto oder den
 * konfigurierten BU-Schlüssel (#9) ermittelt.
 */
@Component
public class DatevBuchungsstapelExporter {

    /** DATEV-Format-Dateien werden im Windows-Zeichensatz erwartet (ANSI). */
    static final Charset CHARSET = Charset.forName("windows-1252");
    static final int FIELD_COUNT = 125;

    // Regeln der Kopfzeile (Nummern laut Referenz)
    private static final Pattern H11_CONSULTANT = Pattern.compile("\\d{4,6}|\\d{7}");
    private static final Pattern H12_CLIENT = Pattern.compile("\\d{1,5}");
    private static final Pattern H17_LABEL = Pattern.compile("[\\w.\\-/ ]{0,30}");
    private static final Pattern H18_DICTATION = Pattern.compile("([A-Z]{2}){0,2}");
    // Regeln der Nutzdaten
    private static final Pattern F1_AMOUNT = Pattern.compile("(?!0{1,10},00)\\d{1,10},\\d{2}");
    private static final Pattern F7_ACCOUNT = Pattern.compile("(?!0{1,9}$)\\d{1,9}");
    private static final Pattern F9_BU = Pattern.compile("\\d{4}");
    private static final Pattern F10_DATE = Pattern.compile("\\d{4}");
    private static final Pattern F11_DOCFIELD = Pattern.compile("[\\w$&%*+\\-/]{0,36}");
    private static final Pattern F11_INVALID_CHAR = Pattern.compile("[^\\w$&%*+\\-/]");
    private static final Pattern F40_VATID = Pattern.compile(".{0,15}");

    /** Textfelder (mit Anführungszeichen) laut Feldliste; alle anderen sind Zahlenfelder. */
    private static final Set<Integer> TEXT_FIELDS = textFields();

    private static final Pattern F115_DATE = Pattern.compile("(0[1-9]|[1-2]\\d|3[0-1])(0[1-9]|1[0-2])([2])([0])(\\d{2})");
    private static final DateTimeFormatter TTMMJJJJ = DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ROOT);

    private final LedgerRepository ledger;
    private final ExportRepository exportRepository;
    private final ExportSettingsService settings;
    private final ProfileRegistry registry;
    private final Clock clock;

    public DatevBuchungsstapelExporter(LedgerRepository ledger, ExportRepository exportRepository, ExportSettingsService settings,
                                       ProfileRegistry registry, Clock clock) {
        this.ledger = ledger;
        this.exportRepository = exportRepository;
        this.settings = settings;
        this.registry = registry;
        this.clock = clock;
    }

    public ExportResult export(String tenantId, LocalDate from, LocalDate to) throws ExportException {
        registry.tenant(tenantId).orElseThrow(() -> new ExportException("Unbekannter Mandant: " + tenantId));
        Datev cfg = settings.effective(tenantId).datev();
        if (cfg == null || !cfg.enabled()) {
            throw new ExportException("DATEV-Export ist für Mandant " + tenantId + " nicht aktiviert (Export-Einstellungen bzw. config/tenant.yaml: export.datev.enabled)");
        }
        if (from == null || to == null) {
            throw new ExportException("Für den DATEV-Buchungsstapel ist ein Zeitraum (von/bis) erforderlich");
        }
        if (to.isBefore(from)) {
            throw new ExportException("Zeitraum ungültig: 'bis' liegt vor 'von'");
        }
        LocalDate fiscalYearStart = fiscalYearStart(cfg, from);
        if (!to.isBefore(fiscalYearStart.plusYears(1))) {
            throw new ExportException("Der Zeitraum überschreitet das Wirtschaftsjahr ab " + fiscalYearStart
                    + "; das Belegdatum (#10) trägt kein Jahr, daher je Wirtschaftsjahr exportieren");
        }

        Selection selection = ExportSelection.select(ledger, tenantId, from, to);
        List<String> warnings = new ArrayList<>(selection.warnings());
        List<String> skipped = new ArrayList<>(selection.skipped());
        StringBuilder sb = new StringBuilder();
        sb.append(header(cfg, from, to, fiscalYearStart)).append("\r\n");
        sb.append(columnHeader()).append("\r\n");

        int bookings = 0;
        int invoices = 0;
        for (Selected s : selection.invoices()) {
            LedgerEntryRow e = s.row().entry();
            LocalDate date;
            try {
                date = LocalDate.parse(e.invoiceDate());
            } catch (RuntimeException ex) {
                skipped.add(e.invoiceNumber() + ": Rechnungsdatum fehlt oder ungültig");
                continue;
            }
            if (date.isBefore(fiscalYearStart) || !date.isBefore(fiscalYearStart.plusYears(1))) {
                skipped.add(e.invoiceNumber() + ": Rechnungsdatum " + e.invoiceDate() + " außerhalb des Wirtschaftsjahres");
                continue;
            }
            List<LedgerTaxLineRow> taxLines = s.taxLines();
            if (taxLines.isEmpty()) {
                skipped.add(e.invoiceNumber() + ": keine Steuerzeilen im Ledger");
                continue;
            }
            String debtor = debtorAccount(cfg, e);
            if (debtor == null) {
                skipped.add(e.invoiceNumber() + ": kein Debitorenkonto für Kunde '" + e.customerName() + "' konfiguriert");
                continue;
            }
            // Vom Benutzer ergänzte Felder (V2) haben Vorrang vor den extrahierten Werten des Ledgers.
            InvoiceExportFieldRow extra = exportRepository.latestInvoiceFields(s.row().source().id()).orElse(null);
            String dueDate = extra != null && extra.dueDate() != null ? extra.dueDate() : e.dueDate();
            String buyerVatId = extra != null && extra.buyerVatId() != null ? extra.buyerVatId() : e.buyerVatId();
            String serviceDate = extra == null ? null : extra.serviceDate();
            String taxPeriodDate = extra == null ? null : extra.taxPeriodDate();
            List<String> lines = new ArrayList<>();
            boolean complete = true;
            for (LedgerTaxLineRow t : taxLines) {
                RevenueAccount revenue = revenueAccount(cfg, e.businessCase(), t.vatRate());
                if (revenue == null) {
                    skipped.add(e.invoiceNumber() + ": kein Erlöskonto für Geschäftsfall " + e.businessCase() + " / Steuersatz " + t.vatRate() + " % konfiguriert");
                    complete = false;
                    break;
                }
                BigDecimal gross = new BigDecimal(t.taxableAmount()).add(new BigDecimal(t.taxAmount())).setScale(2, RoundingMode.HALF_UP);
                if (gross.signum() == 0) {
                    continue;
                }
                boolean creditNote = "CREDIT_NOTE".equals(e.documentType());
                String docField = documentField(e.invoiceNumber(), warnings);
                String bookingText = bookingText(cfg, e);
                // #40 nur bei EU-Geschäftsfällen (EU-Mitgliedstaat und USt-IdNr. des Bestimmungslands)
                String vatId = buyerVatId != null && e.businessCase() != null && e.businessCase().startsWith("EU") ? buyerVatId : null;
                String[] f = new String[FIELD_COUNT + 1];
                f[1] = gross.abs().toPlainString().replace('.', ',');
                f[2] = creditNote ^ gross.signum() < 0 ? "H" : "S";
                f[3] = e.currency() == null ? "EUR" : e.currency();
                f[7] = debtor;
                f[8] = revenue.account();
                f[9] = revenue.buKey() == null || revenue.buKey().isBlank() ? "" : revenue.buKey();
                f[10] = String.format(Locale.ROOT, "%02d%02d", date.getDayOfMonth(), date.getMonthValue());
                f[11] = docField;
                f[14] = bookingText;
                f[40] = vatId == null ? "" : vatId;
                f[114] = cfg.lockRecords() ? "1" : "0";
                // #115 Leistungsdatum nur zusammen mit #116 (DATEV-Vorgabe); beide ausschließlich aus Benutzereingabe
                if (serviceDate != null && taxPeriodDate != null) {
                    f[115] = ttmmjjjj(serviceDate, e.invoiceNumber(), "Leistungsdatum (#115)");
                    f[116] = ttmmjjjj(taxPeriodDate, e.invoiceNumber(), "Datum Zuordnung Steuerperiode (#116)");
                }
                if (dueDate != null) {
                    f[117] = ttmmjjjj(dueDate, e.invoiceNumber(), "Fälligkeit (#117)");
                }
                validateRecord(f, e.invoiceNumber());
                lines.add(record(f));
            }
            if (!complete) {
                continue;
            }
            for (String l : lines) {
                sb.append(l).append("\r\n");
            }
            bookings += lines.size();
            invoices++;
        }
        if (bookings > 99_999) {
            throw new ExportException("Mehr als 99.999 Buchungen; DATEV verlangt je Datei höchstens 99.999");
        }
        byte[] bytes = sb.toString().getBytes(CHARSET);
        String name = "EXTF_Buchungsstapel_" + tenantId + "_" + from + "_" + to + ".csv";
        return new ExportResult(name, "text/csv;charset=windows-1252", bytes, bookings, invoices, skipped, warnings, Sha256.ofBytes(bytes));
    }

    // ------------------------------------------------------------------ Kopfzeile

    String header(Datev cfg, LocalDate from, LocalDate to, LocalDate fiscalYearStart) throws ExportException {
        require(H11_CONSULTANT, cfg.consultantNumber(), "Beraternummer (Kopfzeile #11)");
        require(H12_CLIENT, cfg.clientNumber(), "Mandantennummer (Kopfzeile #12)");
        String label = "Rechnungsausgang " + String.format(Locale.ROOT, "%02d/%04d", from.getMonthValue(), from.getYear());
        require(H17_LABEL, label, "Bezeichnung (Kopfzeile #17)");
        String dictation = cfg.dictationShortcut() == null ? "" : cfg.dictationShortcut();
        require(H18_DICTATION, dictation, "Diktatkürzel (Kopfzeile #18)");
        if (cfg.accountLength() < 4 || cfg.accountLength() > 8) {
            throw new ExportException("Sachkontenlänge (Kopfzeile #14) muss 4–8 sein");
        }
        DateTimeFormatter ymd = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT);
        String created = LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS", Locale.ROOT));
        List<String> h = new ArrayList<>();
        h.add(q("EXTF"));                                   // 1 Kennzeichen
        h.add("700");                                       // 2 Versionsnummer
        h.add("21");                                        // 3 Formatkategorie Buchungsstapel
        h.add(q("Buchungsstapel"));                         // 4 Formatname
        h.add("13");                                        // 5 Formatversion Buchungsstapel
        h.add(created);                                     // 6 Erzeugt am
        h.add("");                                          // 7 Importiert
        h.add(q(cfg.origin() == null ? "RE" : cfg.origin())); // 8 Herkunft
        h.add(q(cfg.exportedBy() == null ? "" : cfg.exportedBy())); // 9 Exportiert von
        h.add(q(""));                                       // 10 Importiert von
        h.add(cfg.consultantNumber());                      // 11 Beraternummer
        h.add(cfg.clientNumber());                          // 12 Mandantennummer
        h.add(fiscalYearStart.format(ymd));                 // 13 WJ-Beginn
        h.add(String.valueOf(cfg.accountLength()));         // 14 Sachkontenlänge
        h.add(from.format(ymd));                            // 15 Datum von
        h.add(to.format(ymd));                              // 16 Datum bis
        h.add(q(label));                                    // 17 Bezeichnung
        h.add(q(dictation));                                // 18 Diktatkürzel
        h.add("1");                                         // 19 Buchungstyp Finanzbuchführung
        h.add("0");                                         // 20 Rechnungslegungszweck unabhängig
        h.add(cfg.lockRecords() ? "1" : "0");               // 21 Festschreibung
        h.add(q("EUR"));                                    // 22 WKZ
        h.add("");                                          // 23 Reserviert
        h.add(q(""));                                       // 24 Derivatskennzeichen
        h.add("");                                          // 25 Reserviert
        h.add("");                                          // 26 Reserviert
        h.add(q(cfg.chartOfAccounts() == null ? "" : cfg.chartOfAccounts())); // 27 Sachkontenrahmen
        h.add("");                                          // 28 ID der Branchenlösung
        h.add("");                                          // 29 Reserviert
        h.add(q(""));                                       // 30 Reserviert
        h.add(q("PDF-zu-ERechnung"));                       // 31 Anwendungsinformation
        return String.join(";", h);
    }

    static String columnHeader() {
        String[] names = new String[FIELD_COUNT];
        String fixed = "Umsatz (ohne Soll/Haben-Kz);Soll/Haben-Kennzeichen;WKZ Umsatz;Kurs;Basis-Umsatz;WKZ Basis-Umsatz;Konto;"
                + "Gegenkonto (ohne BU-Schlüssel);BU-Schlüssel;Belegdatum;Belegfeld 1;Belegfeld 2;Skonto;Buchungstext;Postensperre;"
                + "Diverse Adressnummer;Geschäftspartnerbank;Sachverhalt;Zinssperre;Beleglink";
        String[] first = fixed.split(";");
        System.arraycopy(first, 0, names, 0, first.length);
        int i = 20;
        for (int n = 1; n <= 8; n++) {
            names[i++] = "Beleginfo - Art " + n;
            names[i++] = "Beleginfo - Inhalt " + n;
        }
        names[i++] = "KOST1 - Kostenstelle";
        names[i++] = "KOST2 - Kostenstelle";
        names[i++] = "Kost-Menge";
        names[i++] = "EU-Land u. UStID (Bestimmung)";
        names[i++] = "EU-Steuersatz (Bestimmung)";
        names[i++] = "Abw. Versteuerungsart";
        names[i++] = "Sachverhalt L+L";
        names[i++] = "Funktionsergänzung L+L";
        names[i++] = "BU 49 Hauptfunktionstyp";
        names[i++] = "BU 49 Hauptfunktionsnummer";
        names[i++] = "BU 49 Funktionsergänzung";
        for (int n = 1; n <= 20; n++) {
            names[i++] = "Zusatzinformation - Art " + n;
            names[i++] = "Zusatzinformation- Inhalt " + n;
        }
        String[] rest = ("Stück;Gewicht;Zahlweise;Forderungsart;Veranlagungsjahr;Zugeordnete Fälligkeit;Skontotyp;Auftragsnummer;Buchungstyp;"
                + "USt-Schlüssel (Anzahlungen);EU-Land (Anzahlungen);Sachverhalt L+L (Anzahlungen);EU-Steuersatz (Anzahlungen);Erlöskonto (Anzahlungen);"
                + "Herkunft-Kz;Buchungs GUID;KOST-Datum;SEPA-Mandatsreferenz;Skontosperre;Gesellschaftername;Beteiligtennummer;Identifikationsnummer;"
                + "Zeichnernummer;Postensperre bis;Bezeichnung SoBil-Sachverhalt;Kennzeichen SoBil-Buchung;Festschreibung;Leistungsdatum;"
                + "Datum Zuord. Steuerperiode;Fälligkeit;Generalumkehr (GU);Steuersatz;Land;Abrechnungsreferenz;BVV-Position;"
                + "EU-Land u. UStID (Ursprung);EU-Steuersatz (Ursprung);Abw. Skontokonto").split(";");
        System.arraycopy(rest, 0, names, i, rest.length);
        if (i + rest.length != FIELD_COUNT) {
            throw new IllegalStateException("Spaltenzahl " + (i + rest.length) + " ≠ " + FIELD_COUNT);
        }
        return String.join(";", names);
    }

    // ------------------------------------------------------------------ Datensatz

    /** Serialisiert die 125 Felder; Textfelder in Anführungszeichen (leer = ""), Zahlenfelder leer. */
    static String record(String[] f) {
        List<String> cells = new ArrayList<>(FIELD_COUNT);
        for (int i = 1; i <= FIELD_COUNT; i++) {
            String v = f[i] == null ? "" : f[i];
            cells.add(TEXT_FIELDS.contains(i) ? q(v) : v);
        }
        return String.join(";", cells);
    }

    static void validateRecord(String[] f, String invoiceNumber) throws ExportException {
        for (int i = 1; i <= FIELD_COUNT; i++) {
            if (f[i] == null) {
                f[i] = "";
            }
        }
        String p = "Rechnung " + invoiceNumber + ": ";
        require(F1_AMOUNT, f[1], p + "Umsatz (#1)");
        if (!f[2].equals("S") && !f[2].equals("H")) {
            throw new ExportException(p + "Soll/Haben (#2) ungültig");
        }
        require(F7_ACCOUNT, f[7], p + "Konto (#7)");
        require(F7_ACCOUNT, f[8], p + "Gegenkonto (#8)");
        if (!f[9].isEmpty()) {
            require(F9_BU, f[9], p + "BU-Schlüssel (#9)");
        }
        require(F10_DATE, f[10], p + "Belegdatum (#10)");
        require(F11_DOCFIELD, f[11], p + "Belegfeld 1 (#11)");
        if (f[14].length() > 60) {
            throw new ExportException(p + "Buchungstext (#14) länger als 60 Zeichen");
        }
        require(F40_VATID, f[40], p + "EU-UStID (#40)");
        if (!f[115].isEmpty() || !f[116].isEmpty()) {
            require(F115_DATE, f[115], p + "Leistungsdatum (#115)");
            require(F115_DATE, f[116], p + "Datum Zuordnung Steuerperiode (#116)");
        }
        if (!f[117].isEmpty()) {
            require(F115_DATE, f[117], p + "Fälligkeit (#117)");
        }
    }

    private static String ttmmjjjj(String isoDate, String invoiceNumber, String what) throws ExportException {
        try {
            return LocalDate.parse(isoDate).format(TTMMJJJJ);
        } catch (RuntimeException e) {
            throw new ExportException("Rechnung " + invoiceNumber + ": " + what + " ist kein gültiges Datum: '" + isoDate + "'");
        }
    }

    private static void require(Pattern pattern, String value, String what) throws ExportException {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new ExportException(what + " verletzt die DATEV-Feldregel: '" + value + "'");
        }
    }

    /** Belegfeld 1: unzulässige Zeichen werden durch '-' ersetzt und protokolliert; Länge maximal 36. */
    static String documentField(String invoiceNumber, List<String> warnings) {
        String replaced = F11_INVALID_CHAR.matcher(invoiceNumber).replaceAll("-");
        String cut = replaced.length() > 36 ? replaced.substring(0, 36) : replaced;
        if (!cut.equals(invoiceNumber)) {
            warnings.add("Belegfeld 1: Rechnungsnummer '" + invoiceNumber + "' als '" + cut + "' exportiert (DATEV erlaubt nur Buchstaben, Ziffern, _ $ & % * + - /, max. 36 Zeichen)");
        }
        return cut;
    }

    static String bookingText(Datev cfg, LedgerEntryRow e) {
        String template = cfg.bookingTextTemplate() == null ? "Rechnung {invoiceNumber} {customerName}" : cfg.bookingTextTemplate();
        String text = template.replace("{invoiceNumber}", nz(e.invoiceNumber())).replace("{customerName}", nz(e.customerName()))
                .replace("{invoiceDate}", LedgerCsvExporter.date(e.invoiceDate()));
        text = text.replaceAll("[\\r\\n\\t]", " ").trim();
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    static String debtorAccount(Datev cfg, LedgerEntryRow e) {
        if (cfg.debtorStrategy() == DebtorStrategy.PER_CUSTOMER) {
            String account = cfg.customerAccounts().get(e.customerName());
            return account == null || account.isBlank() ? null : account.trim();
        }
        return cfg.collectiveDebtorAccount();
    }

    /** Erlöskonto: zuerst {@code <Geschäftsfall>:<Satz>}, dann {@code <Geschäftsfall>}. Steuersatz ohne Nachkommastellen, falls ganzzahlig. */
    static RevenueAccount revenueAccount(Datev cfg, String businessCase, String vatRate) {
        String bc = businessCase == null ? "DOMESTIC_STANDARD" : businessCase;
        String rate = normalizeRate(vatRate);
        Map<String, RevenueAccount> m = cfg.revenueAccounts();
        RevenueAccount ra = m.get(bc + ":" + rate);
        if (ra == null) {
            ra = m.get(bc);
        }
        return ra;
    }

    static String normalizeRate(String vatRate) {
        if (vatRate == null) {
            return "0";
        }
        BigDecimal r = new BigDecimal(vatRate).stripTrailingZeros();
        return r.scale() <= 0 ? r.toBigInteger().toString() : r.toPlainString();
    }

    static LocalDate fiscalYearStart(Datev cfg, LocalDate reference) {
        String[] md = (cfg.fiscalYearStart() == null ? "01-01" : cfg.fiscalYearStart()).split("-");
        int month = Integer.parseInt(md[0]);
        int day = Integer.parseInt(md[1]);
        LocalDate candidate = LocalDate.of(reference.getYear(), month, Math.min(day, LocalDate.of(reference.getYear(), month, 1).lengthOfMonth()));
        return candidate.isAfter(reference) ? candidate.minusYears(1) : candidate;
    }

    static String q(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static Set<Integer> textFields() {
        Set<Integer> t = new LinkedHashSet<>();
        for (int i : new int[] {2, 3, 6, 9, 11, 12, 14, 16, 20}) {
            t.add(i);
        }
        for (int i = 21; i <= 38; i++) {
            t.add(i);
        }
        for (int i : new int[] {40, 42}) {
            t.add(i);
        }
        for (int i = 48; i <= 87; i++) {
            t.add(i);
        }
        for (int i : new int[] {91, 95, 96, 98, 102, 103, 105, 107, 109, 110, 112, 118, 120, 121, 123}) {
            t.add(i);
        }
        return Set.copyOf(t);
    }
}
