# PDF-zu-E-Rechnung

On-Premises-Anwendung von Hofmann IT, die fertige PDF-Ausgangsrechnungen in E-Rechnungen
(XRechnung CII, XRechnung UBL, ZUGFeRD/Factur-X) überführt, diese validiert, revisionsnah
archiviert, ein Rechnungsausgangsbuch führt und optional versendet. Zusätzlich prüft sie
empfangene E-Rechnungen („E-Rechnung prüfen") und exportiert das Rechnungsausgangsbuch als
CSV oder DATEV-Buchungsstapel.

Zur Laufzeit findet **keine Netzwerkkommunikation** statt, außer zum ausdrücklich
konfigurierten SMTP-Server. Alle Validierungsressourcen liegen lokal.

| Stack | Version |
|-------|---------|
| Java (Eclipse Temurin empfohlen) | 21 |
| Spring Boot (Web, Thymeleaf, JDBC, Mail) | 3.5.16 |
| Apache PDFBox | 3.0.5 |
| Mustangproject (library, validator) | 2.17.0 |
| KoSIT validationtool, Konfiguration XRechnung 3.0.2 | 1.5.0 / 2026-08-31 |
| SQLite (xerial JDBC), Flyway | 3.49 / 11.7 |

Architekturentscheidungen: [docs/adr/](docs/adr/README.md). Lizenzen der Drittanbieter:
[THIRD-PARTY.md](THIRD-PARTY.md). Änderungen: [CHANGELOG.md](CHANGELOG.md).

## Inhalt

1. [Funktionsweise](#1-funktionsweise)
2. [Voraussetzungen](#2-voraussetzungen)
3. [Windows Installation](#3-windows-installation)
4. [Linux Installation](#4-linux-installation)
5. [Docker Installation](#5-docker-installation)
6. [Konfiguration](#6-konfiguration)
7. [Profil-Einrichtung](#7-profil-einrichtung)
8. [Kalibrierung](#8-kalibrierung)
9. [E-Rechnung validieren](#9-e-rechnung-validieren)
10. [Reprocess](#10-reprocess)
11. [Versand](#11-versand)
12. [Export, DATEV und Belegtransfer](#12-export-datev-und-belegtransfer)
13. [Betriebs-Checkliste](#13-betriebs-checkliste)
14. [Backup](#14-backup)
15. [Restore](#15-restore)
16. [Troubleshooting](#16-troubleshooting)
17. [Entwicklung](#17-entwicklung)

## 1. Funktionsweise

```
inbox/  ──►  Erkennen ──► Extrahieren ──► Mapping ──► Plausibilität ──► Erzeugen ──► Validieren ──► output/
   │                                          │                                          │
   │                                     manual-review/                                  ├──► archive/{jahr}/{monat}/{nr}/run-NNN/
   └── Nicht-Rechnung ──► rejected/       (Status REVIEW)                                ├──► Ledger + Events (SQLite)
                                                                                         └──► optional: E-Mail, Kommando, DATEV Belegtransfer
```

- **Watcher** beobachtet `inbox/` (je Mandant ein Unterverzeichnis oder die Wurzel). Eine
  Datei gilt als fertig, wenn ihre Größe über mehrere Prüfintervalle stabil ist. Alternativ
  lädt die Schaltfläche **PDF einlesen** auf „Ausgangsrechnungen" eine Rechnung hoch; sie
  wird in die Inbox des gewählten Mandanten gelegt und durchläuft dieselbe Verarbeitung.
- **Idempotenz:** Mandant + SHA-256 der PDF. Dieselbe Datei wird nie zweimal verarbeitet;
  Duplikate landen in `rejected/duplikate/` und werden am ursprünglichen Run protokolliert.
- **Extraktion** ist positionsbasiert (PDFBox, keine OCR). **Mapping** über Mandantenprofile
  (`profiles/*.yaml`) auf EN-16931-Business-Terms. **Plausibilität** prüft alle Summen mit
  Toleranz 0,01; Abweichungen führen zu `manual-review/` und Status REVIEW, nie zu einer
  E-Rechnung.
- **Validierung:** XRechnung verpflichtend mit KoSIT, ZUGFeRD mit Mustang (inkl. PDF/A-3 über
  veraPDF). Nur vollständig valide Ergebnisse gelangen nach `output/`.
- **Archiv und Ledger** sind append-only: nichts wird überschrieben oder gelöscht, auch nicht
  bei Reprocess. Jeder Lauf ist ein eigener `run-NNN` mit Profil-Hash und Anwendungsversion.
- **Oberfläche** (Port 8080): Dashboard, Ausgangsrechnungen, E-Rechnung prüfen, Manuelle
  Prüfung, Profile, Systemstatus, Export.

Verzeichnisse (relativ zum Arbeitsverzeichnis, alle in `config/application.yaml` änderbar):

| Verzeichnis | Inhalt | Sicherung |
|-------------|--------|-----------|
| `inbox/` | Eingang fertiger PDF-Rechnungen | nein (transient) |
| `processing/` | in Verarbeitung; nach Neustart automatisch wieder aufgenommen | nein |
| `output/` | fertige, validierte E-Rechnungen (Übergabe an Dritte) | optional |
| `failed/` | Erzeugungs-/Validierungsfehler mit Reports (`fehler.txt`) | optional |
| `manual-review/` | Plausibilitätsabweichungen (`pruefung.txt`, `extraction.json`) | optional |
| `rejected/` | Nicht-Rechnungen, Duplikate | optional |
| `archive/` | revisionsnahes Archiv, nie ändern | **ja** |
| `data/` | SQLite-Datenbank `erechnung.db` (Ledger, Events, Einstellungen) | **ja** |
| `config/` | `application.yaml`, `tenant.yaml` | **ja** |
| `profiles/` | Mandantenprofile | **ja** |
| `validator/` | KoSIT-Konfiguration (Teil der Auslieferung) | über Auslieferung |
| `logs/` | Protokolle (menschenlesbar und JSON/ECS, rotierend) | optional |
| `inbound-validation/` | optionale Prüfprotokolle von „E-Rechnung prüfen" | optional |

## 2. Voraussetzungen

- **Java 21** (JDK oder JRE), z. B. Eclipse Temurin. Kein weiteres Laufzeitpaket nötig.
- **PDF/A als Quelle für ZUGFeRD.** Mustang bettet das XML in die vorhandene PDF ein; ein
  gültiges ZUGFeRD entsteht nur, wenn die Quell-PDF bereits **PDF/A** ist (PDF/A-1b oder
  PDF/A-3b). Word, Excel und die meisten ERP-Systeme können beim Export „PDF/A" wählen. Ist die
  Quelle kein PDF/A, scheitert der Lauf in der Mustang-Validierung mit veraPDF-Meldungen und
  landet in `failed/`. XRechnung (CII/UBL) ist davon nicht betroffen.
- Text-PDFs, keine Scans: Die Extraktion arbeitet mit dem Textlayer, es gibt keine OCR.
- Arbeitsspeicher: 1 GB Heap genügen im Normalbetrieb (`-Xmx1g`).
- Für DATEV Belegtransfer: der DATEV-Client muss auf demselben Rechner installiert sein
  (nur Windows, siehe Abschnitt 12).

## 3. Windows Installation

1. JDK/JRE 21 installieren (z. B. Temurin), Systemvariable `JAVA_HOME` setzen, prüfen mit
   `java -version`.
2. Verzeichnis anlegen, z. B. `C:\pdf-zu-erechnung\`, und hineinkopieren:
   - `pdf-zu-erechnung.jar` (aus `target/` nach `mvn -q verify`, Abschnitt 17),
   - `config\`, `profiles\`, `validator\` aus dieser Auslieferung,
   - `deploy\windows\pdf-zu-erechnung.xml`.
3. `config\application.yaml` und `config\tenant.yaml` anpassen (Abschnitt 6).
4. Probelauf im Vordergrund (Konsole im Installationsverzeichnis):

```bash
java -jar pdf-zu-erechnung.jar
```

   Oberfläche: <http://localhost:8080>. Beenden mit Strg+C.

5. Als Dienst mit **WinSW** (MIT-Lizenz, <https://github.com/winsw/winsw>): die WinSW-EXE als
   `pdf-zu-erechnung.exe` neben die XML legen, dann als Administrator:

```bash
pdf-zu-erechnung.exe install
```

```bash
pdf-zu-erechnung.exe start
```

   Weitere Befehle: `status`, `stop`, `restart`, `uninstall`. Der Dienst startet automatisch
   (verzögert) mit Windows, startet sich nach Absturz neu und gibt der Anwendung beim Stopp
   60 Sekunden zum sauberen Abschluss. Prozessausgabe unter `logs\service\`.
6. SMTP-Zugangsdaten (falls Versand genutzt wird) **nur** als Systemumgebungsvariablen
   `SMTP_USERNAME` und `SMTP_PASSWORD` setzen, nie in Dateien. Nach dem Setzen den Dienst neu
   starten.
7. Optional: ein eigenes Dienstkonto mit Schreibrecht auf das Installationsverzeichnis und, bei
   Nutzung, auf das Belegtransfer-Verzeichnis (Abschnitt `serviceaccount` in der XML).

## 4. Linux Installation

```bash
sudo useradd --system --home-dir /opt/pdf-zu-erechnung --shell /usr/sbin/nologin erechnung
```

```bash
sudo mkdir -p /opt/pdf-zu-erechnung /var/lib/pdf-zu-erechnung/{inbox,processing,output,failed,manual-review,rejected,archive,data,inbound-validation} /var/log/pdf-zu-erechnung /etc/pdf-zu-erechnung
```

1. `pdf-zu-erechnung.jar`, `config/`, `profiles/`, `validator/` nach `/opt/pdf-zu-erechnung/`
   kopieren; Eigentümer `erechnung`, Datenverzeichnisse beschreibbar:

```bash
sudo chown -R erechnung:erechnung /opt/pdf-zu-erechnung /var/lib/pdf-zu-erechnung /var/log/pdf-zu-erechnung
```

2. In `/opt/pdf-zu-erechnung/config/application.yaml` alle `app.directories.*` außer
   `profiles` und `validator-resources` **absolut** auf `/var/lib/pdf-zu-erechnung/...` und
   `app.logging.directory` auf `/var/log/pdf-zu-erechnung` setzen. Die systemd-Unit erlaubt
   Schreibzugriff nur dort (`ProtectSystem=strict`).
3. Unit und Umgebungsdatei installieren:

```bash
sudo cp deploy/linux/pdf-zu-erechnung.service /etc/systemd/system/ && sudo cp deploy/linux/pdf-zu-erechnung.env.example /etc/pdf-zu-erechnung/env && sudo chmod 600 /etc/pdf-zu-erechnung/env
```

4. SMTP-Zugangsdaten in `/etc/pdf-zu-erechnung/env` eintragen (nur dort), dann:

```bash
sudo systemctl daemon-reload && sudo systemctl enable --now pdf-zu-erechnung
```

```bash
sudo systemctl status pdf-zu-erechnung
```

   Anwendungslogs: `/var/log/pdf-zu-erechnung/erechnung.log`, Dienstausgabe: `journalctl -u pdf-zu-erechnung`.
5. Optionaler Netzwerk-Lock auf Betriebssystemebene: in der Unit `IPAddressDeny=any` und
   `IPAddressAllow=<SMTP-Server>` aktivieren (auskommentiertes Beispiel in der Datei).

## 5. Docker Installation

Das Image enthält JRE 21, die Anwendung und die Validierungsressourcen. Konfiguration,
Profile und alle Datenverzeichnisse liegen außerhalb des Containers.

```bash
docker compose up -d --build
```

- Volumes laut Vorgabe: `inbox`, `processing`, `output`, `archive`, `config`, `profiles`,
  `data`; zusätzlich `failed`, `manual-review`, `rejected`, `inbound-validation`, `logs`. In
  `docker-compose.yaml` sind das Bind-Mounts relativ zur Datei; für den Betrieb absolute
  Host-Pfade eintragen. `config` und `profiles` werden nur lesend eingebunden.
- Vor dem ersten Start `config/application.yaml` und `config/tenant.yaml` sowie
  `profiles/standard.yaml` auf dem Host anlegen (Vorlagen liegen im Repository und im Image
  unter `/app/examples/`). Pfade in `application.yaml` bleiben relativ (`./inbox` usw.), sie
  zeigen im Container auf die Volumes.
- Der Container läuft als unprivilegierter Benutzer (UID 10001). Die gemounteten Verzeichnisse
  müssen für diese UID beschreibbar sein.
- SMTP-Zugangsdaten in einer `.env` neben der Compose-Datei (`SMTP_USERNAME`, `SMTP_PASSWORD`,
  Rechte 600). `.env` ist in `.gitignore`.
- Port 8080 ist standardmäßig nur an `127.0.0.1` gebunden. Für das Firmennetz einen Reverse
  Proxy mit Anmeldung vorschalten (Abschnitt 13).
- Der Build führt die Tests aus; `--build-arg SKIP_TESTS=true` nur, wenn `mvn -q verify`
  bereits anderweitig gelaufen ist.
- CLI-Befehle laufen im selben Image, z. B. Kalibrierung gegen eine PDF im Archiv-Volume
  (unter Git Bash auf Windows vorher `export MSYS_NO_PATHCONV=1`, sonst werden die
  Container-Pfade umgeschrieben):

```bash
docker compose run --rm --no-deps erechnung calibrate --samples /app/archive/2026/09/RE-1/run-001/original.pdf --profile /app/profiles/standard.yaml --tenant hofmann-it
```

- Geprüft mit Docker Desktop 29.8 / WSL 2 unter Windows 11: Image-Build mit Tests, Start über
  Compose (Healthcheck „healthy"), Verarbeitung einer PDF/A-Rechnung aus dem Inbox-Volume bis
  `output/` und `archive/`, Oberfläche, CSV-Export, CLI im Container.
- DATEV Belegtransfer aus einem Container heraus setzt voraus, dass das Belegtransfer-Verzeichnis
  des Windows-Hosts in den Container gemountet ist; empfohlen ist dafür die Windows-Installation.

Logs: `docker compose logs -f erechnung` und das Volume `logs/`.

## 6. Konfiguration

Spring Boot lädt `./config/application.yaml` (überschreibt die im Jar enthaltenen
Standardwerte) und `./config/tenant.yaml`, jeweils relativ zum Arbeitsverzeichnis.
**Keine Zugangsdaten in YAML-Dateien.**

### config/application.yaml

| Schlüssel | Bedeutung | Standard |
|-----------|-----------|----------|
| `server.port` | Port der Oberfläche | 8080 |
| `app.directories.*` | Arbeitsverzeichnisse (siehe Tabelle in Abschnitt 1) | `./…` |
| `app.watcher.enabled` | Inbox-Überwachung an/aus | true |
| `app.watcher.poll-interval` | Prüfintervall | 5s |
| `app.watcher.stable-checks` | Datei muss über N Intervalle unverändert sein | 2 |
| `app.processing.tenant-parallelism` | Mandanten parallel; innerhalb eines Mandanten immer sequentiell | 1 |
| `app.validation.kosit-repository`, `kosit-scenarios` | lokale KoSIT-Konfiguration | `./validator/xrechnung` |
| `app.inbound-validation.store-reports` | Prüfprotokolle von „E-Rechnung prüfen" ablegen | false |
| `app.inbound-validation.max-upload-size` | Upload-Grenze | 50MB |
| `app.smtp.*` | SMTP-Server für den Versand; `username`/`password` kommen aus `SMTP_USERNAME`/`SMTP_PASSWORD` | deaktiviert |
| `app.logging.directory` | Logverzeichnis | `./logs` |

### config/tenant.yaml

Ein Mandant je Eintrag unter `tenants:`:

| Schlüssel | Bedeutung |
|-----------|-----------|
| `id` | stabile Kennung (`[a-z0-9-]`), Teil der Idempotenz-Identität |
| `name` | Anzeigename |
| `enabled` | inaktive Mandanten werden vom Watcher ignoriert |
| `inbox-subdirectory` | Unterverzeichnis unter `inbox/`, `processing/`, `output/`, `failed/`, `manual-review/`, `rejected/`; leer = Wurzel. Zwei aktive Mandanten dürfen nicht dasselbe Verzeichnis nutzen |
| `profiles` | Profilnamen aus `profiles/`, in Prüfreihenfolge |
| `fixed-values` | Werte für Regeln vom Typ `fixed`: Verkäuferangaben, die nur grafisch im Briefpapier stehen (Name, Anschrift, USt-IdNr., IBAN, BIC, Währung) |
| `export.datev` | DATEV-Buchungsstapel: Beraternummer, Mandantennummer, Wirtschaftsjahresbeginn, Sachkontenlänge, Sachkontenrahmen 03/04, Debitorenstrategie, Erlöskonten je Steuerfall. Beispielwerte sind **Platzhalter** und kommen vom Steuerberater |
| `export.belegtransfer` | Verzeichnis des lokalen DATEV-Belegtransfer-Clients |

Die Export-Einstellungen lassen sich in der Oberfläche pflegen (Ausgangsrechnungen → Export →
Einstellungen bearbeiten); dort gespeicherte Werte gehen der YAML vor und werden mit Benutzer,
Zeitpunkt und Notiz historisiert.

Die Konfiguration wird beim Start vollständig geprüft. Fehler verhindern den Start und stehen
im Log (fail fast).

### Umgebungsvariablen

| Variable | Zweck |
|----------|-------|
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Zugangsdaten für den Versand |
| `ADMIN_PASSWORD` | Passwort für den Verwaltungsbereich (`/verwaltung`, Export-Einstellungen); Benutzername `admin`. Nicht gesetzt = Bereich gesperrt |
| `JAVA_OPTS` (Docker, systemd) | JVM-Optionen, z. B. `-Xmx1g` |
| `TZ` | Zeitzone (Zeitstempel im Ledger sind immer UTC) |

### Verwaltung in der Oberfläche

Unter **Verwaltung** (`/verwaltung`, Anmeldung mit `admin` und `ADMIN_PASSWORD`) lassen sich
die Laufzeiteinstellungen ohne Neustart ändern: Arbeitsverzeichnisse (außer `data`,
`profiles`, `validator`), Watcher (aktiv, Intervall, stabile Prüfungen), Parallelität,
Ablage der Prüfprotokolle und der SMTP-Server (Host, Port, STARTTLS/SSL, Anmeldung,
Absender, Timeout). Jede Speicherung ist ein neuer Datensatz mit Benutzer, Zeitpunkt und
Notiz; die Historie steht auf der Seite. In der Verwaltung gespeicherte Werte gehen der YAML
vor; die Seite und der Systemstatus zeigen, welche Quelle gerade gilt.

Nur mit Neustart über `config/application.yaml`: Port, Datenverzeichnis, Profil- und
Validator-Verzeichnis, Logverzeichnis, Upload-Grenze. Zugangsdaten (SMTP, Admin) stehen nie
in der Datenbank oder in Formularen. Von dort führt ein Link zu den Export- und
DATEV-Einstellungen je Mandant (Abschnitt 12), die dieselbe Anmeldung verlangen.

## 7. Profil-Einrichtung

Ein Profil (`profiles/<name>.yaml`) beschreibt, wie Rechnungen eines Layouts erkannt und auf
EN-16931-Felder abgebildet werden. `profiles/standard.yaml` ist vollständig kommentiert und
zeigt jede Regelart:

| Regelart | Verwendung |
|----------|------------|
| `anchor` | Text relativ zu einem Label, z. B. rechts von „Rechnungsnummer:" |
| `regex` | regulärer Ausdruck über den Seitentext, Gruppe 1 ist der Wert |
| `region` | Bereich in PDF-Punkten (Ursprung links oben), z. B. Anschriftenfeld |
| `table` | Positionstabelle mit Spalten je Business-Term (unter `lineItems`) |
| `fixed` | fester Wert aus `tenant.yaml` → `fixed-values` |

Vorgehen für ein neues Layout:

1. `profiles/standard.yaml` unter neuem Namen kopieren; `profile.name` muss dem Dateinamen
   entsprechen.
2. Klassifizierungs-Indikatoren (Rechnung, Gutschrift, Nicht-Rechnung) und Geschäftsfälle
   (Steuerbehandlung) anpassen. Befreiungsgründe für steuerfreie Fälle stimmen Sie mit dem
   Steuerberater ab.
3. Koordinaten und Anker mit der Kalibrierung ermitteln (Abschnitt 8).
4. Profil dem Mandanten in `tenant.yaml` zuordnen, Anwendung neu starten (Profile werden beim
   Start geladen und gehasht).
5. In der Oberfläche unter **Profile** mit einer Beispiel-PDF testen: Der Profil-Test führt
   Extraktion, Klassifizierung, Mapping und Plausibilität aus, erzeugt aber nichts und schreibt
   nichts ins Ledger.

Der SHA-256 jedes Profils steht je Lauf im Ledger; Änderungen bleiben nachvollziehbar.

## 8. Kalibrierung

Die CLI ermittelt für Beispiel-PDFs alle Fundstellen (Seite, Koordinaten, Rohtext,
transformierter Wert, Status) und meldet, welche Pflichtfelder fehlen:

```bash
java -jar pdf-zu-erechnung.jar calibrate --samples ./beispiele --profile ./profiles/standard.yaml --tenant hofmann-it --out kalibrierung.txt
```

- `--samples` ist ein Verzeichnis mit PDFs oder eine einzelne PDF.
- `--tenant` liefert die `fixed-values`; ohne Angabe bleiben `fixed`-Regeln leer.
- `--out` schreibt den Report (bestehende Datei wird nicht überschrieben); ohne `--out` auf die
  Konsole. Exit-Code 1, wenn ein Feld „NICHT bestanden" oder „FEHLER" meldet.

Im CLI-Modus laufen weder Web-Server noch Watcher, es wird nichts verarbeitet oder gespeichert.
Die Kalibrierung lässt sich daher auch auf einem Arbeitsplatzrechner gegen eine Kopie des
Profils ausführen. Denselben Report liefert der Profil-Test in der Oberfläche.

## 9. E-Rechnung validieren

Oberfläche → **E-Rechnung prüfen** (`/pruefen`): XML (XRechnung CII/UBL) oder PDF
(ZUGFeRD/Factur-X) hochladen. Das Ergebnis zeigt Dokumenttyp, erkannten Standard und Profil,
die Ergebnisse von KoSIT und Mustang getrennt, Fehler und Warnungen verständlich sowie
technische Details; Reports lassen sich herunterladen. Das Original wird nie verändert und
nur mit `store-reports: true` zusammen mit den Reports unter `inbound-validation/` abgelegt.
Jede Prüfung wird in der Datenbank protokolliert (Hash, Ergebnis, Zeitpunkt).

## 10. Reprocess

Ein Reprocess verarbeitet dasselbe Quelldokument technisch neu (z. B. nach Profilkorrektur)
in einem neuen `run-NNN`. Frühere Runs, Artefakte und Ledger-Einträge bleiben unverändert.
Ein Reprocess ist **keine** Rechnungskorrektur und **kein** Storno und löst **nie** einen
automatischen Versand oder eine automatische Belegtransfer-Übergabe aus.

- Oberfläche: Rechnungsdetail → Reprocess mit Benutzername und Begründung. Für bereits
  gültige, archivierte oder versendete Rechnungen ist eine ausdrückliche Bestätigung nötig.
- CLI für Wartungsfenster: Den Dienst vorher stoppen, denn zwei Instanzen auf derselben
  Datenbank und demselben Archiv werden nicht unterstützt. Im laufenden Betrieb den Reprocess
  über die Oberfläche auslösen.

```bash
java -jar pdf-zu-erechnung.jar reprocess --tenant hofmann-it --sha 3f2a9c1b --user "Uwe Hofmann" --reason "Profil korrigiert" --confirm
```

  `--sha` akzeptiert den vollständigen SHA-256 oder ein eindeutiges Präfix (aus der
  Rechnungsdetailseite). `--confirm` nur, wenn die Bestätigung verlangt wird.

Nach einem Neustart nimmt die Anwendung Dateien in `processing/` selbst wieder auf
(Trigger `RESTART_RECOVERY`); das ist kein Reprocess und braucht keine Benutzeraktion.

## 11. Versand

Optionaler E-Mail-Versand je Profil (`postProcess.email`): Empfänger, Betreff- und
Textvorlage, Anhänge (z. B. `ZUGFERD_PDF`, `XRECHNUNG_CII`). Voraussetzungen:

1. `app.smtp.enabled: true`, Host, Port, STARTTLS/SSL und Absender in `application.yaml`.
2. `SMTP_USERNAME` und `SMTP_PASSWORD` als Umgebungsvariablen (Abschnitte 3 bis 5).
3. Versand erfolgt automatisch nur nach vollständig validierter **Erstverarbeitung**; jeder
   Versuch wird als Event protokolliert (`DISPATCH_SUCCEEDED`/`DISPATCH_FAILED`).
4. Erneuter Versand ist eine eigene Benutzeraktion auf der Rechnungsdetailseite.

Alternativ oder zusätzlich kann ein lokales Programm aufgerufen werden
(`postProcess.command`, ohne Shell, mit Platzhaltern für die Artefaktpfade, Zeitlimit).

## 12. Export, DATEV und Belegtransfer

Oberfläche → Ausgangsrechnungen → **Export**:

- **Rechnungsausgangsbuch (CSV):** eine Zeile je Rechnung mit allen Ledger-Daten,
  Steueraufschlüsselung, Validierungsergebnissen, Artefakt-Hashes (Semikolon, Dezimalkomma,
  UTF-8 mit BOM, direkt in Excel/LibreOffice nutzbar).
- **DATEV-Format Buchungsstapel (EXTF):** nur wenn in den Export-Einstellungen aktiviert. Je
  Rechnung und Steuersatz eine Buchung auf Debitor (Sammeldebitor oder Konto je Kunde) gegen
  das konfigurierte Erlöskonto, Zeitraum innerhalb eines Wirtschaftsjahres. Alle Feldregeln
  werden vor der Ausgabe geprüft; Abweichungen (z. B. unzulässige Zeichen in Belegfeld 1)
  stehen in der Vorschau. Referenz: [docs/datev-format-referenz.md](docs/datev-format-referenz.md).
- Je Rechnung lassen sich auf der Detailseite DATEV-Zusatzfelder ergänzen (Leistungsdatum
  nur zusammen mit Datum der Steuerperiode und nach Absprache mit dem Steuerberater,
  Fälligkeit, USt-IdNr. des Kunden).
- Exportiert wird je Quelldokument der jüngste erfolgreiche Lauf. Jeder Download wird mit
  Benutzer, Zeitraum und SHA-256 im Exportprotokoll festgehalten; die Vorschau nicht.

**DATEV Belegtransfer (Unternehmen online):** Ist in den Export-Einstellungen ein
Belegtransfer-Verzeichnis aktiviert, kopiert die Anwendung nach jeder erfolgreichen
Erstverarbeitung die ZUGFeRD-PDF dorthin. Den Upload nach DATEV Unternehmen online übernimmt
allein der lokal installierte DATEV-Client (DATEV Belegtransfer, Windows), der das Verzeichnis
überwacht und hochgeladene Dateien in seinen Archivordner verschiebt. Einrichtung:

1. DATEV Belegtransfer installieren, anmelden und dort für das Unternehmen die Verzeichnisse
   anlegen (Belegtyp „Rechnungsausgang"), „Automatisch hochladen" aktivieren.
2. Den Pfad dieses Verzeichnisses in den Export-Einstellungen eintragen (absolut, lokal, kein
   Netzwerkpfad). Das Dienstkonto braucht dort Schreibrecht.
3. Übergaben stehen je Rechnung auf der Detailseite (COPIED / SKIPPED / FAILED) und lassen
   sich dort manuell wiederholen. Es wird nie überschrieben.

## 13. Betriebs-Checkliste

Vor Produktivstart:

- [ ] `config/tenant.yaml`: Mandanten-ID, Profile, `fixed-values` (Verkäuferdaten, IBAN) geprüft
- [ ] Profil mit echten Beispielrechnungen kalibriert; Profil-Test grün, Plausibilität bestanden
- [ ] Quell-PDFs werden als **PDF/A** exportiert (sonst kein ZUGFeRD)
- [ ] Testlauf: PDF in `inbox/` → Ergebnis in `output/`, Detailseite zeigt alle Validierungen VALID
- [ ] Verzeichnisse liegen auf gesichertem Speicher; `archive/` und `data/` in der Datensicherung (Abschnitt 14)
- [ ] Dienst eingerichtet (WinSW/systemd/Docker), Neustart nach Absturz geprüft
- [ ] `ADMIN_PASSWORD` gesetzt (sonst ist die Verwaltung gesperrt), Passwort nur beim Betreiber
- [ ] Oberfläche nur im Firmennetz erreichbar; **außer der Verwaltung hat die Anwendung keine Anmeldung**, daher
      Reverse Proxy mit Authentifizierung oder Beschränkung auf vertrauenswürdige Adressen
- [ ] Netzwerk: nur SMTP nach außen erlaubt (Firewall), keine weiteren Verbindungen nötig
- [ ] SMTP-Zugangsdaten ausschließlich als Umgebungsvariablen, Test-Mail an interne Adresse
- [ ] DATEV-Konten und Beraternummer vom Steuerberater bestätigt, Probeexport dort importiert
- [ ] Belegtransfer-Verzeichnis (falls genutzt) angelegt, Probeübergabe in DATEV sichtbar
- [ ] Zeitzone und Systemzeit korrekt (Belegdaten, Fristen)

Im Betrieb:

- Täglich: Dashboard (offene Vorgänge, Fehler), `manual-review/` und `failed/` sichten.
- REVIEW-Fälle: Ursache in `pruefung.txt` bzw. auf der Seite „Manuelle Prüfung"; nach
  Profilkorrektur Reprocess.
- Log-Rotation ist eingebaut (20 MB je Datei, 90 Tage, max. 2 GB). Dienstprotokolle (WinSW,
  journald) regelmäßig prüfen.
- Speicher: `archive/` wächst mit jeder Rechnung (PDF, XML, Reports je Lauf).
- Updates: neue Jar-Version einspielen, Dienst neu starten; Flyway führt Schemamigrationen
  automatisch aus. Vorher Backup (Abschnitt 14). Migrationen sind vorwärts gerichtet; ein
  Downgrade der Anwendung ist nur mit Restore der Datenbank möglich.

## 14. Backup

Zu sichern: `data/` (SQLite-Datenbank), `archive/`, `config/`, `profiles/`; optional `output/`,
`failed/`, `manual-review/`, `rejected/`, `inbound-validation/`, `logs/`.

Konsistente Sicherung:

1. Dienst stoppen (`pdf-zu-erechnung.exe stop`, `systemctl stop pdf-zu-erechnung` oder
   `docker compose stop`). Beim Stopp wird laufende Verarbeitung bis 60 s abgeschlossen.
2. Verzeichnisse kopieren. Die Datenbank besteht aus `erechnung.db` und, während des Betriebs,
   `erechnung.db-wal` und `erechnung.db-shm`; nach sauberem Stopp sind die WAL-Dateien leer
   oder nicht vorhanden. Immer alle drei sichern, falls vorhanden.
3. Dienst starten.

Sicherung ohne Stopp ist möglich, wenn das Backup-Werkzeug Snapshots des Dateisystems nutzt
(VSS, LVM) oder SQLite-bewusst arbeitet (`sqlite3 data/erechnung.db ".backup pfad"`); eine
einfache Dateikopie einer laufenden Datenbank kann inkonsistent sein.

`archive/` und die Datenbank gehören zusammen: Die Tabelle `artifact` verweist auf Pfade im
Archiv. Beide immer aus demselben Sicherungsstand wiederherstellen.

## 15. Restore

1. Dienst stoppen. Vorhandene Verzeichnisse nicht löschen, sondern beiseitelegen
   (z. B. `data.alt/`, `archive.alt/`).
2. `data/`, `archive/`, `config/`, `profiles/` aus derselben Sicherung zurückspielen.
3. `processing/` leeren oder prüfen: Dateien darin werden beim Start als abgebrochene Läufe
   erkannt und erneut verarbeitet (Wiederanlauf) oder, wenn das Dokument bereits abgeschlossene
   Läufe hat, nach `failed/verwaist/` verschoben.
4. Dienst starten, Log auf Flyway-Meldungen und Konfigurationsfehler prüfen, Dashboard und eine
   Rechnungsdetailseite mit Artefakt-Download testen.
5. PDFs, die zwischen Sicherung und Ausfall in `inbox/` eingingen, erneut in `inbox/` legen.
   Bereits verarbeitete Dateien werden anhand ihres Hashes als Duplikat erkannt.

## 16. Troubleshooting

| Symptom | Ursache und Abhilfe |
|---------|---------------------|
| Start bricht ab mit „Mandantenkonfiguration ist ungültig" | Fehlerliste im Log lesen: Profil fehlt, Mandanten-ID ungültig, doppeltes Inbox-Verzeichnis, DATEV-Werte außerhalb der Regeln. Korrigieren, neu starten |
| Start bricht ab mit `Unable to establish loopback connection` / `Selector.open()` | Betrifft die JVM, nicht die Anwendung: Ein Sicherheits- oder Backup-Produkt (z. B. Endpoint-Schutz mit Netzwerkfilter) blockiert lokale Loopback-Sockets. Java-Prozess in der Ausnahmeliste eintragen oder Filtertreiber prüfen; Test mit `java -jar` in einer anderen Sitzung/anderem Konto |
| Port 8080 belegt | `server.port` in `application.yaml` ändern |
| Datei bleibt in `inbox/` | Watcher deaktiviert? Datei noch im Schreibvorgang (Größe ändert sich)? Mandant `enabled: false`? Unterverzeichnis keinem Mandanten zugeordnet? Log prüfen |
| Datei in `rejected/` | Klassifizierung hat keine Rechnung erkannt (Begründung in der `.txt` daneben) oder Duplikat (`rejected/duplikate/`, Hash bereits verarbeitet) |
| Status REVIEW, Datei in `manual-review/` | Plausibilität verletzt oder Pflichtfeld fehlt; `pruefung.txt` und `extraction.json` bzw. Seite „Manuelle Prüfung" zeigen Feld und Fundstelle. Profil korrigieren, dann Reprocess |
| Status FAILED, Datei in `failed/`, Mustang/veraPDF-Fehler | Quell-PDF ist kein PDF/A (Abschnitt 2). PDF als PDF/A exportieren und erneut in `inbox/` legen (neuer Hash = neues Dokument) |
| FAILED mit KoSIT-Fehlern (BR-…, BR-DE-…) | Pflichtangaben fehlen oder sind inkonsistent (z. B. Leitweg-ID, Käuferland, Zahlungsangaben). Fehlertexte stehen im HTML-Report im Archiv/`failed/`; Profil oder `fixed-values` ergänzen |
| „Dateinamenskollision in output/" | Gleicher Dateiname bereits vorhanden; es wird nie überschrieben. Datei in `output/` abholen/wegräumen, dann Reprocess |
| `SQLITE_BUSY` im Log | Zweite Instanz auf derselben Datenbank (z. B. CLI-Reprocess während Verarbeitung) – kurz warten, die Anwendung wiederholt. Mehrere Dienstinstanzen auf einer Datenbank werden nicht unterstützt |
| Versand schlägt fehl | `app.smtp.*`, Umgebungsvariablen, Firewall; Details im Event `DISPATCH_FAILED` und im Log. Erneuter Versand über die Detailseite |
| DATEV-Export „nicht aktiviert" | Export-Einstellungen: DATEV-Buchungsstapel anbieten, Beraternummer/Mandantennummer/Konten eintragen |
| DATEV-Import lehnt Datei ab | Vorschau-Hinweise beachten; Kontenlänge und Sachkontenrahmen müssen dem DATEV-Mandat entsprechen; nur ein Wirtschaftsjahr je Datei |
| Belegtransfer FAILED | Verzeichnis existiert nicht oder Dienstkonto hat kein Schreibrecht; Meldung auf der Detailseite. Nach Korrektur manuell wiederholen |
| Oberfläche zeigt keine Rechnungen | Anderes Arbeitsverzeichnis/andere Datenbank als der Watcher (Dienst und manuelle Starts müssen dasselbe Arbeitsverzeichnis nutzen) |

Logdateien: `logs/erechnung.log` (menschenlesbar) und `logs/erechnung.json.log` (ECS-JSON für
Auswertungen), jeweils mit Korrelations-ID `sha8/run-NNN` je Lauf.

## 17. Entwicklung

```bash
mvn -q verify
```

erzeugt `target/pdf-zu-erechnung.jar` und führt alle Tests aus (Unit, End-to-End mit
selbst erzeugten PDF/A-Rechnungen, KoSIT/Mustang-Validierung, GreenMail-SMTP, MockMvc).
Tests verwenden ausschließlich temporäre Verzeichnisse unter `target/test-data/`. Die
Modulabhängigkeiten sind in [docs/adr/README.md](docs/adr/README.md) festgelegt und werden
durch einen Test erzwungen.

UI-Snapshots aller Seiten entstehen beim Test unter `target/ui-snapshots/`.
