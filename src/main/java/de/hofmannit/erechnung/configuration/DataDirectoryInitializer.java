package de.hofmannit.erechnung.configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Stellt sicher, dass das Datenverzeichnis ({@code app.directories.data}) existiert, bevor die
 * SQLite-Datenbank geöffnet wird. Der SQLite-Treiber legt die Datenbankdatei an, nicht aber
 * fehlende übergeordnete Verzeichnisse.
 *
 * <p>Andere Arbeitsverzeichnisse (inbox, processing, ...) werden erst vom Watcher (Phase 2)
 * angelegt bzw. geprüft.
 */
@Configuration(proxyBeanMethods = false)
public class DataDirectoryInitializer {

    @Bean
    static BeanFactoryPostProcessor dataDirectoryCreator(Environment environment) {
        return new BeanFactoryPostProcessor() {
            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
                Path data = Path.of(environment.getProperty("app.directories.data", "./data"));
                try {
                    Files.createDirectories(data);
                } catch (IOException e) {
                    throw new IllegalStateException("Datenverzeichnis kann nicht angelegt werden: " + data.toAbsolutePath(), e);
                }
            }
        };
    }
}
