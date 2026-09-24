package de.hofmannit.erechnung.export;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.ledger.InvoiceStatus;
import de.hofmannit.erechnung.ledger.LedgerRepository;
import de.hofmannit.erechnung.ledger.Rows.InvoiceFilter;
import de.hofmannit.erechnung.ledger.Rows.InvoiceListRow;
import de.hofmannit.erechnung.ledger.Rows.LedgerTaxLineRow;
import de.hofmannit.erechnung.ledger.RunResult;

/**
 * Auswahl der zu exportierenden Rechnungen: je Quelldokument der jüngste erfolgreiche Run
 * (ein Reprocess ersetzt damit den Vorgänger im Export, überschreibt aber nichts). Runs ohne
 * Erfolg (REVIEW, FAILED, REJECTED) werden mit Begründung übersprungen.
 */
final class ExportSelection {

    private ExportSelection() {
    }

    record Selected(InvoiceListRow row, List<LedgerTaxLineRow> taxLines) {
    }

    record Selection(List<Selected> invoices, List<String> skipped, List<String> warnings) {
    }

    static Selection select(LedgerRepository ledger, String tenantId, LocalDate from, LocalDate to) {
        List<InvoiceListRow> rows = ledger.listInvoices(new InvoiceFilter(tenantId, null, null, null,
                from == null ? null : from.toString(), to == null ? null : to.toString(), null, 1000));
        Map<Long, InvoiceListRow> latestPerSource = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (InvoiceListRow r : rows) {
            if (r.run().result() != RunResult.SUCCESS || r.entry() == null || r.entry().invoiceNumber() == null) {
                skipped.add(r.run().correlationId() + " (" + (r.entry() == null || r.entry().invoiceNumber() == null
                        ? r.source().originalFilename() : r.entry().invoiceNumber()) + "): Ergebnis " + r.run().result()
                        + ", Status " + r.status());
                continue;
            }
            if (r.status() == InvoiceStatus.PROCESSING) {
                skipped.add(r.run().correlationId() + ": noch in Verarbeitung");
                continue;
            }
            InvoiceListRow existing = latestPerSource.get(r.source().id());
            if (existing == null || r.run().runNumber() > existing.run().runNumber()) {
                if (existing != null) {
                    skipped.add(existing.run().correlationId() + ": durch Run " + r.run().runNumber() + " ersetzt");
                }
                latestPerSource.put(r.source().id(), r);
            } else {
                skipped.add(r.run().correlationId() + ": durch Run " + existing.run().runNumber() + " ersetzt");
            }
        }
        // Gleiche Rechnungsnummer aus verschiedenen Quelldokumenten: exportieren, aber warnen.
        Map<String, Integer> numbers = new LinkedHashMap<>();
        for (InvoiceListRow r : latestPerSource.values()) {
            numbers.merge(r.entry().invoiceNumber(), 1, Integer::sum);
        }
        numbers.forEach((n, c) -> {
            if (c > 1) {
                warnings.add("Rechnungsnummer " + n + " kommt " + c + "-mal vor (verschiedene Quelldokumente)");
            }
        });
        List<Selected> selected = new ArrayList<>();
        for (InvoiceListRow r : latestPerSource.values()) {
            selected.add(new Selected(r, ledger.taxLines(r.entry().id())));
        }
        selected.sort((a, b) -> {
            int byDate = String.valueOf(a.row().entry().invoiceDate()).compareTo(String.valueOf(b.row().entry().invoiceDate()));
            return byDate != 0 ? byDate : a.row().entry().invoiceNumber().compareTo(b.row().entry().invoiceNumber());
        });
        return new Selection(selected, skipped, warnings);
    }
}
