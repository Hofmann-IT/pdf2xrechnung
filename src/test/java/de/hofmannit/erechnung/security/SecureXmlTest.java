package de.hofmannit.erechnung.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

class SecureXmlTest {

    /** Verhindert nur die Konsolenausgabe des JDK-Parsers; die Exception wird weiterhin geworfen. */
    private static final ErrorHandler QUIET = new ErrorHandler() {
        @Override
        public void warning(SAXParseException e) {
        }

        @Override
        public void error(SAXParseException e) throws SAXException {
            throw e;
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXException {
            throw e;
        }
    };

    private static DocumentBuilder quietDomBuilder() throws Exception {
        DocumentBuilder builder = SecureXml.documentBuilderFactory().newDocumentBuilder();
        builder.setErrorHandler(QUIET);
        return builder;
    }

    @Test
    void domParserRejectsExternalEntities(@TempDir Path tmp) throws Exception {
        Path secret = tmp.resolve("secret.txt");
        Files.writeString(secret, "GEHEIM", StandardCharsets.UTF_8);
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe SYSTEM "%s"> ]>
                <foo>&xxe;</foo>
                """.formatted(secret.toUri());

        DocumentBuilder builder = quietDomBuilder();
        assertThatThrownBy(() -> builder.parse(new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(SAXException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    void domParserRejectsAnyDoctype() throws Exception {
        String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE foo><foo/>";
        DocumentBuilder builder = quietDomBuilder();
        assertThatThrownBy(() -> builder.parse(new ByteArrayInputStream(withDoctype.getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(SAXException.class)
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    void domParserStillParsesPlainNamespacedXml() throws Exception {
        String xml = "<?xml version=\"1.0\"?><rsm:CrossIndustryInvoice xmlns:rsm=\"urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100\"/>";
        Document doc = SecureXml.documentBuilderFactory().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        assertThat(doc.getDocumentElement().getLocalName()).isEqualTo("CrossIndustryInvoice");
        assertThat(doc.getDocumentElement().getNamespaceURI()).isEqualTo("urn:un:unece:uncefact:data:standard:CrossIndustryInvoice:100");
    }

    @Test
    void saxParserRejectsDoctype() throws Exception {
        String withDoctype = "<?xml version=\"1.0\"?><!DOCTYPE foo><foo/>";
        var parser = SecureXml.saxParserFactory().newSAXParser();
        assertThatThrownBy(() -> parser.parse(new ByteArrayInputStream(withDoctype.getBytes(StandardCharsets.UTF_8)), new DefaultHandler()))
                .isInstanceOf(SAXException.class);
    }

    @Test
    void staxReaderDoesNotSupportDtd() throws IOException, XMLStreamException {
        String xxe = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [ <!ENTITY xxe "expanded"> ]>
                <foo>&xxe;</foo>
                """;
        XMLStreamReader reader = SecureXml.xmlInputFactory()
                .createXMLStreamReader(new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> {
            while (reader.hasNext()) {
                reader.next();
            }
        }).isInstanceOf(XMLStreamException.class);
    }

    @Test
    void transformerAndSchemaFactoriesAreCreatedWithSecureProcessing() {
        assertThat(SecureXml.transformerFactory()).isNotNull();
        assertThat(SecureXml.schemaFactory()).isNotNull();
    }
}
