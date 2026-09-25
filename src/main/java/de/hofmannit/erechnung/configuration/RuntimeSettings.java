package de.hofmannit.erechnung.configuration;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Halter der wirksamen Laufzeiteinstellungen (ADR 0012). Startwert aus der YAML; die Verwaltung
 * (Modul {@code admin}) setzt beim Start den jüngsten Datenbankstand und später jede Änderung.
 * Verbraucher (Verzeichnisse, Watcher, Versand) lesen bei jeder Verwendung {@link #current()}
 * oder registrieren sich für Änderungen.
 */
@Component
public class RuntimeSettings {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSettings.class);

    /** Herkunft des wirksamen Standes. */
    public enum Source { YAML, DATABASE }

    private final AtomicReference<RuntimeConfig> current;
    private final AtomicReference<Source> source = new AtomicReference<>(Source.YAML);
    private final List<Consumer<RuntimeConfig>> listeners = new CopyOnWriteArrayList<>();

    public RuntimeSettings(AppProperties properties) {
        this.current = new AtomicReference<>(RuntimeConfig.fromProperties(properties));
    }

    public RuntimeConfig current() {
        return current.get();
    }

    public Source source() {
        return source.get();
    }

    public void addListener(Consumer<RuntimeConfig> listener) {
        listeners.add(listener);
    }

    /** Übernimmt einen bereits geprüften Stand und benachrichtigt alle Verbraucher. */
    public void apply(RuntimeConfig config, Source from) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Laufzeiteinstellungen ungültig: " + String.join("; ", errors));
        }
        current.set(config);
        source.set(from);
        for (Consumer<RuntimeConfig> l : listeners) {
            try {
                l.accept(config);
            } catch (RuntimeException e) {
                log.error("Verbraucher konnte neue Einstellungen nicht übernehmen", e);
            }
        }
    }
}
