package de.hofmannit.erechnung.export;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.Rows.ExportLogRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Auswahl der Exportvariante je Mandant (Vorgabe Abschnitt 33) und Exportprotokoll (ADR 0010). */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);

    /** Verfügbare Exportvarianten. */
    public enum Variant {
        CSV("Rechnungsausgangsbuch (CSV)"),
        DATEV_BUCHUNGSSTAPEL("DATEV-Format Buchungsstapel (EXTF)");

        private final String label;

        Variant(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final LedgerCsvExporter csv;
    private final DatevBuchungsstapelExporter datev;
    private final ExportSettingsService settings;
    private final ExportRepository exportRepository;
    private final ProfileRegistry registry;
    private final Clock clock;

    public ExportService(LedgerCsvExporter csv, DatevBuchungsstapelExporter datev, ExportSettingsService settings,
                         ExportRepository exportRepository, ProfileRegistry registry, Clock clock) {
        this.csv = csv;
        this.datev = datev;
        this.settings = settings;
        this.exportRepository = exportRepository;
        this.registry = registry;
        this.clock = clock;
    }

    /** Varianten, die für den Mandanten konfiguriert sind (Datenbank vor YAML). */
    public List<Variant> availableVariants(String tenantId) {
        List<Variant> v = new ArrayList<>();
        v.add(Variant.CSV);
        if (registry.tenant(tenantId).isPresent() && settings.effective(tenantId).datevEnabled()) {
            v.add(Variant.DATEV_BUCHUNGSSTAPEL);
        }
        return v;
    }

    /** Erzeugt den Export ohne Protokolleintrag (Vorschau). */
    public ExportResult preview(Variant variant, String tenantId, LocalDate from, LocalDate to) throws ExportException {
        return build(variant, tenantId, from, to);
    }

    /** Erzeugt den Export und protokolliert ihn in {@code export_log} (Download). */
    public ExportResult export(Variant variant, String tenantId, LocalDate from, LocalDate to, String user) throws ExportException {
        if (user == null || user.isBlank()) {
            throw new ExportException("Benutzername ist erforderlich");
        }
        ExportResult result = build(variant, tenantId, from, to);
        exportRepository.logExport(new ExportLogRow(0, tenantId, variant.name(), from == null ? null : from.toString(),
                to == null ? null : to.toString(), result.fileName(), result.sha256(), result.content().length, result.records(),
                result.invoices(), result.skipped().size(), clock.instant(), user.trim()));
        log.info("Export {} für Mandant {} ({} bis {}) durch {}: {} Datensätze, {} Rechnungen, {} übersprungen, SHA-256 {}",
                variant, tenantId, from, to, user.trim(), result.records(), result.invoices(), result.skipped().size(), result.sha256());
        return result;
    }

    private ExportResult build(Variant variant, String tenantId, LocalDate from, LocalDate to) throws ExportException {
        if (registry.tenant(tenantId).isEmpty()) {
            throw new ExportException("Unbekannter Mandant: " + tenantId);
        }
        return switch (variant) {
            case CSV -> csv.export(tenantId, from, to);
            case DATEV_BUCHUNGSSTAPEL -> datev.export(tenantId, from, to);
        };
    }
}
