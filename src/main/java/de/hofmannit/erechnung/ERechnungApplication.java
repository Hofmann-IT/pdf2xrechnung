package de.hofmannit.erechnung;

import de.hofmannit.erechnung.cli.CliArguments;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Einstiegspunkt der On-Premises-Anwendung "PDF-zu-E-Rechnung".
 *
 * <p>Ohne Argumente startet die Anwendung mit Web-Server und Inbox-Watcher. Mit einem
 * CLI-Befehl ({@code calibrate}, {@code reprocess}) läuft sie ohne Web-Server und ohne Watcher,
 * führt den Befehl aus und beendet sich mit dessen Exit-Code.
 *
 * <p>Die Anwendung besteht aus klar getrennten fachlichen Modulen (Packages), deren
 * Verantwortlichkeiten und erlaubte Abhängigkeitsrichtungen in den jeweiligen
 * {@code package-info.java} dokumentiert sind. Die Abhängigkeitsregeln werden durch
 * {@code ModuleDependencyTest} abgesichert (keine zyklischen Abhängigkeiten).
 *
 * <p>Zur Laufzeit findet keinerlei ausgehende Netzwerkkommunikation statt, mit Ausnahme
 * des ausdrücklich konfigurierten SMTP-Servers (siehe Modul {@code dispatch}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ERechnungApplication {

    public static void main(String[] args) {
        if (CliArguments.isCliCommand(args)) {
            ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ERechnungApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties("app.watcher.enabled=false", "spring.main.banner-mode=off")
                    .run(args);
            System.exit(SpringApplication.exit(ctx));
        }
        SpringApplication.run(ERechnungApplication.class, args);
    }
}
