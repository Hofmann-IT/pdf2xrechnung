package de.hofmannit.erechnung.configuration.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.BusinessCase;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Classification;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Column;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.FieldMapping;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Indicator;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.LineItems;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Rule;
import de.hofmannit.erechnung.model.BusinessTerm;
import de.hofmannit.erechnung.security.Sha256;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Lädt Profile aus {@code profiles/*.yaml}, bindet sie auf {@link ProfileDefinition} und prüft
 * sie strukturell. Verwendet ausschließlich Spring-Boot-eigene Mechanismen
 * ({@link YamlPropertySourceLoader} + {@link Binder}); keine zusätzliche YAML-Bibliothek.
 */
public final class ProfileLoader {

    private static final Pattern PROFILE_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();

    /** Lädt alle {@code *.yaml}/{@code *.yml} im Verzeichnis, sortiert nach Dateiname. */
    public Map<String, LoadedProfile> loadAll(Path profilesDirectory) throws IOException {
        if (!Files.isDirectory(profilesDirectory)) {
            throw new ProfileException("Profilverzeichnis existiert nicht: " + profilesDirectory.toAbsolutePath());
        }
        List<Path> files;
        try (Stream<Path> stream = Files.list(profilesDirectory)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".yaml") || n.endsWith(".yml");
                    })
                    .sorted()
                    .toList();
        }
        Map<String, LoadedProfile> result = new LinkedHashMap<>();
        for (Path file : files) {
            LoadedProfile profile = load(file);
            if (result.putIfAbsent(profile.name(), profile) != null) {
                throw new ProfileException("Profilname doppelt: " + profile.name() + " (" + file + ")");
            }
        }
        return result;
    }

    /** Lädt genau eine Profildatei. */
    public LoadedProfile load(Path file) throws IOException {
        byte[] raw = Files.readAllBytes(file);
        String sha256 = Sha256.ofBytes(raw);

        List<PropertySource<?>> sources = yamlLoader.load(file.getFileName().toString(), new FileSystemResource(file));
        if (sources.isEmpty()) {
            throw new ProfileException("Profildatei ist leer: " + file);
        }
        if (sources.size() > 1) {
            throw new ProfileException("Profildatei darf nur ein YAML-Dokument enthalten: " + file);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources.get(0)));
        ProfileDefinition definition;
        try {
            definition = binder.bind("", Bindable.of(ProfileDefinition.class))
                    .orElseThrow(() -> new ProfileException("Profildatei enthält keine bindbaren Werte: " + file));
        } catch (ProfileException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ProfileException("Profil konnte nicht gebunden werden: " + file + " – " + e.getMessage(), e);
        }

        String expectedName = stripExtension(file.getFileName().toString());
        validate(definition, expectedName, file);
        return new LoadedProfile(definition.profile().name(), file, sha256, definition);
    }

    /** Strukturelle Prüfung; die fachliche Ausführung der Regeln erfolgt in Phase 2. */
    static void validate(ProfileDefinition def, String expectedName, Path file) {
        List<String> errors = new ArrayList<>();

        if (def.profile() == null || def.profile().name() == null || def.profile().name().isBlank()) {
            errors.add("profile.name fehlt");
        } else {
            String name = def.profile().name();
            if (!PROFILE_NAME.matcher(name).matches()) {
                errors.add("profile.name '" + name + "' ist ungültig (erlaubt: [a-z0-9-])");
            }
            if (!name.equals(expectedName)) {
                errors.add("profile.name '" + name + "' muss dem Dateinamen '" + expectedName + "' entsprechen");
            }
        }

        if (def.classification() == null) {
            errors.add("classification fehlt");
        } else {
            Classification c = def.classification();
            if (c.invoiceIndicators().isEmpty()) {
                errors.add("classification.invoiceIndicators darf nicht leer sein");
            }
            checkIndicators("classification.invoiceIndicators", c.invoiceIndicators(), errors);
            checkIndicators("classification.creditNoteIndicators", c.creditNoteIndicators(), errors);
            checkIndicators("classification.nonInvoiceIndicators", c.nonInvoiceIndicators(), errors);
            long defaults = c.businessCases().stream().filter(BusinessCase::defaultCase).count();
            if (c.businessCases().isEmpty()) {
                errors.add("classification.businessCases darf nicht leer sein");
            } else if (defaults != 1) {
                errors.add("classification.businessCases: genau ein Geschäftsfall muss defaultCase=true haben (gefunden: " + defaults + ")");
            }
            for (BusinessCase bc : c.businessCases()) {
                if (bc.id() == null || bc.id().isBlank()) {
                    errors.add("classification.businessCases: id fehlt");
                }
                if (bc.vatCategoryCode() == null || bc.vatCategoryCode().isBlank()) {
                    errors.add("classification.businessCases[" + bc.id() + "].vatCategoryCode fehlt");
                }
                checkIndicators("classification.businessCases[" + bc.id() + "].indicators", bc.indicators(), errors);
            }
        }

        for (int i = 0; i < def.fields().size(); i++) {
            FieldMapping f = def.fields().get(i);
            String where = "fields[" + i + "]";
            if (BusinessTerm.fromId(f.businessTerm()).isEmpty()) {
                errors.add(where + ".businessTerm '" + f.businessTerm() + "' ist kein bekannter Business Term");
            }
            if (f.rule() == null || f.rule().type() == null) {
                errors.add(where + ".rule.type fehlt");
            } else {
                checkRule(where + ".rule", f.rule(), errors);
            }
        }

        if (def.lineItems() != null) {
            LineItems li = def.lineItems();
            if (li.startAnchor() == null || li.startAnchor().regex() == null) {
                errors.add("lineItems.startAnchor.regex fehlt");
            } else {
                checkRegex("lineItems.startAnchor.regex", li.startAnchor().regex(), errors);
            }
            if (li.endAnchor() != null && li.endAnchor().regex() != null) {
                checkRegex("lineItems.endAnchor.regex", li.endAnchor().regex(), errors);
            }
            checkIndicators("lineItems.ignoreRows", li.ignoreRows(), errors);
            if (li.columns().isEmpty()) {
                errors.add("lineItems.columns darf nicht leer sein");
            }
            for (int i = 0; i < li.columns().size(); i++) {
                Column col = li.columns().get(i);
                if (BusinessTerm.fromId(col.businessTerm()).isEmpty()) {
                    errors.add("lineItems.columns[" + i + "].businessTerm '" + col.businessTerm() + "' ist kein bekannter Business Term");
                }
                if (col.width() <= 0) {
                    errors.add("lineItems.columns[" + i + "].width muss > 0 sein");
                }
            }
            if (li.rowDetection() == null || li.rowDetection().keyColumnBusinessTerm() == null || li.rowDetection().pattern() == null) {
                errors.add("lineItems.rowDetection (keyColumnBusinessTerm, pattern) fehlt");
            } else {
                checkRegex("lineItems.rowDetection.pattern", li.rowDetection().pattern(), errors);
                String key = li.rowDetection().keyColumnBusinessTerm();
                boolean keyExists = li.columns().stream()
                        .anyMatch(c -> BusinessTerm.fromId(c.businessTerm()).equals(BusinessTerm.fromId(key)));
                if (!keyExists) {
                    errors.add("lineItems.rowDetection.keyColumnBusinessTerm '" + key + "' ist keine Spalte");
                }
            }
        }

        if (def.generation() == null) {
            errors.add("generation fehlt");
        } else {
            if (def.generation().formats().isEmpty()) {
                errors.add("generation.formats darf nicht leer sein");
            }
            if (def.generation().filenameTemplate() == null || def.generation().filenameTemplate().isBlank()) {
                errors.add("generation.filenameTemplate fehlt");
            }
        }

        if (def.plausibility() != null && def.plausibility().tolerance() != null
                && def.plausibility().tolerance().signum() < 0) {
            errors.add("plausibility.tolerance darf nicht negativ sein");
        }

        if (!errors.isEmpty()) {
            throw new ProfileException("Profil " + file + " ist ungültig:\n - " + String.join("\n - ", errors));
        }
    }

    private static void checkRule(String where, Rule r, List<String> errors) {
        switch (r.type()) {
            case ANCHOR -> {
                if (r.label() == null || r.label().isBlank()) {
                    errors.add(where + ".label fehlt (anchor)");
                }
            }
            case REGEX -> {
                if (r.pattern() == null) {
                    errors.add(where + ".pattern fehlt (regex)");
                } else {
                    checkRegex(where + ".pattern", r.pattern(), errors);
                }
                if (r.group() < 0) {
                    errors.add(where + ".group muss >= 0 sein");
                }
            }
            case REGION -> {
                if (r.x() == null || r.y() == null || r.width() == null || r.height() == null) {
                    errors.add(where + ": x, y, width, height sind für region erforderlich");
                } else if (r.width() <= 0 || r.height() <= 0) {
                    errors.add(where + ": width und height müssen > 0 sein");
                }
            }
            case FIXED -> {
                if (r.key() == null || r.key().isBlank()) {
                    errors.add(where + ".key fehlt (fixed)");
                }
            }
            case TABLE -> errors.add(where + ": Regeltyp table ist nur unter lineItems zulässig");
        }
    }

    private static void checkIndicators(String where, List<Indicator> indicators, List<String> errors) {
        for (int i = 0; i < indicators.size(); i++) {
            Indicator ind = indicators.get(i);
            if (ind.regex() == null || ind.regex().isBlank()) {
                errors.add(where + "[" + i + "].regex fehlt");
            } else {
                checkRegex(where + "[" + i + "].regex", ind.regex(), errors);
            }
        }
    }

    private static void checkRegex(String where, String regex, List<String> errors) {
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            errors.add(where + ": ungültiger regulärer Ausdruck – " + e.getDescription());
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? fileName : fileName.substring(0, dot);
    }
}
