package de.hofmannit.erechnung.security;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLInputFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.validation.SchemaFactory;

import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

/**
 * Zentrale Fabrik für sicher konfigurierte XML-Parser (Vorgabe Abschnitt 38).
 *
 * <ul>
 *   <li>keine externen Entities (XXE)</li>
 *   <li>keine externe DTD-Auflösung, kein DOCTYPE</li>
 *   <li>keine externen Schema-/Stylesheet-Zugriffe</li>
 *   <li>Secure Processing (Limits gegen Entity-Expansion / übergroße Strukturen, soweit vom
 *       JDK-Parser unterstützt)</li>
 * </ul>
 *
 * Alle XML-verarbeitenden Stellen der Anwendung müssen ihre Factories ausschließlich von hier
 * beziehen. Bibliotheken mit eigenen Parsern (KoSIT, Mustang) werden in Phase 2/3 gesondert
 * abgesichert (siehe docs/adr/0002-validator-einbindung.md).
 */
public final class SecureXml {

    private static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private SecureXml() {
    }

    /** DOM-Parser-Fabrik, namespace-aware, ohne externe Zugriffe. */
    public static DocumentBuilderFactory documentBuilderFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature(DISALLOW_DOCTYPE, true);
            f.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            f.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            f.setFeature(LOAD_EXTERNAL_DTD, false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("Sichere XML-Konfiguration wird vom Parser nicht unterstützt", e);
        }
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }

    /** SAX-Parser-Fabrik, namespace-aware, ohne externe Zugriffe. */
    public static SAXParserFactory saxParserFactory() {
        SAXParserFactory f = SAXParserFactory.newInstance();
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature(DISALLOW_DOCTYPE, true);
            f.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            f.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            f.setFeature(LOAD_EXTERNAL_DTD, false);
        } catch (ParserConfigurationException | SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException("Sichere XML-Konfiguration wird vom Parser nicht unterstützt", e);
        }
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        return f;
    }

    /** StAX-Fabrik ohne DTD-Unterstützung und ohne externe Entities. */
    public static XMLInputFactory xmlInputFactory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        return f;
    }

    /** XSLT-Fabrik (JDK-intern) ohne externe DTD-/Stylesheet-Zugriffe. */
    public static TransformerFactory transformerFactory() {
        TransformerFactory f = TransformerFactory.newInstance();
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        } catch (javax.xml.transform.TransformerConfigurationException e) {
            throw new IllegalStateException("Sichere XSLT-Konfiguration wird nicht unterstützt", e);
        }
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return f;
    }

    /** XSD-Schema-Fabrik ohne externe DTD-/Schema-Zugriffe (Schemata ausschließlich lokal). */
    public static SchemaFactory schemaFactory() {
        SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException("Sichere Schema-Konfiguration wird nicht unterstützt", e);
        }
        return f;
    }
}
