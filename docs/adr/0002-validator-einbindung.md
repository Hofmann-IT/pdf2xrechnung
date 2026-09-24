# ADR 0002: Validator-Einbindung (KoSIT und Mustang)

- Status: Akzeptiert (2026-09-24)
- Betrifft: Module `validation`, `inboundvalidation`, Vorgabe Abschnitte 2, 3, 11, 12, 38, 44

## Kontext

Erzeugte und empfangene E-Rechnungen müssen lokal, ohne Netzwerk, formatabhängig validiert
werden. Für XRechnung ist der KoSIT-Validator verpflichtend; für ZUGFeRD die Mustang-Validierung.
Beide Ergebnisse sind getrennt zu protokollieren. Nicht anwendbare Validator-Kombinationen
dürfen nicht als Fehler gewertet werden.

Verifizierte Artefakte (Maven Central, 2026-09-24):

| Artefakt | Version | Lizenz | Bemerkung |
|---|---|---|---|
| `de.kosit:validationtool` | 1.5.0 | Apache 2.0 | Java-8-Bytecode, JAXB 2.x (`javax.xml.bind`), Saxon-HE 11.4 |
| `org.mustangproject:library` | 2.17.0 | Apache 2.0 | PDFBox 3.0.5, Saxon-HE 12.4, JAXB 4 (`jakarta.xml.bind`) |
| `org.mustangproject:validator` | 2.17.0 | Apache 2.0 | zieht `org.verapdf:validation-model-jakarta` (dual GPLv3 / MPL 2.0) |

## Entscheidung

1. **KoSIT-Validator als Bibliothek** (`de.kosit:validationtool`), nicht als externer Prozess
   und nicht als Daemon. Die XRechnung-Szenariokonfiguration (Schematron/XSLT, XSD,
   `scenarios.xml`, Report-Stylesheet) wird als Release-Paket **lokal** unter
   `validator/xrechnung/` abgelegt (`app.validation.kosit-repository`). Das Paket wird beim
   Deployment bereitgestellt; zur Laufzeit erfolgt kein Download. Die Konfiguration wird beim
   Start einmalig geladen und wiederverwendet.
2. **Mustang-Validierung** für ZUGFeRD/Factur-X (Profilkonformität, eingebettete XML,
   PDF/A-3-Aspekte) über `org.mustangproject:validator`, **sobald die Lizenzfrage geklärt ist**
   (siehe Offene Punkte). Bis dahin wird ausschließlich `org.mustangproject:library` eingebunden.
3. **Anwendbarkeitsmatrix** (Ergebnis `NOT_APPLICABLE` statt Fehler):

   | Dokument | KoSIT XRechnung | Mustang |
   |---|---|---|
   | XRechnung CII | verpflichtend | zusätzlich, soweit unterstützt |
   | XRechnung UBL | verpflichtend | zusätzlich, soweit unterstützt |
   | ZUGFeRD EN16931 (nicht XRechnung) | nur EN-16931-Regelwerk, keine BR-DE-Regeln | verpflichtend |
   | ZUGFeRD Profil XRECHNUNG | verpflichtend auf die eingebettete XML | verpflichtend |
   | PDF ohne XML | nicht anwendbar | Befund "keine XML" |

   Welches KoSIT-Szenario greift, entscheidet die Szenariokonfiguration anhand von
   Wurzelelement/CustomizationID (BT-24) und Profil-ID (BT-23); die Anwendung erzwingt keine
   XRechnung-Regeln auf Nicht-XRechnung-Profile.
4. **Getrennte Protokollierung:** Je Validator und Zielformat ein `validation_result`-Datensatz
   mit Fehler-/Warnzahl, Regelwerk und Report-Artefakten (XML immer; HTML, sofern lokal
   erzeugbar – der KoSIT-Report-XSLT liegt im lokalen Paket).
5. **Fehlerschwere wird nie herabgestuft.** Ein Dokument gilt nur als erfolgreich, wenn alle
   verpflichtenden Validatoren `VALID` liefern; Warnungen werden vollständig protokolliert.
6. **XML-Sicherheit:** Eigene Parser ausschließlich über `SecureXml`. Für KoSIT/Mustang wird in
   Phase 2/3 geprüft, ob deren Parser über Konfiguration abgesichert werden können (Resolver
   ohne Netzwerk, keine DTD); zusätzlich wird jede Eingabe vor Übergabe an die Bibliotheken mit
   `SecureXml` auf Wohlgeformtheit und DOCTYPE-Freiheit geprüft.

## Alternativen

- **KoSIT als externer Prozess (validator CLI):** plattformneutral, aber Prozess-Overhead,
  separates Java-Binary, schwerer zu testen. Verworfen.
- **KoSIT-Daemon (HTTP):** zusätzlicher lokaler Netzwerkdienst; unnötige Angriffsfläche. Verworfen.
- **Nur Mustang-Schematron-Validierung für XRechnung:** entspricht nicht der Vorgabe
  (KoSIT verpflichtend). Verworfen.

## Konsequenzen und Risiken

- **Unvollständiges KoSIT-POM (verifiziert 2026-09-24 am Jar und am veröffentlichten POM):**
  Das auf Maven Central veröffentlichte POM von `de.kosit:validationtool:1.5.0` deklariert nur
  ein optionales `jaxb-runtime`; die tatsächlichen Abhängigkeiten stehen nur im POM innerhalb
  des Jars. Die Klassen des Jars referenzieren nachweislich `net.sf.saxon` (43 Klassen),
  `javax.xml.bind` (60), `org.apache.commons.lang3` (29), `org.apache.commons.io` (7) und
  `org.slf4j` (40). Auf dem aktuellen Classpath vorhanden: Saxon-HE 12.4 (über Mustang),
  commons-io 2.11.0 (über FOP), slf4j. **Nicht vorhanden:** `javax.xml.bind:jaxb-api` mit einer
  JAXB-2-Runtime sowie `org.apache.commons:commons-lang3`. Diese müssten als Laufzeit-
  Abhängigkeiten ergänzt werden, was laut Dependency-Regel eine Freigabe erfordert (siehe
  Offene Punkte). Bis dahin ist der KoSIT-Validator zwar auf dem Classpath, aber nicht lauffähig.
- **Saxon-Versionskonflikt:** Mustang 2.17.0 verlangt Saxon-HE 12.4, KoSIT 1.5.0 wurde gegen
  11.4 gebaut. Es wird 12.4 festgeschrieben (`dependencyManagement`). Die Lauffähigkeit von
  KoSIT 1.5.0 auf Saxon 12 ist in Phase 3 durch die Pflichttests nachzuweisen; schlägt dies fehl,
  ist das eine Stopp-Bedingung (fehlende Bibliotheksfunktion), keine eigenmächtige Anpassung.
- **Zwei JAXB-Generationen** (`javax.xml.bind` für KoSIT, `jakarta.xml.bind` für Mustang)
  können koexistieren, da unterschiedliche Packages (nach Freigabe der JAXB-2-Abhängigkeiten).
- **Netzwerk-Lock:** Saxon-HE 12.4 bringt `org.xmlresolver:xmlresolver` mit
  `httpclient5` (runtime) mit. XML Resolver kann entfernte Kataloge/Ressourcen per HTTP laden.
  In Phase 2/3 wird Saxon bzw. der KoSIT-Validator mit einem lokalen Resolver konfiguriert und
  per Test nachgewiesen, dass kein Netzwerkzugriff stattfindet (z. B. Proxy auf ungültigen Host).
- **Versionspflege der XRechnung-Konfiguration** (Gültigkeitszeiträume der XRechnung-Versionen)
  ist Betriebsaufgabe; das verwendete Regelwerk wird je Validierung protokolliert.

## Entscheidungen vom 2026-09-24 (Freigabe zu Beginn Phase 2)

1. **veraPDF unter MPL 2.0:** `org.mustangproject:validator:2.17.0` ist eingebunden; veraPDF
   wird als unveränderte Bibliothek unter der MPL-2.0-Option genutzt. In der README (Phase 6)
   wird das unter "Drittanbieter-Lizenzen" ausgewiesen.
2. **KoSIT-Laufzeitabhängigkeiten ergänzt:** `javax.xml.bind:jaxb-api:2.3.1` und
   `org.apache.commons:commons-lang3`. Als JAXB-2-Implementierung wurde bewusst
   `com.sun.xml.bind:jaxb-impl:2.3.9` gewählt, **nicht** `org.glassfish.jaxb:jaxb-runtime:2.3.x`:
   Letzteres hat dieselben Maven-Koordinaten wie die von Mustang benötigte `jaxb-runtime:4.0.9`
   und würde sie per Konfliktauflösung verdrängen (im Probelauf nachgewiesen:
   "Implementation of Jakarta XML Binding-API has not been found"). Beide Implementierungen
   liegen in unterschiedlichen Packages und koexistieren.
3. **Saxon 12.4 mit KoSIT 1.5.0 nachgewiesen:** Der KoSIT-Modus `STRICT_LOCAL` ist mit Saxon 12
   nicht lauffähig (setzt einen null-`URIResolver`, den Saxon 12 mit NullPointerException
   ablehnt). Verwendet wird `ResolvingMode.CUSTOM` mit der eigenen
   `LocalOnlyResolvingStrategy` (Basis: `StrictRelativeResolvingStrategy`), die zusätzlich jede
   Auflösung mit Netzwerkschema (http, https, ftp) ablehnt. Der KoSIT-Report-DOM ist nicht
   namespace-bewusst; Elemente werden über lokale Namen gesucht. Eingaben werden als Bytes
   übergeben, da bei Pfad-Eingaben ein Dateihandle offen bleibt (Windows: Verschieben scheitert).
4. **XRechnung-Konfiguration:** ausgeliefert wird das KoSIT-Release `v2026-08-31`
   ("Validator Configuration 2026-08-31 compatible with XRechnung 3.0", 11 Szenarien, EN16931
   Schematron 1.3.16, XRechnung Schematron 2.6.0) unter `validator/xrechnung/`. XRechnung 3.0.2
   ist zugleich die aktuelle und die Vorgängerversion; eine ältere Konfiguration wird nicht
   mitgeliefert. Lizenz des Pakets: Apache 2.0 (KoSIT); die enthaltenen EN16931-Schematron-
   Artefakte (ConnectingEurope) tragen auf GitHub keine SPDX-Kennung ("Other") – siehe
   Offene Punkte.
5. **Anwendbarkeitsmatrix umgesetzt** in `ValidationService`; ZUGFeRD-Ausgaben werden
   zusätzlich über die eingebettete XML mit KoSIT geprüft (EN16931-Szenario der Konfiguration,
   verpflichtend nur bei Profil XRECHNUNG). Der End-to-End-Test weist für CII, UBL und ZUGFeRD
   EN16931 jeweils `VALID` ohne Fehler nach.
6. **PDF/A-Voraussetzung:** ZUGFeRD wird aus der unveränderten Quell-PDF erzeugt. Ist die
   Quell-PDF kein PDF/A, ist das Ergebnis kein gültiges PDF/A-3 (Probelauf mit Standard-14-Font:
   veraPDF meldet Verstöße) und der Run scheitert in der Mustang-Validierung. Word/Excel müssen
   daher mit PDF/A-Option exportieren; das wird in der README dokumentiert.

## Offene Punkte

1. Lizenzkennung der EN16931-Schematron-Artefakte im KoSIT-Paket (GitHub meldet "Other"); vor
   Aufnahme von `validator/` in das Repository bestätigen.
