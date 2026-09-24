package de.hofmannit.erechnung;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Einstiegspunkt der On-Premises-Anwendung "PDF-zu-E-Rechnung".
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
        SpringApplication.run(ERechnungApplication.class, args);
    }
}
