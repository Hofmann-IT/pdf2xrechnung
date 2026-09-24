package de.hofmannit.erechnung.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Column;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.FieldMapping;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.LineItems;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Rule;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.ExtractedPage;
import de.hofmannit.erechnung.extraction.PositionedText;
import de.hofmannit.erechnung.extraction.TextLine;
import de.hofmannit.erechnung.mapping.FieldEvidence.BoundingBox;
import de.hofmannit.erechnung.mapping.FieldEvidence.FieldStatus;
import de.hofmannit.erechnung.mapping.Transformations.TransformationException;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.MappingRuleType;

import org.springframework.stereotype.Component;

/**
 * Wendet die Mapping-Regeln eines Profils auf ein extrahiertes Dokument an (Vorgabe Abschnitt 7)
 * und protokolliert je Feld den vollständigen Nachweis (Abschnitt 8).
 *
 * <p>Regelarten: anchor, regex, region, table (Positionen), fixed (Mandanten-Festwerte).
 * Die Engine verändert keine fachlichen Werte außer über die im Profil deklarierten
 * Transformationen.
 */
@Component
public class MappingEngine {

    public InvoiceData map(ExtractedDocument document, ProfileDefinition profile, ClassificationResult classification,
                           Map<String, String> fixedValues) {
        Map<BusinessTerm, FieldEvidence> fields = new LinkedHashMap<>();
        for (int i = 0; i < profile.fields().size(); i++) {
            FieldMapping fm = profile.fields().get(i);
            BusinessTerm bt = BusinessTerm.fromId(fm.businessTerm()).orElseThrow();
            String ruleId = "fields[" + i + "]:" + bt.id();
            FieldEvidence evidence = mapField(document, fm, ruleId, fixedValues);
            // Ein bereits gefundener Wert wird durch eine spätere, nicht gefundene Regel nicht überschrieben.
            FieldEvidence existing = fields.get(bt);
            if (existing == null || isBetter(evidence, existing)) {
                fields.put(bt, evidence);
            }
        }
        List<LineItemData> lines = profile.lineItems() == null ? List.of() : mapLineItems(document, profile.lineItems());
        var bc = classification.businessCase();
        // Käuferland (BT-55) aus dem Geschäftsfall, wenn die PDF keines ausweist (Inlandsrechnungen drucken es selten).
        FieldEvidence bt55 = fields.get(BusinessTerm.BT_55);
        if (bc != null && bc.defaultBuyerCountryCode() != null && !bc.defaultBuyerCountryCode().isBlank()
                && (bt55 == null || bt55.value() == null || bt55.status() == FieldStatus.NOT_FOUND || bt55.status() == FieldStatus.ERROR)) {
            fields.put(BusinessTerm.BT_55, new FieldEvidence(BusinessTerm.BT_55, null, bc.defaultBuyerCountryCode().trim(),
                    "classification.businessCases[" + bc.id() + "].defaultBuyerCountryCode", MappingRuleType.FIXED, null, null, null,
                    "Standardwert des Geschäftsfalls " + bc.id(), FieldStatus.OK, null));
        }
        return new InvoiceData(
                classification.documentType(),
                bc == null ? null : bc.id(),
                bc == null ? null : bc.vatCategoryCode(),
                bc == null ? null : bc.exemptionReasonText(),
                bc == null ? null : bc.exemptionReasonCode(),
                fields, lines, document.pageCount());
    }

    private static boolean isBetter(FieldEvidence candidate, FieldEvidence existing) {
        return rank(candidate.status()) < rank(existing.status());
    }

    private static int rank(FieldStatus s) {
        return switch (s) {
            case OK -> 0;
            case WARNING -> 1;
            case ERROR -> 2;
            case NOT_FOUND -> 3;
        };
    }

    // ------------------------------------------------------------------ Kopf-/Fußfelder

    FieldEvidence mapField(ExtractedDocument document, FieldMapping fm, String ruleId, Map<String, String> fixedValues) {
        BusinessTerm bt = BusinessTerm.fromId(fm.businessTerm()).orElseThrow();
        Rule rule = fm.rule();
        RawHit hit = switch (rule.type()) {
            case ANCHOR -> anchor(document, rule);
            case REGEX -> regex(document, rule);
            case REGION -> region(document, rule);
            case FIXED -> fixed(rule, fixedValues);
            case TABLE -> null;
        };
        if (hit == null) {
            FieldStatus status = fm.required() ? FieldStatus.ERROR : FieldStatus.NOT_FOUND;
            String message = fm.required() ? "Pflichtfeld nicht gefunden" : "nicht gefunden";
            return new FieldEvidence(bt, null, null, ruleId, rule.type(), rule.page(), null, null,
                    describe(rule), status, message);
        }
        return transform(bt, null, hit, ruleId, rule.type(), fm.transform(), describe(rule));
    }

    static FieldEvidence transform(BusinessTerm bt, Integer lineNumber, RawHit hit, String ruleId, MappingRuleType type,
                                   List<String> transforms, String ruleDescription) {
        String transformation = transforms.isEmpty() ? ruleDescription : ruleDescription + " | " + String.join(", ", transforms);
        try {
            String value = Transformations.apply(hit.text(), transforms);
            FieldStatus status = value == null || value.isBlank() ? FieldStatus.NOT_FOUND : FieldStatus.OK;
            return new FieldEvidence(bt, lineNumber, value, ruleId, type, hit.page(), hit.box(), hit.text(), transformation,
                    status, status == FieldStatus.OK ? null : "leerer Wert nach Transformation");
        } catch (TransformationException e) {
            return new FieldEvidence(bt, lineNumber, null, ruleId, type, hit.page(), hit.box(), hit.text(), transformation,
                    FieldStatus.ERROR, e.getMessage());
        }
    }

    private static String describe(Rule r) {
        return switch (r.type()) {
            case ANCHOR -> "anchor '" + r.label() + "' " + r.direction() + (r.maxDistance() != null ? " ≤" + r.maxDistance() + "pt" : "");
            case REGEX -> "regex " + r.pattern() + " Gruppe " + r.group();
            case REGION -> "region x=" + r.x() + " y=" + r.y() + " w=" + r.width() + " h=" + r.height() + " " + r.regionMode();
            case FIXED -> "fixed tenant.yaml[" + r.key() + "]";
            case TABLE -> "table";
        };
    }

    /** Roh-Treffer einer Regel. */
    record RawHit(String text, Integer page, BoundingBox box) {
    }

    private static List<ExtractedPage> pagesFor(ExtractedDocument document, Integer page, boolean defaultFirst) {
        if (page != null) {
            return document.page(page).map(List::of).orElse(List.of());
        }
        if (defaultFirst) {
            return document.page(1).map(List::of).orElse(List.of());
        }
        return document.pages();
    }

    // ---- anchor: Label finden, Wert rechts (gleiche Zeile) oder unterhalb (nächste Zeile)
    static RawHit anchor(ExtractedDocument document, Rule rule) {
        String label = rule.label().trim();
        double maxDistance = rule.maxDistance() == null ? Double.MAX_VALUE : rule.maxDistance();
        for (ExtractedPage page : pagesFor(document, rule.page(), false)) {
            List<TextLine> lines = page.lines();
            for (int li = 0; li < lines.size(); li++) {
                TextLine line = lines.get(li);
                LabelPos lp = findLabel(line, label);
                if (lp == null) {
                    continue;
                }
                if (rule.direction() == ProfileDefinition.Direction.RIGHT) {
                    List<PositionedText> right = new ArrayList<>();
                    for (PositionedText f : line.fragments()) {
                        if (f.x() >= lp.right - 0.5 && f.x() - lp.right <= maxDistance && !right.contains(f)) {
                            right.add(f);
                        }
                    }
                    // Label-Fragmente selbst ausschließen (sie enden bei lp.right)
                    right.removeIf(f -> f.right() <= lp.right + 0.01);
                    // Das Label kann mit dem Wert in einem Fragment stehen ("Rechnungsnummer: 4711")
                    if (right.isEmpty() && lp.trailing != null && !lp.trailing.isBlank()) {
                        return new RawHit(lp.trailing.trim(), page.pageNumber(), lp.trailingBox);
                    }
                    if (!right.isEmpty()) {
                        return new RawHit(join(right), page.pageNumber(), union(right));
                    }
                } else {
                    for (int lj = li + 1; lj < lines.size(); lj++) {
                        TextLine below = lines.get(lj);
                        if (below.top() - line.bottom() > maxDistance) {
                            break;
                        }
                        List<PositionedText> under = new ArrayList<>();
                        for (PositionedText f : below.fragments()) {
                            if (f.right() >= lp.left - 2 && f.x() <= lp.right + maxDistance) {
                                under.add(f);
                            }
                        }
                        if (!under.isEmpty()) {
                            return new RawHit(join(under), page.pageNumber(), union(under));
                        }
                    }
                }
            }
        }
        return null;
    }

    private record LabelPos(double left, double right, String trailing, BoundingBox trailingBox) {
    }

    /** Sucht das Label als Folge von Fragmenten innerhalb einer Zeile (Label kann mehrere Wörter umfassen). */
    private static LabelPos findLabel(TextLine line, String label) {
        List<PositionedText> frags = line.fragments();
        String[] words = label.split("\\s+");
        for (int start = 0; start < frags.size(); start++) {
            int wi = 0;
            int fi = start;
            String trailing = null;
            BoundingBox trailingBox = null;
            boolean matched = true;
            while (wi < words.length) {
                if (fi >= frags.size()) {
                    matched = false;
                    break;
                }
                String ft = frags.get(fi).text();
                String w = words[wi];
                if (wi == words.length - 1 && ft.startsWith(w) && ft.length() > w.length()) {
                    // letztes Label-Wort mit angehängtem Wert im selben Fragment
                    trailing = ft.substring(w.length());
                    trailingBox = new BoundingBox(frags.get(fi).x(), frags.get(fi).y(), frags.get(fi).width(), frags.get(fi).height());
                    wi++;
                    fi++;
                } else if (ft.equals(w)) {
                    wi++;
                    fi++;
                } else {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                double left = frags.get(start).x();
                double right = trailing != null ? frags.get(fi - 1).x() : frags.get(fi - 1).right();
                if (trailing != null) {
                    // rechte Kante des Labels approximieren: Anteil des Labelworts an der Fragmentbreite
                    PositionedText f = frags.get(fi - 1);
                    double share = (double) words[words.length - 1].length() / f.text().length();
                    right = f.x() + f.width() * share;
                }
                return new LabelPos(left, right, trailing, trailingBox);
            }
        }
        return null;
    }

    // ---- regex über den zeilenweisen Seitentext
    static RawHit regex(ExtractedDocument document, Rule rule) {
        Pattern p = Pattern.compile(rule.pattern(), Pattern.MULTILINE);
        for (ExtractedPage page : pagesFor(document, rule.page(), false)) {
            String text = page.text();
            Matcher m = p.matcher(text);
            if (m.find()) {
                int group = Math.min(rule.group(), m.groupCount());
                String value = m.group(group);
                if (value == null) {
                    continue;
                }
                BoundingBox box = locate(page, text, m.start(group), m.end(group));
                return new RawHit(value, page.pageNumber(), box);
            }
        }
        return null;
    }

    /** Ermittelt die Bounding Box des Treffers über die Zeile, in der der Treffer liegt. */
    private static BoundingBox locate(ExtractedPage page, String text, int start, int end) {
        int lineIndex = 0;
        int offset = 0;
        for (TextLine line : page.lines()) {
            int len = line.text().length() + 1;
            if (start < offset + len) {
                // Fragmente der Zeile, deren Text im Treffer vorkommt
                String hit = text.substring(start, Math.min(end, text.length()));
                List<PositionedText> parts = new ArrayList<>();
                for (PositionedText f : line.fragments()) {
                    if (hit.contains(f.text()) || f.text().contains(hit)) {
                        parts.add(f);
                    }
                }
                if (parts.isEmpty()) {
                    return new BoundingBox(line.left(), line.top(), line.right() - line.left(), line.bottom() - line.top());
                }
                return union(parts);
            }
            offset += len;
            lineIndex++;
        }
        return null;
    }

    // ---- region: Fragmente, deren Mittelpunkt im Rechteck liegt
    static RawHit region(ExtractedDocument document, Rule rule) {
        double x0 = rule.x();
        double y0 = rule.y();
        double x1 = x0 + rule.width();
        double y1 = y0 + rule.height();
        for (ExtractedPage page : pagesFor(document, rule.page(), true)) {
            List<TextLine> hitLines = new ArrayList<>();
            for (TextLine line : page.lines()) {
                List<PositionedText> inside = new ArrayList<>();
                for (PositionedText f : line.fragments()) {
                    double cx = f.x() + f.width() / 2;
                    double cy = f.y() + f.height() / 2;
                    if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1) {
                        inside.add(f);
                    }
                }
                if (!inside.isEmpty()) {
                    hitLines.add(new TextLine(page.pageNumber(), line.top(), line.bottom(), inside, join(inside)));
                }
            }
            if (hitLines.isEmpty()) {
                continue;
            }
            List<PositionedText> all = new ArrayList<>();
            hitLines.forEach(l -> all.addAll(l.fragments()));
            String text = switch (rule.regionMode()) {
                case FIRST_LINE -> hitLines.get(0).text();
                case JOIN_LINES -> String.join("\n", hitLines.stream().map(TextLine::text).toList());
                case JOIN_WORDS -> join(all);
            };
            List<PositionedText> used = rule.regionMode() == ProfileDefinition.RegionMode.FIRST_LINE ? hitLines.get(0).fragments() : all;
            return new RawHit(text, page.pageNumber(), union(used));
        }
        return null;
    }

    // ---- fixed
    static RawHit fixed(Rule rule, Map<String, String> fixedValues) {
        String v = fixedValues.get(rule.key());
        return v == null || v.isBlank() ? null : new RawHit(v, null, null);
    }

    // ------------------------------------------------------------------ Positionen (table)

    List<LineItemData> mapLineItems(ExtractedDocument document, LineItems cfg) {
        Pattern start = Pattern.compile(cfg.startAnchor().regex());
        Pattern end = cfg.endAnchor() == null || cfg.endAnchor().regex() == null ? null : Pattern.compile(cfg.endAnchor().regex());
        List<Pattern> ignore = cfg.ignoreRows().stream().map(i -> Pattern.compile(i.regex())).toList();
        Pattern rowKey = Pattern.compile(cfg.rowDetection().pattern());
        BusinessTerm keyBt = BusinessTerm.fromId(cfg.rowDetection().keyColumnBusinessTerm()).orElseThrow();
        Optional<Column> keyColumn = cfg.columns().stream()
                .filter(c -> BusinessTerm.fromId(c.businessTerm()).orElse(null) == keyBt).findFirst();
        Optional<Column> descriptionColumn = cfg.columns().stream()
                .filter(c -> BusinessTerm.fromId(c.businessTerm()).orElse(null) == BusinessTerm.BT_153).findFirst();

        List<List<TextLine>> rows = new ArrayList<>();
        List<TextLine> currentRow = null;
        boolean inTable = false;
        boolean finished = false;

        for (ExtractedPage page : document.pages()) {
            if (finished) {
                break;
            }
            boolean pageActive = inTable && !cfg.headerRepeatsOnContinuationPages();
            for (TextLine line : page.lines()) {
                String text = line.text();
                if (!pageActive) {
                    if (start.matcher(text).find()) {
                        pageActive = true;
                        inTable = true;
                    }
                    continue;
                }
                if (end != null && end.matcher(text).find()) {
                    finished = true;
                    break;
                }
                if (ignore.stream().anyMatch(p -> p.matcher(text).find())) {
                    continue;
                }
                if (start.matcher(text).find()) {
                    continue; // wiederholter Tabellenkopf
                }
                String keyCell = keyColumn.map(c -> cellText(line, c)).orElse("");
                if (rowKey.matcher(keyCell.trim()).matches()) {
                    currentRow = new ArrayList<>();
                    currentRow.add(line);
                    rows.add(currentRow);
                } else if (currentRow != null && cfg.multiLineDescription()) {
                    currentRow.add(line);
                }
            }
            if (!cfg.headerRepeatsOnContinuationPages()) {
                pageActive = inTable;
            }
        }

        List<LineItemData> items = new ArrayList<>();
        int number = 0;
        for (List<TextLine> row : rows) {
            number++;
            Map<BusinessTerm, FieldEvidence> cells = new LinkedHashMap<>();
            TextLine first = row.get(0);
            for (int ci = 0; ci < cfg.columns().size(); ci++) {
                Column col = cfg.columns().get(ci);
                BusinessTerm bt = BusinessTerm.fromId(col.businessTerm()).orElseThrow();
                String ruleId = "lineItems.columns[" + ci + "]:" + bt.id();
                List<PositionedText> parts = new ArrayList<>(cellFragments(first, col));
                boolean isDescription = descriptionColumn.isPresent() && descriptionColumn.get() == col;
                if (isDescription) {
                    for (int r = 1; r < row.size(); r++) {
                        parts.addAll(cellFragments(row.get(r), col));
                    }
                }
                String desc = "table Spalte x=" + col.x() + " w=" + col.width() + " " + col.align();
                if (parts.isEmpty()) {
                    FieldStatus status = col.required() ? FieldStatus.ERROR : FieldStatus.NOT_FOUND;
                    cells.put(bt, new FieldEvidence(bt, number, null, ruleId, MappingRuleType.TABLE, first.page(), null, null, desc,
                            status, col.required() ? "Pflichtspalte leer" : "leer"));
                    continue;
                }
                String raw = isDescription && row.size() > 1
                        ? String.join("\n", rowTexts(row, col))
                        : join(parts);
                RawHit hit = new RawHit(raw, first.page(), union(parts));
                cells.put(bt, transform(bt, number, hit, ruleId, MappingRuleType.TABLE, col.transform(), desc));
            }
            items.add(new LineItemData(number, cells));
        }
        return items;
    }

    private static List<String> rowTexts(List<TextLine> row, Column col) {
        List<String> texts = new ArrayList<>();
        for (TextLine line : row) {
            List<PositionedText> parts = cellFragments(line, col);
            if (!parts.isEmpty()) {
                texts.add(join(parts));
            }
        }
        return texts;
    }

    private static String cellText(TextLine line, Column col) {
        return join(cellFragments(line, col));
    }

    /**
     * Fragmente einer Spalte: linksbündig → Fragment beginnt im Spaltenbereich;
     * rechtsbündig → Fragment endet im Spaltenbereich.
     */
    private static List<PositionedText> cellFragments(TextLine line, Column col) {
        double x0 = col.x();
        double x1 = col.x() + col.width();
        List<PositionedText> result = new ArrayList<>();
        for (PositionedText f : line.fragments()) {
            double probe = col.align() == ProfileDefinition.Align.RIGHT ? f.right() : f.x();
            if (probe >= x0 - 0.5 && probe <= x1 + 0.5) {
                result.add(f);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ Hilfsfunktionen

    private static String join(List<PositionedText> parts) {
        StringBuilder sb = new StringBuilder();
        for (PositionedText p : parts) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(p.text());
        }
        return sb.toString();
    }

    private static BoundingBox union(List<PositionedText> parts) {
        double x0 = Double.MAX_VALUE;
        double y0 = Double.MAX_VALUE;
        double x1 = -Double.MAX_VALUE;
        double y1 = -Double.MAX_VALUE;
        for (PositionedText p : parts) {
            x0 = Math.min(x0, p.x());
            y0 = Math.min(y0, p.y());
            x1 = Math.max(x1, p.right());
            y1 = Math.max(y1, p.bottom());
        }
        return new BoundingBox(x0, y0, x1 - x0, y1 - y0);
    }
}
