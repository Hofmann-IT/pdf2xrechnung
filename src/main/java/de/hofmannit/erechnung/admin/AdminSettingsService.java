package de.hofmannit.erechnung.admin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import de.hofmannit.erechnung.configuration.RuntimeConfig;
import de.hofmannit.erechnung.configuration.RuntimeSettings;
import de.hofmannit.erechnung.configuration.RuntimeSettings.Source;
import de.hofmannit.erechnung.ledger.AppSettingsRepository;
import de.hofmannit.erechnung.ledger.AppSettingsRepository.AppSettingsRow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Verwaltung der Laufzeiteinstellungen (ADR 0012): Datenbankstand beim Start übernehmen,
 * Änderungen prüfen, als neuen Datensatz speichern und sofort wirksam machen.
 */
@Service
public class AdminSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AdminSettingsService.class);

    /** Ablehnung einer Änderung mit allen Gründen. */
    public static class SettingsException extends Exception {
        private final List<String> errors;

        public SettingsException(List<String> errors) {
            super(String.join("; ", errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    private final RuntimeSettings settings;
    private final AppSettingsRepository repository;
    private final ObjectMapper json;
    private final Clock clock;

    public AdminSettingsService(RuntimeSettings settings, AppSettingsRepository repository, ObjectMapper json, Clock clock) {
        this.settings = settings;
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        loadFromDatabase();
    }

    /** Beim Start: jüngster Datenbankstand geht der YAML vor; ungültige Stände werden protokolliert und ignoriert. */
    private void loadFromDatabase() {
        Optional<AppSettingsRow> latest = repository.latest();
        if (latest.isEmpty()) {
            log.info("Laufzeiteinstellungen aus config/application.yaml (keine gespeicherten Einstellungen)");
            return;
        }
        try {
            RuntimeConfig config = json.readValue(latest.get().settingsJson(), RuntimeConfig.class);
            settings.apply(config, Source.DATABASE);
            log.info("Laufzeiteinstellungen aus der Datenbank übernommen (Datensatz #{} von {} am {})", latest.get().id(),
                    latest.get().createdBy(), latest.get().createdAt());
        } catch (JsonProcessingException | IllegalArgumentException e) {
            log.error("Gespeicherte Laufzeiteinstellungen #{} sind unbrauchbar, es gilt die YAML: {}", latest.get().id(), e.getMessage());
        }
    }

    public RuntimeConfig current() {
        return settings.current();
    }

    public Source source() {
        return settings.source();
    }

    public List<AppSettingsRow> history(int limit) {
        return repository.history(limit);
    }

    /**
     * Prüft, speichert (append-only) und übernimmt die Einstellungen. Verzeichnisse werden
     * angelegt; ein Wechsel des Verarbeitungsverzeichnisses ist nur erlaubt, solange das bisherige
     * keine Dateien enthält (sonst gingen laufende Vorgänge bis zum Neustart verloren).
     */
    public AppSettingsRow save(RuntimeConfig config, String user, String note) throws SettingsException {
        List<String> errors = new ArrayList<>();
        if (user == null || user.isBlank()) {
            errors.add("Benutzername ist erforderlich");
        }
        errors.addAll(config.validate());
        if (!errors.isEmpty()) {
            throw new SettingsException(errors);
        }
        RuntimeConfig before = settings.current();
        Path oldProcessing = Path.of(before.directories().processing()).toAbsolutePath().normalize();
        Path newProcessing = Path.of(config.directories().processing()).toAbsolutePath().normalize();
        if (!oldProcessing.equals(newProcessing) && hasFiles(oldProcessing)) {
            throw new SettingsException(List.of("Verarbeitungsverzeichnis kann nicht gewechselt werden, solange " + oldProcessing
                    + " noch Dateien enthält (laufende Vorgänge erst abschließen)"));
        }
        for (var entry : config.directories().asMap().entrySet()) {
            Path dir = Path.of(entry.getValue().trim()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(dir);
                if (!Files.isWritable(dir)) {
                    errors.add("Verzeichnis '" + entry.getKey() + "' ist nicht beschreibbar: " + dir);
                }
            } catch (IOException | RuntimeException e) {
                errors.add("Verzeichnis '" + entry.getKey() + "' kann nicht angelegt werden: " + dir + " (" + e.getMessage() + ")");
            }
        }
        if (!errors.isEmpty()) {
            throw new SettingsException(errors);
        }
        String serialized;
        try {
            serialized = json.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        AppSettingsRow row = repository.save(clock.instant(), user.trim(), note == null || note.isBlank() ? null : note.trim(), serialized);
        settings.apply(config, Source.DATABASE);
        log.info("Laufzeiteinstellungen gespeichert und übernommen (Datensatz #{} von {})", row.id(), user.trim());
        return row;
    }

    public RuntimeConfig parse(String settingsJson) throws JsonProcessingException {
        return json.readValue(settingsJson, RuntimeConfig.class);
    }

    private static boolean hasFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return true;
        }
    }
}
