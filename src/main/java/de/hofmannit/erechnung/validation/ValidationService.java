package de.hofmannit.erechnung.validation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import de.hofmannit.erechnung.model.OutputFormat;

import org.mustangproject.ZUGFeRD.ZUGFeRDInvoiceImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Formatabhängige Validierung nach der Anwendbarkeitsmatrix aus ADR 0002:
 *
 * <pre>
 * XRechnung CII / UBL : KoSIT verpflichtend, Mustang zusätzlich
 * ZUGFeRD XRECHNUNG   : Mustang verpflichtend, KoSIT auf die eingebettete XML verpflichtend
 * ZUGFeRD EN16931     : Mustang verpflichtend, KoSIT (EN16931-Szenario) auf die eingebettete XML zusätzlich
 * </pre>
 *
 * Nicht anwendbare Kombinationen liefern {@code NOT_APPLICABLE} und blockieren nie.
 */
@Component
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);

    private final KositValidator kosit;
    private final MustangValidator mustang;

    public ValidationService(KositValidator kosit, MustangValidator mustang) {
        this.kosit = kosit;
        this.mustang = mustang;
    }

    /**
     * @param workDir Verzeichnis für temporär extrahierte XML (ZUGFeRD)
     */
    public List<ValidationReport> validate(OutputFormat format, Path artifact, Path workDir) {
        List<ValidationReport> reports = new ArrayList<>();
        switch (format) {
            case XRECHNUNG_CII, XRECHNUNG_UBL -> {
                reports.add(kosit.validate(format, true, artifact));
                reports.add(mustang.validate(format, false, artifact));
            }
            case ZUGFERD_EN16931, ZUGFERD_XRECHNUNG -> {
                reports.add(mustang.validate(format, true, artifact));
                boolean kositMandatory = format == OutputFormat.ZUGFERD_XRECHNUNG;
                try {
                    Path embedded = extractEmbeddedXml(artifact, workDir);
                    reports.add(kosit.validate(format, kositMandatory, embedded));
                } catch (Exception e) {
                    log.warn("Eingebettete XML konnte nicht extrahiert werden: {}", e.toString());
                    reports.add(new ValidationReport(ValidatorKind.KOSIT, format, kositMandatory, ValidationOutcome.ERROR, null,
                            List.of(), null, null, Instant.now(), "Eingebettete XML nicht extrahierbar: " + e.getMessage()));
                }
            }
        }
        return reports;
    }

    /** Liest die eingebettete E-Rechnungs-XML einer ZUGFeRD-PDF (verifiziert: ZUGFeRDInvoiceImporter#getUTF8). */
    static Path extractEmbeddedXml(Path zugferdPdf, Path workDir) throws IOException {
        ZUGFeRDInvoiceImporter importer = new ZUGFeRDInvoiceImporter(zugferdPdf.toString());
        String xml = importer.getUTF8();
        if (xml == null || xml.isBlank()) {
            throw new IOException("keine eingebettete XML gefunden");
        }
        Path target = workDir.resolve(zugferdPdf.getFileName().toString().replaceAll("\\.pdf$", "") + "-embedded.xml");
        Files.write(target, xml.getBytes(StandardCharsets.UTF_8));
        return target;
    }

    public static boolean allMandatoryValid(List<ValidationReport> reports) {
        return reports.stream().noneMatch(ValidationReport::blocks);
    }
}
