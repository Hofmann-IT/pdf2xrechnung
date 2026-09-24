package de.hofmannit.erechnung.generation;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rendert das Dateinamen-Template eines Profils (Vorgabe Abschnitt 14) und bereinigt jeden
 * Platzhalterwert sicher: Umlaute werden transliteriert, alles außer {@code [A-Za-z0-9._-]}
 * wird zu {@code _}, Pfadtrenner und {@code ..} sind ausgeschlossen.
 *
 * <p>Platzhalter: {@code {invoiceNumber}}, {@code {invoiceDate:<Pattern>}}, {@code {customerName}},
 * {@code {tenantId}}, {@code {runNumber}}.
 */
public final class FilenameTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]+))?}");
    private static final int MAX_VALUE_LENGTH = 60;
    private static final int MAX_TOTAL_LENGTH = 180;

    private FilenameTemplate() {
    }

    /**
     * @param template Template ohne Dateiendung
     * @param values   Textwerte je Platzhaltername
     * @param dates    Datumswerte je Platzhaltername (Formatierung über den Doppelpunkt-Zusatz)
     */
    public static String render(String template, Map<String, String> values, Map<String, LocalDate> dates) {
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String format = m.group(2);
            String replacement;
            if (dates.containsKey(name)) {
                LocalDate d = dates.get(name);
                replacement = d == null ? "" : d.format(DateTimeFormatter.ofPattern(format == null ? "yyyyMMdd" : format, Locale.ROOT));
            } else if (values.containsKey(name)) {
                replacement = values.get(name) == null ? "" : values.get(name);
            } else {
                throw new IllegalArgumentException("Unbekannter Platzhalter im Dateinamen-Template: {" + name + "}");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(sanitize(replacement)));
        }
        m.appendTail(sb);
        String result = sanitize(sb.toString());
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Dateiname ist nach Bereinigung leer (Template: " + template + ")");
        }
        return result.length() > MAX_TOTAL_LENGTH ? result.substring(0, MAX_TOTAL_LENGTH) : result;
    }

    /** Bereinigt einen einzelnen Wert für die Verwendung in Dateinamen. */
    public static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String s = value.trim()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^A-Za-z0-9._-]+", "_");
        s = s.replaceAll("_+", "_").replaceAll("^[._-]+|[._-]+$", "");
        s = s.replace("..", ".");
        if (s.length() > MAX_VALUE_LENGTH) {
            s = s.substring(0, MAX_VALUE_LENGTH);
        }
        return s;
    }
}
