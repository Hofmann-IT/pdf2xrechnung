package de.hofmannit.erechnung.validation;

import java.io.Reader;
import java.net.URI;
import java.util.Locale;

import javax.xml.XMLConstants;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;
import javax.xml.validation.SchemaFactory;

import de.kosit.validationtool.impl.xml.StrictRelativeResolvingStrategy;
import net.sf.saxon.Configuration;
import net.sf.saxon.lib.UnparsedTextURIResolver;
import net.sf.saxon.trans.XPathException;

/**
 * Resolver-Strategie für den KoSIT-Validator ohne Netzwerkzugriff (Vorgabe Abschnitte 38, 44).
 *
 * <p>Baut auf {@code StrictRelativeResolvingStrategy} aus KoSIT 1.5.0 auf (löst relativ zum
 * lokalen Repository auf) und lehnt zusätzlich jede Auflösung mit Netzwerkschema ab.
 * Der KoSIT-Modus {@code STRICT_LOCAL} ist mit Saxon 12 nicht lauffähig (liefert einen
 * null-Resolver, den Saxon 12 nicht mehr akzeptiert), daher diese eigene Strategie.
 */
public class LocalOnlyResolvingStrategy extends StrictRelativeResolvingStrategy {

    @Override
    public URIResolver createResolver(URI repository) {
        URIResolver delegate = super.createResolver(repository);
        return (href, base) -> {
            rejectRemote(href, "href");
            rejectRemote(base, "base");
            Source s = delegate == null ? null : delegate.resolve(href, base);
            if (s != null && s.getSystemId() != null) {
                rejectRemote(s.getSystemId(), "resolved");
            }
            return s;
        };
    }

    @Override
    public UnparsedTextURIResolver createUnparsedTextURIResolver(URI repository) {
        UnparsedTextURIResolver delegate = super.createUnparsedTextURIResolver(repository);
        return (URI absoluteURI, String encoding, Configuration config) -> {
            if (absoluteURI != null && isRemote(absoluteURI.getScheme())) {
                throw new XPathException("Netzwerkzugriff nicht erlaubt: " + absoluteURI);
            }
            if (delegate == null) {
                throw new XPathException("Kein Resolver für unparsed-text verfügbar: " + absoluteURI);
            }
            Reader r = delegate.resolve(absoluteURI, encoding, config);
            return r;
        };
    }

    @Override
    public SchemaFactory createSchemaFactory() {
        SchemaFactory factory = super.createSchemaFactory();
        try {
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
        } catch (Exception e) {
            throw new IllegalStateException("Schema-Fabrik kann nicht abgesichert werden", e);
        }
        return factory;
    }

    private static void rejectRemote(String uri, String what) throws TransformerException {
        if (uri == null || uri.isBlank()) {
            return;
        }
        int colon = uri.indexOf(':');
        if (colon > 1) {
            String scheme = uri.substring(0, colon);
            if (isRemote(scheme)) {
                throw new TransformerException("Netzwerkzugriff nicht erlaubt (" + what + "): " + uri);
            }
        }
    }

    static boolean isRemote(String scheme) {
        if (scheme == null) {
            return false;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        return s.equals("http") || s.equals("https") || s.equals("ftp") || s.equals("ftps") || s.equals("sftp");
    }
}
