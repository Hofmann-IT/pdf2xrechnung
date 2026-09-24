package de.hofmannit.erechnung.inboundvalidation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;

import de.hofmannit.erechnung.security.SecureXml;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Bestimmt Dateityp, Dokumenttyp und Spezifikationskennungen einer empfangenen E-Rechnung
 * anhand des Inhalts, nie anhand der Dateiendung (ADR 0005). Verändert nichts.
 */
@Component
public class DocumentInspector {

    static final String NS_CII = "urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100";
    static final String NS_RAM = "urn:un:unece:uncefact:data:standard:ReusableAggregateBusinessInformationEntity:100";
    static final String NS_UBL_INVOICE = "urn:oasis:names:specification:ubl:schema:xsd:Invoice-2";
    static final String NS_UBL_CREDITNOTE = "urn:oasis:names:specification:ubl:schema:xsd:CreditNote-2";
    static final String NS_CBC = "urn:oasis:names:specification:ubl:schema:xsd:CommonBasicComponents-2";

    /** Bekannte Dateinamen eingebetteter E-Rechnungs-XML in ZUGFeRD/Factur-X-PDFs (bevorzugte Reihenfolge). */
    private static final List<String> KNOWN_EMBEDDED_NAMES = List.of("factur-x.xml", "zugferd-invoice.xml", "xrechnung.xml");

    /**
     * Ergebnis der Inspektion.
     *
     * @param type             Dokumenttyp
     * @param syntax           {@code CII} oder {@code UBL}, sonst {@code null}
     * @param xml              zu validierende XML (Datei selbst oder eingebettete XML), sonst {@code null}
     * @param embeddedFilename Dateiname der eingebetteten XML bei PDF, sonst {@code null}
     * @param rootElement      Wurzelelement der XML (lokaler Name), sonst {@code null}
     * @param customizationId  BT-24, sofern vorhanden
     * @param processId        BT-23, sofern vorhanden
     * @param attachmentNames  alle eingebetteten Dateinamen einer PDF
     * @param message          technische Meldung (z. B. Parserfehler)
     */
    public record Inspection(InboundDocumentType type, String syntax, byte[] xml, String embeddedFilename, String rootElement,
                             String customizationId, String processId, List<String> attachmentNames, String message) {
        public Inspection {
            attachmentNames = attachmentNames == null ? List.of() : List.copyOf(attachmentNames);
        }

        public boolean hasXml() {
            return xml != null;
        }
    }

    public Inspection inspect(byte[] content) {
        if (content == null || content.length == 0) {
            return new Inspection(InboundDocumentType.UNSUPPORTED, null, null, null, null, null, null, List.of(), "Datei ist leer");
        }
        if (isPdf(content)) {
            return inspectPdf(content);
        }
        if (looksLikeXml(content)) {
            return inspectXml(content, null);
        }
        return new Inspection(InboundDocumentType.UNSUPPORTED, null, null, null, null, null, null, List.of(),
                "Weder PDF noch XML (unterstützt werden XRechnung-XML und ZUGFeRD/Factur-X-PDF)");
    }

    // ------------------------------------------------------------------ PDF

    private Inspection inspectPdf(byte[] content) {
        Map<String, byte[]> attachments;
        try (PDDocument doc = Loader.loadPDF(content)) {
            if (doc.isEncrypted()) {
                return new Inspection(InboundDocumentType.PDF_WITHOUT_XML, null, null, null, null, null, null, List.of(),
                        "PDF ist verschlüsselt; eingebettete Dateien können nicht gelesen werden");
            }
            attachments = embeddedFiles(doc);
        } catch (IOException e) {
            return new Inspection(InboundDocumentType.UNSUPPORTED, null, null, null, null, null, null, List.of(),
                    "PDF konnte nicht gelesen werden: " + e.getMessage());
        }
        List<String> names = new ArrayList<>(attachments.keySet());
        if (attachments.isEmpty()) {
            return new Inspection(InboundDocumentType.PDF_WITHOUT_XML, null, null, null, null, null, null, names,
                    "PDF enthält keine eingebetteten Dateien");
        }
        // bevorzugt bekannte Namen, dann jede XML-Datei mit CII-/UBL-Wurzel
        List<String> ordered = new ArrayList<>();
        for (String known : KNOWN_EMBEDDED_NAMES) {
            for (String n : names) {
                if (n.equalsIgnoreCase(known)) {
                    ordered.add(n);
                }
            }
        }
        for (String n : names) {
            if (!ordered.contains(n) && n.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                ordered.add(n);
            }
        }
        for (String n : ordered) {
            Inspection x = inspectXml(attachments.get(n), n);
            if (x.type() == InboundDocumentType.CII_XML || x.type() == InboundDocumentType.UBL_XML) {
                return new Inspection(InboundDocumentType.ZUGFERD_PDF, x.syntax(), x.xml(), n, x.rootElement(),
                        x.customizationId(), x.processId(), names, null);
            }
        }
        return new Inspection(InboundDocumentType.PDF_WITHOUT_XML, null, null, null, null, null, null, names,
                "PDF enthält eingebettete Dateien " + names + ", aber keine E-Rechnungs-XML (CII/UBL)");
    }

    /** Liest alle eingebetteten Dateien (Name → Inhalt), inklusive verschachtelter Namensbaum-Knoten. */
    static Map<String, byte[]> embeddedFiles(PDDocument doc) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        PDDocumentNameDictionary names = doc.getDocumentCatalog().getNames();
        if (names == null) {
            return result;
        }
        PDEmbeddedFilesNameTreeNode root = names.getEmbeddedFiles();
        if (root == null) {
            return result;
        }
        collect(root, result);
        return result;
    }

    private static void collect(PDNameTreeNode<PDComplexFileSpecification> node, Map<String, byte[]> result) throws IOException {
        Map<String, PDComplexFileSpecification> entries = node.getNames();
        if (entries != null) {
            for (Map.Entry<String, PDComplexFileSpecification> e : entries.entrySet()) {
                PDComplexFileSpecification spec = e.getValue();
                PDEmbeddedFile file = spec == null ? null : spec.getEmbeddedFile();
                if (file == null) {
                    continue;
                }
                String name = spec.getFileUnicode() != null ? spec.getFileUnicode()
                        : spec.getFile() != null ? spec.getFile() : e.getKey();
                result.putIfAbsent(name, file.toByteArray());
            }
        }
        List<PDNameTreeNode<PDComplexFileSpecification>> kids = node.getKids();
        if (kids != null) {
            for (PDNameTreeNode<PDComplexFileSpecification> kid : kids) {
                collect(kid, result);
            }
        }
    }

    // ------------------------------------------------------------------ XML

    private Inspection inspectXml(byte[] content, String embeddedName) {
        Document doc;
        try {
            DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
            builder.setErrorHandler(null);
            doc = builder.parse(new ByteArrayInputStream(content));
        } catch (SAXException e) {
            return new Inspection(InboundDocumentType.MALFORMED_XML, null, null, embeddedName, null, null, null, List.of(),
                    "XML ist nicht wohlgeformt oder nicht zulässig: " + e.getMessage());
        } catch (Exception e) {
            return new Inspection(InboundDocumentType.MALFORMED_XML, null, null, embeddedName, null, null, null, List.of(),
                    "XML konnte nicht gelesen werden: " + e.getMessage());
        }
        Element root = doc.getDocumentElement();
        String local = root.getLocalName() == null ? root.getNodeName() : root.getLocalName();
        String ns = root.getNamespaceURI() == null ? "" : root.getNamespaceURI();
        if ("CrossIndustryInvoice".equals(local) && NS_CII.equals(ns)) {
            String customization = firstText(root, NS_RAM, "GuidelineSpecifiedDocumentContextParameter", "ID");
            String process = firstText(root, NS_RAM, "BusinessProcessSpecifiedDocumentContextParameter", "ID");
            return new Inspection(InboundDocumentType.CII_XML, "CII", content, embeddedName, local, customization, process, List.of(), null);
        }
        if (("Invoice".equals(local) && NS_UBL_INVOICE.equals(ns)) || ("CreditNote".equals(local) && NS_UBL_CREDITNOTE.equals(ns))) {
            String customization = directChildText(root, NS_CBC, "CustomizationID");
            String process = directChildText(root, NS_CBC, "ProfileID");
            return new Inspection(InboundDocumentType.UBL_XML, "UBL", content, embeddedName, local, customization, process, List.of(), null);
        }
        return new Inspection(InboundDocumentType.UNKNOWN_XML, null, null, embeddedName, local, null, null, List.of(),
                "Wurzelelement {" + ns + "}" + local + " ist keine bekannte EN-16931-Syntax (CII oder UBL)");
    }

    /** Text des Kindelements {@code child} des ersten Elements {@code parent} im Namensraum. */
    private static String firstText(Element root, String ns, String parent, String child) {
        NodeList parents = root.getElementsByTagNameNS(ns, parent);
        if (parents.getLength() == 0) {
            return null;
        }
        return directChildText((Element) parents.item(0), ns, child);
    }

    private static String directChildText(Element parent, String ns, String local) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(n.getLocalName()) && ns.equals(n.getNamespaceURI())) {
                String t = n.getTextContent();
                return t == null ? null : t.trim();
            }
        }
        return null;
    }

    static boolean isPdf(byte[] content) {
        int offset = 0;
        // PDF-Signatur innerhalb der ersten 1024 Bytes (Spezifikation erlaubt vorangestellte Bytes)
        int limit = Math.min(content.length - 5, 1024);
        while (offset <= limit) {
            if (content[offset] == '%' && content[offset + 1] == 'P' && content[offset + 2] == 'D' && content[offset + 3] == 'F'
                    && content[offset + 4] == '-') {
                return true;
            }
            offset++;
        }
        return false;
    }

    static boolean looksLikeXml(byte[] content) {
        int i = 0;
        if (content.length >= 3 && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < content.length && Character.isWhitespace(content[i])) {
            i++;
        }
        return i < content.length && content[i] == '<';
    }

    static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
