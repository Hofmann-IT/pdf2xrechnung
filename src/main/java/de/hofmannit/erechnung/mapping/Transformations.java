package de.hofmannit.erechnung.mapping;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die im Profil erlaubten Transformationen (siehe {@code profiles/standard.yaml}). Feste,
 * dokumentierte Liste; keine Skriptsprache.
 *
 * <ul>
 *   <li>{@code trim}, {@code upper}, {@code lower}, {@code removeWhitespace}</li>
 *   <li>{@code date:<pattern>} → ISO-8601 (yyyy-MM-dd)</li>
 *   <li>{@code decimal:de} ("1.234,56" → 1234.56), {@code decimal:en} ("1,234.56" → 1234.56)</li>
 *   <li>{@code percent:de} ("19 %" → 19), {@code percent:en}</li>
 *   <li>{@code regexReplace:/<regex>/<ersatz>/}</li>
 * </ul>
 */
public final class Transformations {

    private static final Pattern CURRENCY_NOISE = Pattern.compile("[€$£]|EUR|USD|CHF|GBP", Pattern.CASE_INSENSITIVE);

    private Transformations() {
    }

    /** Wendet alle Transformationen in Reihenfolge an; liefert den transformierten Wert. */
    public static String apply(String value, List<String> transforms) throws TransformationException {
        String result = value;
        for (String t : transforms) {
            result = applyOne(result, t);
        }
        return result;
    }

    static String applyOne(String value, String transform) throws TransformationException {
        if (value == null) {
            return null;
        }
        String t = transform.trim();
        String name = t.contains(":") ? t.substring(0, t.indexOf(':')) : t;
        String arg = t.contains(":") ? t.substring(t.indexOf(':') + 1) : "";
        return switch (name) {
            case "trim" -> value.trim();
            case "upper" -> value.toUpperCase(Locale.ROOT);
            case "lower" -> value.toLowerCase(Locale.ROOT);
            case "removeWhitespace" -> value.replaceAll("\\s+", "");
            case "date" -> parseDate(value, arg);
            case "decimal" -> parseDecimal(value, arg).toPlainString();
            case "percent" -> parseDecimal(value.replace("%", ""), arg).toPlainString();
            case "regexReplace" -> regexReplace(value, arg);
            default -> throw new TransformationException("Unbekannte Transformation: " + transform);
        };
    }

    static String parseDate(String value, String pattern) throws TransformationException {
        try {
            // STRICT: "31.02.2026" ist ein Fehler, kein 28.02. – dafür Jahr als 'u' (proleptisches Jahr) statt 'y' (Jahr der Ära).
            DateTimeFormatter f = DateTimeFormatter.ofPattern(pattern.replace('y', 'u'), Locale.GERMANY)
                    .withResolverStyle(ResolverStyle.STRICT);
            return LocalDate.parse(value.trim(), f).toString();
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new TransformationException("'" + value + "' ist kein Datum im Format " + pattern);
        }
    }

    /** Parst "1.234,56" (de) bzw. "1,234.56" (en) und Vorzeichen; Währungszeichen werden entfernt. */
    public static BigDecimal parseDecimal(String value, String locale) throws TransformationException {
        String cleaned = CURRENCY_NOISE.matcher(value).replaceAll("").replaceAll("\\s+", "");
        cleaned = cleaned.replace("−", "-");
        boolean negative = cleaned.endsWith("-") || cleaned.startsWith("-");
        cleaned = cleaned.replace("-", "");
        String normalized;
        if ("en".equalsIgnoreCase(locale)) {
            normalized = cleaned.replace(",", "");
        } else if ("de".equalsIgnoreCase(locale) || locale.isBlank()) {
            normalized = cleaned.replace(".", "").replace(",", ".");
        } else {
            throw new TransformationException("Unbekanntes Zahlenformat: " + locale);
        }
        if (normalized.isEmpty() || !normalized.matches("\\d+(\\.\\d+)?")) {
            throw new TransformationException("'" + value + "' ist keine Zahl im Format " + (locale.isBlank() ? "de" : locale));
        }
        BigDecimal result = new BigDecimal(normalized);
        return negative ? result.negate() : result;
    }

    static String regexReplace(String value, String arg) throws TransformationException {
        // Form: /regex/ersatz/
        if (arg.length() < 3 || arg.charAt(0) != '/' || !arg.endsWith("/")) {
            throw new TransformationException("regexReplace erwartet /regex/ersatz/, erhalten: " + arg);
        }
        String body = arg.substring(1, arg.length() - 1);
        int split = -1;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) == '/' && (i == 0 || body.charAt(i - 1) != '\\')) {
                split = i;
                break;
            }
        }
        if (split < 0) {
            throw new TransformationException("regexReplace erwartet /regex/ersatz/, erhalten: " + arg);
        }
        String regex = body.substring(0, split).replace("\\/", "/");
        String replacement = body.substring(split + 1).replace("\\/", "/");
        try {
            Matcher m = Pattern.compile(regex).matcher(value);
            return m.replaceAll(replacement);
        } catch (RuntimeException e) {
            throw new TransformationException("regexReplace fehlgeschlagen: " + e.getMessage());
        }
    }

    /** Fehler bei einer Transformation (formal ungültiger Wert). */
    public static class TransformationException extends Exception {
        public TransformationException(String message) {
            super(message);
        }
    }
}
