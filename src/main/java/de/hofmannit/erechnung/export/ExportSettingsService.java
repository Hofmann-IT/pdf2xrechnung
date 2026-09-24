package de.hofmannit.erechnung.export;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hofmannit.erechnung.configuration.TenantProperties.Datev;
import de.hofmannit.erechnung.configuration.TenantProperties.DebtorStrategy;
import de.hofmannit.erechnung.configuration.TenantProperties.RevenueAccount;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.ledger.ExportRepository;
import de.hofmannit.erechnung.ledger.Rows.ExportSettingsRow;
import de.hofmannit.erechnung.ledger.Rows.InvoiceExportFieldRow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

/**
 * Wirksame DATEV-Einstellungen je Mandant (ADR 0010): der jüngste in der Datenbank gespeicherte
 * Datensatz, sonst der Block {@code export.datev} aus {@code config/tenant.yaml}. Gespeichert wird
 * nur, was dieselben Prüfregeln besteht wie die YAML-Konfiguration beim Start.
 */
@Service
public class ExportSettingsService {

    /** Herkunft der wirksamen Einstellungen. */
    public enum Source { DATABASE, YAML, NONE }

    public record Effective(Datev datev, Source source, ExportSettingsRow row) {
        public boolean datevEnabled() {
            return datev != null && datev.enabled();
        }
    }

    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final TypeReference<Map<String, RevenueAccount>> REVENUE_MAP = new TypeReference<>() { };

    private final ExportRepository repository;
    private final ProfileRegistry registry;
    private final ObjectMapper json;
    private final Clock clock;

    public ExportSettingsService(ExportRepository repository, ProfileRegistry registry, ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.registry = registry;
        this.json = json;
        this.clock = clock;
    }

    public Effective effective(String tenantId) {
        Optional<ExportSettingsRow> row = repository.latestSettings(tenantId);
        if (row.isPresent()) {
            return new Effective(toDatev(row.get()), Source.DATABASE, row.get());
        }
        Datev yaml = registry.tenant(tenantId).map(Tenant::datev).orElse(null);
        return new Effective(yaml, yaml == null ? Source.NONE : Source.YAML, null);
    }

    /** Prüft und speichert neue Einstellungen als weiteren Datensatz (nichts wird überschrieben). */
    public ExportSettingsRow save(String tenantId, Datev datev, String user, String note) throws ExportException {
        if (registry.tenant(tenantId).isEmpty()) {
            throw new ExportException("Unbekannter Mandant: " + tenantId);
        }
        if (user == null || user.isBlank()) {
            throw new ExportException("Benutzername ist erforderlich");
        }
        List<String> errors = new ArrayList<>();
        ProfileRegistry.validateDatev(tenantId, datev, errors);
        if (!errors.isEmpty()) {
            throw new ExportException("Einstellungen ungültig:\n - " + String.join("\n - ", errors));
        }
        return repository.saveSettings(toRow(tenantId, datev, user.trim(), note));
    }

    /** Prüft und speichert je Rechnung ergänzte DATEV-Felder (Formate laut docs/datev-format-referenz.md). */
    public InvoiceExportFieldRow saveInvoiceFields(long sourceDocumentId, String serviceDate, String taxPeriodDate, String dueDate,
                                                   String buyerVatId, String user, String note) throws ExportException {
        if (user == null || user.isBlank()) {
            throw new ExportException("Benutzername ist erforderlich");
        }
        String service = isoDate(serviceDate, "Leistungsdatum (#115)");
        String period = isoDate(taxPeriodDate, "Datum Zuordnung Steuerperiode (#116)");
        String due = isoDate(dueDate, "Fälligkeit (#117)");
        if ((service == null) != (period == null)) {
            throw new ExportException("Leistungsdatum (#115) und Datum Zuordnung Steuerperiode (#116) müssen gemeinsam angegeben werden");
        }
        String vat = buyerVatId == null || buyerVatId.isBlank() ? null : buyerVatId.trim().replace(" ", "").toUpperCase();
        if (vat != null && !vat.matches("[A-Z]{2}[A-Z0-9]{1,13}")) {
            throw new ExportException("USt-IdNr. (#40) muss aus Länderkürzel und bis zu 13 Zeichen bestehen, z. B. DE133546770");
        }
        return repository.saveInvoiceFields(new InvoiceExportFieldRow(0, sourceDocumentId, clock.instant(), user.trim(), service, period, due,
                vat, note));
    }

    private static String isoDate(String value, String what) throws ExportException {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.LocalDate.parse(value.trim()).toString();
        } catch (RuntimeException e) {
            throw new ExportException(what + ": ungültiges Datum '" + value + "' (erwartet JJJJ-MM-TT)");
        }
    }

    Datev toDatev(ExportSettingsRow r) {
        try {
            Map<String, String> customers = r.customerAccountsJson() == null ? Map.of() : json.readValue(r.customerAccountsJson(), STRING_MAP);
            Map<String, RevenueAccount> revenues = r.revenueAccountsJson() == null ? Map.of() : json.readValue(r.revenueAccountsJson(), REVENUE_MAP);
            return new Datev(r.datevEnabled(), r.consultantNumber(), r.clientNumber(), r.fiscalYearStart(), r.accountLength(),
                    r.chartOfAccounts(), DebtorStrategy.valueOf(r.debtorStrategy()), r.collectiveDebtorAccount(), customers, revenues,
                    r.origin(), r.exportedBy(), r.dictationShortcut(), r.lockRecords(), r.bookingTextTemplate());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("export_settings #" + r.id() + " enthält ungültiges JSON", e);
        }
    }

    ExportSettingsRow toRow(String tenantId, Datev d, String user, String note) {
        try {
            return new ExportSettingsRow(0, tenantId, clock.instant(), user, note, d.enabled(), d.consultantNumber(), d.clientNumber(),
                    d.fiscalYearStart(), d.accountLength(), d.chartOfAccounts(), d.debtorStrategy().name(), d.collectiveDebtorAccount(),
                    json.writeValueAsString(new LinkedHashMap<>(d.customerAccounts())),
                    json.writeValueAsString(new LinkedHashMap<>(d.revenueAccounts())),
                    d.origin() == null ? "RE" : d.origin(), d.exportedBy() == null ? "" : d.exportedBy(),
                    d.dictationShortcut() == null ? "" : d.dictationShortcut(), d.lockRecords(),
                    d.bookingTextTemplate() == null ? "Rechnung {invoiceNumber} {customerName}" : d.bookingTextTemplate());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
