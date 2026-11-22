package de.hofmannit.erechnung.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import de.hofmannit.erechnung.ERechnungApplication;
import de.hofmannit.erechnung.testsupport.TestInvoicePdf;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Die CLI-Befehle starten die Anwendung ohne Web-Server, genau wie
 * {@link ERechnungApplication#main}. Mit der Anmeldung für alle Seiten (ADR 0015) brach dieser
 * Start ab, weil die Web-Sicherheit auch ohne Web-Server aufgebaut werden sollte; kein Test
 * hatte den Start ohne Web-Server abgedeckt.
 */
class CliContextTest {

    @Test
    void calibrateLaeuftOhneWebServerUndOhneAnmeldung() throws Exception {
        Path base = Path.of("target", "test-data");
        Files.createDirectories(base);
        Path root = Files.createTempDirectory(base, "cli-").toAbsolutePath();
        Path sample = root.resolve("beispiel.pdf");
        TestInvoicePdf.standardInvoice().writeTo(sample);
        Path report = root.resolve("kalibrierung.txt");

        List<String> properties = new ArrayList<>();
        for (String d : List.of("inbox", "processing", "output", "failed", "manual-review", "rejected", "archive", "data", "inbound-validation")) {
            properties.add("app.directories." + d + "=" + root.resolve(d));
        }
        properties.add("app.logging.directory=" + root.resolve("logs"));
        properties.add("app.watcher.enabled=false");
        properties.add("spring.main.banner-mode=off");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ERechnungApplication.class)
                .web(WebApplicationType.NONE)
                .properties(properties.toArray(String[]::new))
                .run("calibrate", "--samples", sample.toString(), "--profile", "./profiles/standard.yaml", "--tenant", "hofmann-it",
                        "--out", report.toString())) {
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(SpringApplication.exit(context)).as("Exit-Code des Befehls calibrate").isZero();
        }
        assertThat(report).exists();
        assertThat(Files.readString(report)).contains("beispiel.pdf");
    }
}
