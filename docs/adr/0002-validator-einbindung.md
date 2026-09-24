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

## Offene Punkte (Entscheidung erforderlich)

1. `org.mustangproject:validator` zieht veraPDF, das **dual-lizenziert (GPLv3 oder MPL 2.0)**
   ist. Laut Dependency-Regel ist bei GPL zu stoppen. Optionen: (a) Nutzung unter MPL 2.0
   freigeben; (b) ZUGFeRD-Validierung auf das in `library` enthaltene Regelwerk beschränken und
   PDF/A-Prüfung als "nicht zuverlässig prüfbar" ausweisen; (c) PDFBox Preflight (Apache 2.0)
   für PDF/A-Aspekte prüfen – Preflight unterstützt PDF/A-1, nicht PDF/A-3, wäre also nur
   eingeschränkt nutzbar.
2. **Freigabe der vom KoSIT-Validator benötigten, nicht deklarierten Laufzeit-Abhängigkeiten:**
   `javax.xml.bind:jaxb-api:2.3.1` + `org.glassfish.jaxb:jaxb-runtime:2.3.x` (beide EDL/CDDL,
   Eclipse Distribution License 1.0 = BSD-3) und `org.apache.commons:commons-lang3` (Apache 2.0).
   Alternative: KoSIT-Validator in einer neueren Version prüfen, sofern eine mit Jakarta-JAXB
   existiert (am 2026-09-24 war 1.5.0 die höchste Version auf Maven Central).
3. Welche XRechnung-Version(en) der KoSIT-Konfiguration ausgeliefert werden (aktuell gültige
   Version plus Vorgängerversion für Eingangsrechnungen?).
