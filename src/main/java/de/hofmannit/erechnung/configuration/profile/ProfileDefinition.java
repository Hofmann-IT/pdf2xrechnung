package de.hofmannit.erechnung.configuration.profile;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.model.ArtifactType;
import de.hofmannit.erechnung.model.MappingRuleType;
import de.hofmannit.erechnung.model.OutputFormat;

import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Datenmodell eines Mandantenprofils ({@code profiles/*.yaml}). Vollständig dokumentiertes
 * Beispiel: {@code profiles/standard.yaml}.
 *
 * <p>Das Modell ist reine Konfiguration (keine Logik). Die Ausführung der Regeln erfolgt in den
 * Modulen {@code mapping}, {@code plausibility}, {@code generation} und {@code dispatch}.
 */
public record ProfileDefinition(
        Meta profile,
        Classification classification,
        List<FieldMapping> fields,
        LineItems lineItems,
        Plausibility plausibility,
        Generation generation,
        PostProcess postProcess) {

    public ProfileDefinition {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** Kopfdaten des Profils. */
    public record Meta(
            String name,
            String description,
            @DefaultValue("1") int schemaVersion) {
    }

    /** Ein Textindikator (Regex über den Seitentext). */
    public record Indicator(
            String regex,
            /** Seite, auf der gesucht wird; {@code null} = alle Seiten. */
            Integer page) {
    }

    /** Klassifizierung: Dokumenttyp und Geschäftsfall (Vorgabe Abschnitt 6). */
    public record Classification(
            List<Indicator> invoiceIndicators,
            List<Indicator> creditNoteIndicators,
            List<Indicator> nonInvoiceIndicators,
            List<BusinessCase> businessCases) {

        public Classification {
            invoiceIndicators = invoiceIndicators == null ? List.of() : List.copyOf(invoiceIndicators);
            creditNoteIndicators = creditNoteIndicators == null ? List.of() : List.copyOf(creditNoteIndicators);
            nonInvoiceIndicators = nonInvoiceIndicators == null ? List.of() : List.copyOf(nonInvoiceIndicators);
            businessCases = businessCases == null ? List.of() : List.copyOf(businessCases);
        }
    }

    /**
     * Geschäftsfall (Steuerbehandlung).
     *
     * @param id              z. B. DOMESTIC_STANDARD, NO_VAT, EU_REVERSE_CHARGE, THIRD_COUNTRY
     * @param vatCategoryCode BT-118/BT-151 (UNTDID 5305)
     * @param exemptionReasonText BT-120, sofern erforderlich
     * @param exemptionReasonCode BT-121, sofern erforderlich
     * @param indicators      Indikatoren; trifft einer, gilt der Geschäftsfall
     * @param defaultCase     genau ein Geschäftsfall darf Standard sein
     * @param defaultBuyerCountryCode BT-55, falls die PDF kein Käuferland ausweist (z. B. "DE" bei
     *                        Inlandsfällen); {@code null} = BT-55 muss extrahiert werden
     */
    public record BusinessCase(
            String id,
            String vatCategoryCode,
            String exemptionReasonText,
            String exemptionReasonCode,
            List<Indicator> indicators,
            @DefaultValue("false") boolean defaultCase,
            String defaultBuyerCountryCode) {

        public BusinessCase {
            indicators = indicators == null ? List.of() : List.copyOf(indicators);
        }
    }

    /**
     * Mapping eines Kopf-/Fußfeldes auf einen Business Term.
     *
     * @param businessTerm z. B. {@code BT-1}
     * @param required     Pflichtfeld: Fehlen führt zu REVIEW
     * @param rule         die Mapping-Regel
     * @param transform    Transformationen in Reihenfolge, z. B. {@code trim}, {@code date:dd.MM.yyyy},
     *                     {@code decimal:de}, {@code regexReplace:/\\s+//}, {@code upper}
     */
    public record FieldMapping(
            String businessTerm,
            @DefaultValue("false") boolean required,
            Rule rule,
            List<String> transform) {

        public FieldMapping {
            transform = transform == null ? List.of() : List.copyOf(transform);
        }
    }

    /**
     * Eine Mapping-Regel. Welche Attribute relevant sind, hängt von {@link #type()} ab
     * (siehe Kommentare in {@code profiles/standard.yaml}).
     */
    public record Rule(
            MappingRuleType type,
            /** Seite (1-basiert); {@code null} = erste Seite bei anchor/regex/region. */
            Integer page,
            // --- anchor ---
            String label,
            @DefaultValue("RIGHT") Direction direction,
            Double maxDistance,
            // --- regex ---
            String pattern,
            @DefaultValue("1") int group,
            // --- region ---
            Double x,
            Double y,
            Double width,
            Double height,
            @DefaultValue("JOIN_LINES") RegionMode regionMode,
            // --- fixed ---
            String key) {
    }

    /** Suchrichtung relativ zum Anker. */
    public enum Direction {
        RIGHT,
        BELOW
    }

    /** Behandlung mehrerer Textfragmente in einer Region. */
    public enum RegionMode {
        /** Alle Zeilen mit Zeilenumbruch verbinden. */
        JOIN_LINES,
        /** Nur die erste Zeile verwenden. */
        FIRST_LINE,
        /** Alle Fragmente mit Leerzeichen verbinden. */
        JOIN_WORDS
    }

    /** Tabellen-Mapping für Rechnungspositionen (Vorgabe Abschnitt 7, Tabellen-Mapping). */
    public record LineItems(
            /** Regex, der den Tabellenkopf (Beginn der Positionen) markiert. */
            Indicator startAnchor,
            /** Regex, der das Tabellenende markiert (z. B. "Zwischensumme"). */
            Indicator endAnchor,
            /** Der Tabellenkopf wiederholt sich auf Fortsetzungsseiten. */
            @DefaultValue("true") boolean headerRepeatsOnContinuationPages,
            /** Regex für Fortsetzungshinweise, die zu ignorieren sind (z. B. "Übertrag"). */
            List<Indicator> ignoreRows,
            /** Erkennung des Zeilenbeginns über die Schlüsselspalte. */
            RowDetection rowDetection,
            /** Beschreibungstexte dürfen sich über mehrere Textzeilen erstrecken. */
            @DefaultValue("true") boolean multiLineDescription,
            List<Column> columns) {

        public LineItems {
            ignoreRows = ignoreRows == null ? List.of() : List.copyOf(ignoreRows);
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    /**
     * Eine neue Position beginnt, wenn in der Schlüsselspalte ein Wert steht, der auf das Muster passt.
     */
    public record RowDetection(
            String keyColumnBusinessTerm,
            String pattern) {
    }

    /**
     * Spalte der Positionstabelle.
     *
     * @param businessTerm positionsbezogener Business Term (BT-126 ff.)
     * @param x            linke Kante der Spalte in PDF-Punkten
     * @param width        Breite der Spalte
     * @param align        Ausrichtung (für Zuordnung rechtsbündiger Beträge)
     * @param transform    Transformationen wie bei {@link FieldMapping}
     * @param required     Pflichtspalte je Position
     */
    public record Column(
            String businessTerm,
            double x,
            double width,
            @DefaultValue("LEFT") Align align,
            List<String> transform,
            @DefaultValue("false") boolean required) {

        public Column {
            transform = transform == null ? List.of() : List.copyOf(transform);
        }
    }

    public enum Align {
        LEFT,
        RIGHT
    }

    /** Plausibilitätsprüfung (Vorgabe Abschnitt 9). */
    public record Plausibility(
            @DefaultValue("0.01") BigDecimal tolerance,
            List<PlausibilityCheck> checks) {

        public Plausibility {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /** Die verpflichtenden Prüfungen (alle im Standardprofil aktiv). */
    public enum PlausibilityCheck {
        /** Je Position: BT-146 × BT-129 (÷ BT-149, sofern vorhanden) = BT-131. */
        LINE_AMOUNT,
        /** Summe BT-131 aller Positionen = BT-106. */
        LINE_SUM,
        /** BT-106 - BT-107 + BT-108 = BT-109. */
        NET_TOTAL,
        /** Summe BT-116 über alle Steuersätze = BT-109. */
        TAX_BASIS_PER_RATE,
        /** BT-116 × BT-119 / 100 = BT-117 je Steuersatz. */
        TAX_AMOUNT_PER_RATE,
        /** Summe BT-117 = BT-110. */
        TOTAL_TAX,
        /** BT-109 + BT-110 = BT-112. */
        GROSS_TOTAL,
        /** BT-112 - BT-113 + BT-114 = BT-115. */
        PAYABLE_AMOUNT
    }

    /** Erzeugung (Vorgabe Abschnitte 10 und 14). */
    public record Generation(
            List<OutputFormat> formats,
            /** Template ohne Endung, z. B. {@code {invoiceNumber}_{invoiceDate:yyyyMMdd}_{customerName}}. */
            String filenameTemplate,
            /** BT-3 je Dokumenttyp (UNTDID 1001). */
            Map<String, String> invoiceTypeCodes,
            /** BT-5, sofern nicht aus der PDF extrahiert. */
            @DefaultValue("EUR") String defaultCurrency,
            /** BT-130 je Position, sofern die Tabelle keine Einheit liefert (UN/ECE Rec. 20; C62 = Stück/Einheit). */
            @DefaultValue("C62") String defaultUnitCode) {

        public Generation {
            formats = formats == null ? List.of() : List.copyOf(formats);
            invoiceTypeCodes = invoiceTypeCodes == null ? Map.of() : Map.copyOf(invoiceTypeCodes);
        }
    }

    /** Optionaler Postprozess (Vorgabe Abschnitte 22 und 23). */
    public record PostProcess(
            Email email,
            Command command) {
    }

    /** E-Mail-Versand je Profil. */
    public record Email(
            @DefaultValue("false") boolean enabled,
            List<String> to,
            List<String> cc,
            String subjectTemplate,
            String bodyTemplate,
            List<ArtifactType> attachments) {

        public Email {
            to = to == null ? List.of() : List.copyOf(to);
            cc = cc == null ? List.of() : List.copyOf(cc);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }
    }

    /**
     * Lokales Programm nach erfolgreicher Verarbeitung. Executable und Argumente getrennt;
     * Platzhalter in Argumenten (z. B. {@code {zugferdPdf}}) werden je Argument ersetzt, niemals
     * durch eine Shell interpretiert.
     */
    public record Command(
            @DefaultValue("false") boolean enabled,
            String executable,
            List<String> arguments,
            @DefaultValue("60s") java.time.Duration timeout,
            String workingDirectory) {

        public Command {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }
    }
}
