package de.hofmannit.erechnung.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.DocumentType;

/**
 * Ergebnis des Mappings: alle Kopf-/Fußfelder und Positionen mit vollständigem Nachweis
 * (Vorgabe Abschnitte 7 und 8).
 *
 * @param documentType   Rechnung oder Gutschrift
 * @param businessCaseId Geschäftsfall aus dem Profil (z. B. DOMESTIC_STANDARD)
 * @param vatCategoryCode BT-118/BT-151 des Geschäftsfalls
 * @param exemptionReasonText BT-120, sofern konfiguriert
 * @param exemptionReasonCode BT-121, sofern konfiguriert
 * @param fields         Kopf-/Fußfelder je Business Term (auch nicht gefundene, mit Status)
 * @param lines          Positionen
 * @param pageCount      Seitenzahl des Dokuments
 */
public record InvoiceData(
        DocumentType documentType,
        String businessCaseId,
        String vatCategoryCode,
        String exemptionReasonText,
        String exemptionReasonCode,
        Map<BusinessTerm, FieldEvidence> fields,
        List<LineItemData> lines,
        int pageCount) {

    public InvoiceData {
        fields = Map.copyOf(fields);
        lines = List.copyOf(lines);
    }

    /** Wert eines Feldes, sofern gefunden und nicht fehlerhaft. */
    public Optional<String> value(BusinessTerm bt) {
        FieldEvidence e = fields.get(bt);
        if (e == null || e.value() == null || e.status() == FieldEvidence.FieldStatus.ERROR
                || e.status() == FieldEvidence.FieldStatus.NOT_FOUND) {
            return Optional.empty();
        }
        return Optional.of(e.value());
    }

    public Optional<BigDecimal> decimal(BusinessTerm bt) {
        return value(bt).map(InvoiceData::toDecimal);
    }

    public Optional<LocalDate> date(BusinessTerm bt) {
        return value(bt).map(LocalDate::parse);
    }

    /** Alle Nachweise inklusive Positionen, für Extraktionsreport und UI. */
    public List<FieldEvidence> allEvidence() {
        List<FieldEvidence> all = new ArrayList<>(fields.values());
        for (LineItemData line : lines) {
            all.addAll(line.fields().values());
        }
        return all;
    }

    static BigDecimal toDecimal(String canonical) {
        return new BigDecimal(canonical);
    }
}
