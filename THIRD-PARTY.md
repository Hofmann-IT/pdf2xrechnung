# Drittanbieter-Komponenten und Lizenzen

Stand 2026-09-24, ermittelt aus den Maven-POMs der Laufzeitabhängigkeiten
(`mvn org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party`, Test-Abhängigkeiten
ausgenommen). Alle Komponenten werden unverändert als Bibliotheken genutzt; es wird kein
Quellcode dieser Komponenten in dieses Projekt kopiert. Das Projekt selbst wird von Hofmann IT
entwickelt; die Lizenz des Projekts legt der Auftraggeber fest.

## Direkte Abhängigkeiten (vorgegebener Stack)

| Komponente | Version | Lizenz | Hinweis |
|------------|---------|--------|---------|
| Spring Boot (Web, Thymeleaf, JDBC, Mail) | 3.5.16 | Apache-2.0 | inkl. Spring Framework 6.2, Tomcat Embedded 10.1, Thymeleaf 3.1, HikariCP, Jackson, SnakeYAML, Logback, SLF4J, Micrometer |
| Apache PDFBox (pdfbox, preflight, xmpbox, fontbox) | 3.0.5 | Apache-2.0 | Textextraktion, PDF/A-Prüfung |
| Mustangproject library | 2.17.0 | Apache-2.0 | ZUGFeRD/XRechnung-Erzeugung; zieht Apache FOP, Batik, XML Graphics (Apache-2.0), ph-* (Apache-2.0), dom4j (BSD-Style „Plexus"), stax-utils (BSD) |
| Mustangproject validator | 2.17.0 | Apache-2.0 | ZUGFeRD-Validierung; zieht veraPDF (siehe unten) |
| KoSIT validationtool | 1.5.0 | Apache-2.0 | XRechnung-Validierung; zieht Apache HttpClient 5 (Apache-2.0), XML Resolver (Apache-2.0); Netzwerkfunktionen werden nicht verwendet (eigene lokale Auflösungsstrategie, ADR 0002) |
| Saxon-HE | 12.4 | MPL-2.0 | XSLT/Schematron |
| SQLite JDBC (xerial) | 3.49.1.0 | Apache-2.0 | enthält native SQLite-Bibliothek (Public Domain) |
| Flyway Core | 11.7.2 | Apache-2.0 | Schemamigration |
| javax.xml.bind:jaxb-api | 2.3.1 | CDDL-1.1 **oder** GPL-2.0 with Classpath Exception (Dual) | für KoSIT 1.5.0 erforderlich (ADR 0002); Nutzung unter CDDL-1.1 |
| com.sun.xml.bind:jaxb-impl | 2.3.9 | EDL-1.0 (BSD-3-Clause) | JAXB-2-Laufzeit für KoSIT |
| Apache Commons Lang | 3.17.0 | Apache-2.0 | für KoSIT |
| htmx.org (WebJar) | 2.0.6 | 0BSD | lokal ausgeliefert, kein CDN |

## Komponenten mit Doppellizenz oder besonderem Hinweis

| Komponente | Version | Lizenz | Entscheidung |
|------------|---------|--------|--------------|
| veraPDF (core, parser, pdf-model, validation-model, feature-reporting, metadata-fixer, jeweils `-jakarta`) | 1.26.x | GPL-3.0 **oder** MPL-2.0 (Dual) | Nutzung unter **MPL-2.0** als unveränderte Bibliothek (Entscheidung 2026-09-24, ADR 0002). veraPDF XMP Core: BSD-3-Clause |
| Logback (classic, core) | 1.5.34 | EPL-2.0 **oder** LGPL-2.1 (Dual) | Nutzung unter EPL-2.0 |
| Jakarta Annotations API | 2.1.1 | EPL-2.0 **oder** GPL-2.0 with CPE | Nutzung unter EPL-2.0 |
| Angus Mail (jakarta.mail) | 2.0.5 | EDL-1.0 / EPL-2.0 / GPL-2.0 with CPE | Nutzung unter EDL-1.0 |
| Jakarta Activation, JAXB 4 Runtime (glassfish), istack | diverse | EDL-1.0 | – |
| Mozilla Rhino (über Apache FOP) | 1.7.13 | MPL-2.0 | wird zur Laufzeit nicht angesprochen |
| net.sf.offo:fop-hyph (Silbentrennmuster für Apache FOP, über Mustang) | 2.0 | „various licenses" laut POM (überwiegend LPPL/BSD-artig, je Sprachmuster) | Transitiv über den vorgegebenen Stack; wird von dieser Anwendung nicht genutzt (keine FOP-Ausgabe). **Offen:** Lizenzkennung ist nicht eindeutig maschinenlesbar; für eine formale Freigabe die Musterlizenzen im Jar prüfen |

## Validierungsressourcen (nicht als Maven-Abhängigkeit)

| Ressource | Version | Lizenz | Ablage |
|-----------|---------|--------|--------|
| KoSIT Validator-Konfiguration XRechnung | 2026-08-31 (XRechnung 3.0.2) | Apache-2.0 (KoSIT); enthaltene EN16931-Schematron-Regeln: Lizenzkennung auf GitHub „Other" (offen, ADR 0002) | `validator/xrechnung/` |
| LiberationSans (in Testdaten, aus PDFBox-Jar) | – | SIL Open Font License 1.1 | nur Tests |
| sRGB-ICC-Profil (aus Mustang-Jar) | – | siehe Mustang | nur Tests |

## Werkzeuge (nicht Teil der Auslieferung)

| Werkzeug | Lizenz |
|----------|--------|
| WinSW (Windows-Dienst-Wrapper, vom Betreiber separat herunterzuladen) | MIT |
| Eclipse Temurin JDK/JRE 21 | GPL-2.0 with Classpath Exception (Laufzeitumgebung, nicht verlinkt) |
| Apache Maven | Apache-2.0 |

Die vollständige, automatisch erzeugte Liste aller 132 Laufzeitartefakte inklusive
transitiver Abhängigkeiten lässt sich jederzeit reproduzieren:

```bash
mvn org.codehaus.mojo:license-maven-plugin:2.4.0:add-third-party -Dlicense.outputDirectory=target/licenses -Dlicense.excludedScopes=test,provided
```

Ergebnis: `target/licenses/THIRD-PARTY.txt`.
