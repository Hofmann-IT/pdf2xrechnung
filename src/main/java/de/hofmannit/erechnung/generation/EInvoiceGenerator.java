package de.hofmannit.erechnung.generation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilder;

import de.hofmannit.erechnung.configuration.ApplicationVersion;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition;
import de.hofmannit.erechnung.mapping.InvoiceData;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.model.DocumentType;
import de.hofmannit.erechnung.model.OutputFormat;
import de.hofmannit.erechnung.security.SecureXml;

import com.helger.commons.error.IError;
import com.helger.commons.error.list.ErrorList;
import com.helger.en16931.cii2ubl.CIIToUBL21Converter;
import com.helger.ubl21.UBL21Marshaller;
import oasis.names.specification.ubl.schema.xsd.creditnote_21.CreditNoteType;
import oasis.names.specification.ubl.schema.xsd.invoice_21.InvoiceType;
import org.mustangproject.Invoice;
import org.mustangproject.ZUGFeRD.Profile;
import org.mustangproject.ZUGFeRD.Profiles;
import org.mustangproject.ZUGFeRD.ZUGFeRD2PullProvider;
import org.mustangproject.ZUGFeRD.ZUGFeRDExporterFromA3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Erzeugt XRechnung CII, XRechnung UBL und ZUGFeRD/Factur-X (PDF/A-3) mit Mustangproject
 * (Vorgabe Abschnitt 10, ADR 0002).
 *
 * <ul>
 *   <li>CII: {@link ZUGFeRD2PullProvider} mit Profil {@code XRECHNUNG}</li>
 *   <li>UBL: CII → UBL über den in Mustang enthaltenen Konverter ({@code en16931-cii2ubl}),
 *       CustomizationID/ProfileID auf XRechnung gesetzt</li>
 *   <li>ZUGFeRD: {@link ZUGFeRDExporterFromA3} auf Basis der unveränderten Quell-PDF;
 *       die Quell-PDF muss bereits PDF/A sein, andernfalls ist das Ergebnis kein gültiges
 *       PDF/A-3 und scheitert in der Mustang-Validierung</li>
 * </ul>
 *
 * <p>Nach der CII-Erzeugung werden die von Mustang berechneten Summen (BT-112, BT-115) gegen
 * die aus der PDF extrahierten Werte geprüft. Weichen sie ab, wird nichts abgelegt.
 */
@Component
public class EInvoiceGenerator {

    private static final Logger log = LoggerFactory.getLogger(EInvoiceGenerator.class);
    private static final String NS_RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
    /** BT-23 für XRechnung (Peppol-Geschäftsprozess), wie von der XRechnung-Spezifikation vorgegeben. */
    static final String XRECHNUNG_PROFILE_ID = "urn:fdc:peppol.eu:2017:poacc:billing:01:1.0";
    /** Toleranz für den Summenabgleich, identisch zur Plausibilitätstoleranz (Vorgabe Abschnitt 9). */
    static final BigDecimal TOTALS_TOLERANCE = new BigDecimal("0.01");

    private final InvoiceModelBuilder modelBuilder;
    private final ApplicationVersion applicationVersion;

    public EInvoiceGenerator(InvoiceModelBuilder modelBuilder, ApplicationVersion applicationVersion) {
        this.modelBuilder = modelBuilder;
        this.applicationVersion = applicationVersion;
    }

    /**
     * Erzeugt alle im Profil konfigurierten Formate im Arbeitsverzeichnis.
     *
     * @param sourcePdf  unveränderte Quell-PDF
     * @param workDir    Arbeitsverzeichnis des Runs (existiert)
     * @param baseName   Basis-Dateiname laut Template (ohne Endung)
     */
    public List<GeneratedArtifact> generate(InvoiceData data, ProfileDefinition profile, Path sourcePdf, Path workDir, String baseName)
            throws GenerationException {
        Invoice invoice = modelBuilder.build(data, profile);
        List<OutputFormat> formats = profile.generation().formats();
        List<GeneratedArtifact> result = new ArrayList<>();

        byte[] ciiXRechnung = generateCii(invoice, Profiles.getByName("XRECHNUNG"));
        verifyTotals(ciiXRechnung, data);

        if (formats.contains(OutputFormat.XRECHNUNG_CII)) {
            Path p = write(workDir, "invoice-cii.xml", ciiXRechnung);
            result.add(new GeneratedArtifact(OutputFormat.XRECHNUNG_CII, p, baseName + GeneratedArtifact.suffix(OutputFormat.XRECHNUNG_CII) + ".xml"));
        }
        if (formats.contains(OutputFormat.XRECHNUNG_UBL)) {
            byte[] ubl = generateUbl(ciiXRechnung, data.documentType(), workDir);
            Path p = write(workDir, "invoice-ubl.xml", ubl);
            result.add(new GeneratedArtifact(OutputFormat.XRECHNUNG_UBL, p, baseName + GeneratedArtifact.suffix(OutputFormat.XRECHNUNG_UBL) + ".xml"));
        }
        if (formats.contains(OutputFormat.ZUGFERD_EN16931) && formats.contains(OutputFormat.ZUGFERD_XRECHNUNG)) {
            throw new GenerationException("Profil darf nur eines der Formate ZUGFERD_EN16931 und ZUGFERD_XRECHNUNG enthalten");
        }
        for (OutputFormat zf : List.of(OutputFormat.ZUGFERD_EN16931, OutputFormat.ZUGFERD_XRECHNUNG)) {
            if (formats.contains(zf)) {
                Path p = workDir.resolve("invoice-zugferd.pdf");
                generateZugferd(sourcePdf, invoice, zf, p);
                result.add(new GeneratedArtifact(zf, p, baseName + GeneratedArtifact.suffix(zf) + ".pdf"));
            }
        }
        return result;
    }

    byte[] generateCii(Invoice invoice, Profile profile) throws GenerationException {
        try {
            ZUGFeRD2PullProvider provider = new ZUGFeRD2PullProvider();
            provider.setProfile(profile);
            provider.generateXML(invoice);
            byte[] xml = provider.getXML();
            if (xml == null || xml.length == 0) {
                throw new GenerationException("Mustang lieferte keine CII-XML");
            }
            return xml;
        } catch (RuntimeException e) {
            throw new GenerationException("CII-Erzeugung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    byte[] generateUbl(byte[] ciiXml, DocumentType type, Path workDir) throws GenerationException {
        try {
            Path tmp = Files.createTempFile(workDir, "cii-", ".xml");
            try {
                Files.write(tmp, ciiXml);
                ErrorList errors = new ErrorList();
                Object ubl = new CIIToUBL21Converter()
                        .setCustomizationID(Profiles.getByName("XRECHNUNG").getID())
                        .setProfileID(XRECHNUNG_PROFILE_ID)
                        .convertCIItoUBL(tmp.toFile(), errors);
                if (!errors.getAllErrors().isEmpty() || ubl == null) {
                    StringBuilder sb = new StringBuilder("CII→UBL-Konvertierung fehlgeschlagen");
                    for (IError e : errors.getAllErrors()) {
                        sb.append("; ").append(e.getAsString(Locale.GERMAN));
                    }
                    throw new GenerationException(sb.toString());
                }
                byte[] bytes;
                if (ubl instanceof InvoiceType inv) {
                    bytes = UBL21Marshaller.invoice().setFormattedOutput(true).getAsBytes(inv);
                } else if (ubl instanceof CreditNoteType cn) {
                    bytes = UBL21Marshaller.creditNote().setFormattedOutput(true).getAsBytes(cn);
                } else {
                    throw new GenerationException("Unerwarteter UBL-Typ: " + ubl.getClass().getName());
                }
                if (bytes == null || bytes.length == 0) {
                    throw new GenerationException("UBL-Serialisierung lieferte keine Daten");
                }
                return bytes;
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new GenerationException("UBL-Erzeugung fehlgeschlagen: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new GenerationException("UBL-Erzeugung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    void generateZugferd(Path sourcePdf, Invoice invoice, OutputFormat format, Path target) throws GenerationException {
        String profileName = format == OutputFormat.ZUGFERD_XRECHNUNG ? "XRECHNUNG" : "EN16931";
        try {
            ZUGFeRDExporterFromA3 exporter = new ZUGFeRDExporterFromA3()
                    .setProducer("PDF-zu-E-Rechnung " + applicationVersion.value())
                    .setCreator("PDF-zu-E-Rechnung")
                    .setProfile(Profiles.getByName(profileName));
            exporter.load(sourcePdf.toString());
            exporter.setTransaction(invoice);
            if (Files.exists(target)) {
                throw new GenerationException("Zieldatei existiert bereits: " + target);
            }
            exporter.export(target.toString());
        } catch (IOException e) {
            throw new GenerationException("ZUGFeRD-Erzeugung fehlgeschlagen: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new GenerationException("ZUGFeRD-Erzeugung fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /**
     * Vergleicht die von Mustang berechneten Summen mit den aus der PDF extrahierten Werten
     * (BT-109, BT-110, BT-112, BT-115). Abweichung über Toleranz = Fehler, es wird nichts abgelegt.
     */
    void verifyTotals(byte[] ciiXml, InvoiceData data) throws GenerationException {
        Map<String, BusinessTerm> checks = Map.of(
                "TaxBasisTotalAmount", BusinessTerm.BT_109,
                "TaxTotalAmount", BusinessTerm.BT_110,
                "GrandTotalAmount", BusinessTerm.BT_112,
                "DuePayableAmount", BusinessTerm.BT_115);
        try {
            DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
            Document doc = builder.parse(new ByteArrayInputStream(ciiXml));
            List<String> problems = new ArrayList<>();
            for (Map.Entry<String, BusinessTerm> en : checks.entrySet()) {
                Optional<BigDecimal> extracted = data.decimal(en.getValue());
                if (extracted.isEmpty()) {
                    continue;
                }
                NodeList nodes = doc.getElementsByTagNameNS(NS_RAM, en.getKey());
                if (nodes.getLength() == 0) {
                    problems.add(en.getKey() + " fehlt in der erzeugten XML");
                    continue;
                }
                BigDecimal generated = new BigDecimal(((Element) nodes.item(0)).getTextContent().trim());
                if (generated.subtract(extracted.get()).abs().compareTo(TOTALS_TOLERANCE) > 0) {
                    problems.add(en.getValue().id() + ": PDF " + extracted.get().toPlainString() + " ≠ erzeugt " + generated.toPlainString());
                }
            }
            if (!problems.isEmpty()) {
                throw new GenerationException("Summen der erzeugten E-Rechnung weichen von der PDF ab: " + String.join("; ", problems));
            }
        } catch (GenerationException e) {
            throw e;
        } catch (Exception e) {
            throw new GenerationException("Erzeugte CII-XML konnte nicht geprüft werden: " + e.getMessage(), e);
        }
    }

    private static Path write(Path dir, String name, byte[] content) throws GenerationException {
        Path p = dir.resolve(name);
        try {
            Files.write(p, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return p;
        } catch (IOException e) {
            throw new GenerationException("Datei konnte nicht geschrieben werden: " + p + " – " + e.getMessage(), e);
        }
    }

    /** Dateiname laut Template für ein Dokument. */
    public static String baseName(ProfileDefinition profile, InvoiceData data, String tenantId, int runNumber) {
        Map<String, String> values = Map.of(
                "invoiceNumber", data.value(BusinessTerm.BT_1).orElse(""),
                "customerName", data.value(BusinessTerm.BT_44).orElse(""),
                "tenantId", tenantId,
                "runNumber", String.format(Locale.ROOT, "%03d", runNumber));
        Map<String, LocalDate> dates = new java.util.HashMap<>();
        dates.put("invoiceDate", data.date(BusinessTerm.BT_2).orElse(null));
        return FilenameTemplate.render(profile.generation().filenameTemplate(), values, dates);
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
