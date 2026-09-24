package de.hofmannit.erechnung.dispatch;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Platzhalterersetzung für Betreff-, Text- und Kommandozeilen-Templates:
 * {@code {invoiceNumber}}, {@code {invoiceDate:<Pattern>}}, {@code {customerName}},
 * {@code {tenantId}}, {@code {runNumber}}, {@code {correlationId}} sowie Artefaktpfade
 * ({@code {sourcePdf}}, {@code {zugferdPdf}}, {@code {ciiXml}}, {@code {ublXml}}, {@code {archiveDir}}).
 *
 * <p>Werte werden wörtlich eingesetzt und niemals durch eine Shell interpretiert. Ein unbekannter
 * oder fehlender Platzhalter ist ein Fehler, damit nie stillschweigend leere Argumente entstehen.
 */
public final class TextTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)(?::([^}]+))?}");

    private TextTemplate() {
    }

    public static String render(String template, Map<String, String> values, Map<String, LocalDate> dates) {
        if (template == null) {
            return null;
        }
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String format = m.group(2);
            String replacement;
            if (dates != null && dates.containsKey(name) && dates.get(name) != null) {
                replacement = dates.get(name).format(DateTimeFormatter.ofPattern(format == null ? "dd.MM.yyyy" : format, Locale.GERMANY));
            } else if (values != null && values.containsKey(name) && values.get(name) != null) {
                replacement = values.get(name);
            } else {
                throw new IllegalArgumentException("Platzhalter {" + name + "} hat keinen Wert");
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
