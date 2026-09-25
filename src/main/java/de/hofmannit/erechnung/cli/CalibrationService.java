package de.hofmannit.erechnung.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;
import de.hofmannit.erechnung.configuration.profile.LoadedProfile;
import de.hofmannit.erechnung.configuration.profile.ProfileLoader;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.extraction.ExtractedDocument;
import de.hofmannit.erechnung.extraction.ExtractionException;
import de.hofmannit.erechnung.extraction.PdfTextExtractor;
import de.hofmannit.erechnung.mapping.ClassificationResult;
import de.hofmannit.erechnung.mapping.Classifier;
import de.hofmannit.erechnung.mapping.FieldEvidence;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.mapping.LineItemData;
import de.hofmannit.erechnung.mapping.MappingEngine;
import de.hofmannit.erechnung.plausibility.PlausibilityChecker;
import de.hofmannit.erechnung.plausibility.PlausibilityIssue;
import de.hofmannit.erechnung.plausibility.PlausibilityResult;

import org.springframework.stereotype.Service;

/**
 * Profil-Kalibrierung (Vorgabe Abschnitt 32): wendet ein Profil auf Beispiel-PDFs an und
 * erzeugt je Datei einen Report (gefunden / nicht gefunden, Wert, Business Term, Regel, Seite,
 * Position, Fehler/Warnung). Es wird nichts erzeugt, abgelegt, versendet oder im Ledger vermerkt.
 */
@Service
public class CalibrationService {

    private final PdfTextExtractor extractor;
    private final Classifier classifier;
    private final MappingEngine mapping;
    private final PlausibilityChecker plausibility;
    private final ProfileRegistry registry;

    public CalibrationService(PdfTextExtractor extractor, Classifier classifier, MappingEngine mapping,
                              PlausibilityChecker plausibility, ProfileRegistry registry) {
        this.extractor = extractor;
        this.classifier = classifier;
        this.mapping = mapping;
        this.plausibility = plausibility;
        this.registry = registry;
    }

    /**
     * @param samples  Verzeichnis mit PDF-Beispielen oder einzelne PDF
     * @param profile  Profil-YAML
     * @param tenantId Mandant für Fixed Values; {@code null} = erster Mandant
     */
    public String calibrate(Path samples, Path profile, String tenantId) throws IOException {
        LoadedProfile loaded = new ProfileLoader().load(profile);
        if (tenantId == null && registry.tenants().isEmpty()) {
            throw new IllegalArgumentException("Kein Mandant konfiguriert; --tenant ist erst nach der Einrichtung verfügbar");
        }
        Tenant tenant = tenantId == null ? registry.tenants().get(0)
                : registry.tenant(tenantId).orElseThrow(() -> new IllegalArgumentException("Unbekannter Mandant: " + tenantId));
        List<Path> pdfs = new ArrayList<>();
        if (Files.isRegularFile(samples)) {
            pdfs.add(samples);
        } else if (Files.isDirectory(samples)) {
            try (Stream<Path> s = Files.list(samples)) {
                pdfs.addAll(s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .sorted().toList());
            }
        } else {
            throw new IOException("Beispielverzeichnis existiert nicht: " + samples);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Profil-Kalibrierung\n===================\n");
        sb.append("Profil:   ").append(loaded.name()).append(" (").append(profile).append(", SHA-256 ").append(loaded.sha256()).append(")\n");
        sb.append("Mandant:  ").append(tenant.id()).append('\n');
        sb.append("Beispiele: ").append(pdfs.size()).append('\n');
        int ok = 0;
        for (Path pdf : pdfs) {
            sb.append("\n\n").append("#".repeat(78)).append('\n').append("# ").append(pdf.getFileName()).append('\n').append("#".repeat(78)).append('\n');
            if (reportFor(pdf, loaded, tenant.fixedValues(), sb)) {
                ok++;
            }
        }
        sb.append("\n\nErgebnis: ").append(ok).append(" von ").append(pdfs.size()).append(" Beispielen ohne Fehler und mit bestandener Plausibilität\n");
        return sb.toString();
    }

    private boolean reportFor(Path pdf, LoadedProfile profile, Map<String, String> fixedValues, StringBuilder sb) {
        ExtractedDocument doc;
        try {
            doc = extractor.extract(pdf);
        } catch (ExtractionException e) {
            sb.append("FEHLER: ").append(e.getMessage()).append('\n');
            return false;
        }
        ClassificationResult c = classifier.classify(doc, profile.definition());
        sb.append("Seiten: ").append(doc.pageCount()).append('\n');
        sb.append("Klassifizierung: ").append(c.isInvoice() ? c.documentType() + ", Geschäftsfall " + c.businessCase().id() : "NICHT-RECHNUNG").append('\n');
        for (String r : c.reasons()) {
            sb.append("  - ").append(r).append('\n');
        }
        if (!c.isInvoice()) {
            return false;
        }
        InvoiceData data = mapping.map(doc, profile.definition(), c, fixedValues);
        PlausibilityResult pl = plausibility.check(data, profile.definition().plausibility());

        sb.append("\nKopf-/Fußfelder\n");
        sb.append(String.format(Locale.ROOT, "%-7s %-8s %-38s %-32s %-5s %-24s %s%n", "BT", "Status", "Wert", "Regel", "Seite", "Position", "Hinweis"));
        boolean allOk = true;
        for (FieldEvidence e : data.fields().values()) {
            appendEvidence(sb, e);
            if (e.status() == FieldEvidence.FieldStatus.ERROR) {
                allOk = false;
            }
        }
        sb.append("\nPositionen: ").append(data.lines().size()).append('\n');
        for (LineItemData line : data.lines()) {
            sb.append("  Position ").append(line.lineNumber()).append('\n');
            for (FieldEvidence e : line.fields().values()) {
                sb.append("  ");
                appendEvidence(sb, e);
                if (e.status() == FieldEvidence.FieldStatus.ERROR) {
                    allOk = false;
                }
            }
        }
        sb.append("\nPlausibilität: ").append(pl.passed() ? "bestanden" : "NICHT bestanden").append('\n');
        for (PlausibilityIssue i : pl.issues()) {
            sb.append("  - ").append(i.check() == null ? "" : i.check() + ": ").append(i.message()).append('\n');
        }
        return allOk && pl.passed();
    }

    private static void appendEvidence(StringBuilder sb, FieldEvidence e) {
        String status = switch (e.status()) {
            case OK -> "gefunden";
            case WARNING -> "WARNUNG";
            case ERROR -> "FEHLER";
            case NOT_FOUND -> "fehlt";
        };
        String pos = e.boundingBox() == null ? "-" : String.format(Locale.ROOT, "x=%.0f y=%.0f w=%.0f h=%.0f",
                e.boundingBox().x(), e.boundingBox().y(), e.boundingBox().width(), e.boundingBox().height());
        sb.append(String.format(Locale.ROOT, "%-7s %-8s %-38s %-32s %-5s %-24s %s%n",
                e.businessTerm().id(), status, cut(e.value(), 38), cut(e.ruleId(), 32), e.page() == null ? "-" : e.page(), pos,
                e.message() == null ? "" : e.message()));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "-";
        }
        String one = s.replace('\n', '|');
        return one.length() > max ? one.substring(0, max - 1) + "…" : one;
    }
}
