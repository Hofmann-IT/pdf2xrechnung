package de.hofmannit.erechnung.export;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.hofmannit.erechnung.export.ExportSelection.Selected;
import de.hofmannit.erechnung.export.ExportSelection.Selection;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.ArtifactRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerEntryRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.ledger.Rows.ValidationResultRow;
import de.hofmannit.erechnung.security.Sha256;

import org.springframework.stereotype.Component;

/**
 * Einfache CSV des Rechnungsausgangsbuchs: eine Zeile je Rechnung, alle Ledger-Daten inklusive
 * Steueraufschlüsselung, Validierungsergebnissen, Artefakt-Hashes und Archivpfad.
 * Deutsches Tabellenformat (Semikolon, Dezimalkomma, dd.MM.yyyy, UTF-8 mit BOM für Excel).
 */
@Component
public class LedgerCsvExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMANY);
    private static final String[] HEADER = {
            "Mandant", "Rechnungsnummer", "Rechnungsdatum", "Dokumenttyp", "Kunde", "Kundennummer", "USt-IdNr. Kunde", "Währung",
            "Netto", "Steuer", "Brutto", "Zahlbetrag", "Fälligkeit", "Lieferdatum", "Steuerzeilen", "Geschäftsfall", "Formate", "Status", "Run", "Run-Nr", "Auslöser", "Quelldatei", "Quell-SHA-256",
            "Profil", "Profil-Hash", "Anwendungsversion", "Gestartet", "Beendet", "Validierung", "Archivpfad", "Artefakte (SHA-256)"};

    private final LedgerRepository ledger;

    public LedgerCsvExporter(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    public ExportResult export(String tenantId, LocalDate from, LocalDate to) {
        Selection selection = ExportSelection.select(ledger, tenantId, from, to);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(";", HEADER)).append("\r\n");
        for (Selected s : selection.invoices()) {
            LedgerEntryRow e = s.row().entry();
            List<String> cells = new ArrayList<>();
            cells.add(e.tenantId());
            cells.add(e.invoiceNumber());
            cells.add(date(e.invoiceDate()));
            cells.add(e.documentType());
            cells.add(e.customerName());
            cells.add(e.buyerId());
            cells.add(e.buyerVatId());
            cells.add(e.currency());
            cells.add(money(e.netTotal()));
            cells.add(money(e.taxTotal()));
            cells.add(money(e.grossTotal()));
            cells.add(money(e.payableAmount()));
            cells.add(date(e.dueDate()));
            cells.add(date(e.deliveryDate()));
            cells.add(taxLines(s.taxLines()));
            cells.add(e.businessCase());
            cells.add(formats(e.generatedFormats()));
            cells.add(s.row().status().name());
            cells.add(s.row().run().correlationId());
            cells.add(String.valueOf(s.row().run().runNumber()));
            cells.add(s.row().run().trigger().name());
            cells.add(s.row().source().originalFilename());
            cells.add(s.row().source().sha256());
            cells.add(e.profileName());
            cells.add(e.profileHash());
            cells.add(e.applicationVersion());
            cells.add(s.row().run().startedAt() == null ? "" : TS.format(s.row().run().startedAt().atZone(java.time.ZoneId.systemDefault())));
            cells.add(s.row().run().finishedAt() == null ? "" : TS.format(s.row().run().finishedAt().atZone(java.time.ZoneId.systemDefault())));
            List<ValidationResultRow> validations = ledger.validationResults(s.row().run().id());
            cells.add(String.join(" | ", validations.stream().map(v -> v.validator() + "/" + v.targetFormat() + "=" + v.outcome()
                    + " (" + v.errorCount() + " Fehler, " + v.warningCount() + " Warnungen)").toList()));
            List<ArtifactRow> artifacts = ledger.artifacts(s.row().run().id());
            cells.add(artifacts.isEmpty() ? "" : artifacts.get(0).path().substring(0, Math.max(0, artifacts.get(0).path().lastIndexOf('/'))));
            cells.add(String.join(" | ", artifacts.stream().map(a -> a.fileName() + "=" + a.sha256()).toList()));
            sb.append(String.join(";", cells.stream().map(LedgerCsvExporter::quote).toList())).append("\r\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String name = "rechnungsausgangsbuch_" + tenantId + "_" + (from == null ? "anfang" : from) + "_" + (to == null ? "ende" : to) + ".csv";
        return new ExportResult(name, "text/csv;charset=UTF-8", bytes, selection.invoices().size(), selection.invoices().size(),
                selection.skipped(), selection.warnings(), Sha256.ofBytes(bytes));
    }

    static String quote(String s) {
        String v = s == null ? "" : s.replace("\r", " ").replace("\n", " ");
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    static String money(String canonical) {
        if (canonical == null || canonical.isBlank()) {
            return "";
        }
        return new BigDecimal(canonical).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
    }

    static String date(String iso) {
        if (iso == null || iso.isBlank()) {
            return "";
        }
        try {
            return LocalDate.parse(iso).format(DATE);
        } catch (RuntimeException e) {
            return iso;
        }
    }

    private static String taxLines(List<LedgerTaxLineRow> lines) {
        return String.join(" | ", lines.stream().map(t -> t.vatCategoryCode() + " " + t.vatRate() + "%: Basis " + money(t.taxableAmount())
                + " Steuer " + money(t.taxAmount())).toList());
    }

    static String formats(String json) {
        return json == null ? "" : json.replace("[", "").replace("]", "").replace("\"", "").replace(",", " | ").trim();
    }
}
