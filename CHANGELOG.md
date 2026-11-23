# Changelog

Format nach [Keep a Changelog](https://keepachangelog.com/de/1.1.0/); Versionierung nach SemVer.
Die Versionsnummer steht im `pom.xml` und wird im Seitenfuß, im Systemstatus und je Verarbeitungslauf im Ledger angezeigt.

## [1.1.1] – 2026-11-23 (Korrekturen nach dem ersten Kundentest, Rechnungsvorlagen)

Keine Schemaänderung, keine geänderte Konfiguration. Das zuerst ausgelieferte Setup 1.1.0
(20.11.2026) enthält diese Punkte nicht. Läuft der Dienst unter einem eigenen Benutzerkonto
(Zugriff auf Netzlaufwerke), nach dem Update die Dienstanmeldung prüfen.

### Behoben nach dem ersten Kundentest (2026-11-22)
- Die Kommandozeilen-Befehle `calibrate` und `reprocess` starteten seit der Anmeldung für alle
  Seiten nicht mehr (die Web-Sicherheit wurde auch ohne Web-Server aufgebaut). Behoben und mit
  einem Test abgesichert, der die Anwendung wie die Kommandozeile ohne Web-Server startet.

### Rechnungsvorlagen: mehrseitige Rechnungen, Excel-Vorlage, Anleitung (2026-11-23)
- Behoben: In `Rechnungsvorlage.docx` wiederholt sich der Kopf der Positionstabelle jetzt auf
  jeder Folgeseite (`w:tblHeader`); Tabellenzeilen werden nicht mehr über den Seitenwechsel
  getrennt. Bisher las das Profil `standard` bei mehrseitigen Rechnungen aus der Vorlage die
  Positionen ab Seite 2 nicht, die Rechnung blieb in der manuellen Prüfung.
- Neu: `Rechnungsvorlage.xlsx` (Blätter „Rechnung" und „Hinweise", ohne Makros; Spalten und
  Zeilenhöhen an den Koordinaten des Profils `standard`, Summen als Formeln, Drucktitel für
  den Tabellenkopf). Mit Excel erzeugt; Autor, Druckereinstellungen und Speicherpfad entfernt.
- Neu: Anleitung `Rechnungsvorlage-anpassen.pdf` (Schritt für Schritt: mitgelieferte Vorlage
  mit eigenem Briefkopf oder eigene Vorlage umbauen, PDF/A, Test, häufige Meldungen,
  Checkliste); Quelle `docs/Rechnungsvorlage-anpassen.html`.
- Einrichtungs-Assistent und Seite „Profile" bieten alle drei Dateien an;
  `Rechnungsvorlage-Felder.html` beschreibt die Kopfzeilenwiederholung.
- Geprüft: beide Vorlagen als PDF exportiert, ein- und mehrseitig (45 Positionen), mit
  `calibrate` gegen `profiles/standard.yaml`: alle Positionen gelesen, Plausibilität bestanden.
  Neuer Test `InvoiceTemplateFilesTest` sichert Kopfzeilenwiederholung und Drucktitel ab.
- Version 1.1.1.

## [1.1.0] – 2026-11-20 (Dienst + Windows-Verwaltungsanwendung, ADR 0015)

**Beim Update von 1.0.0 beachten:** Alle Benutzer müssen sich anmelden (das Konto aus der
Ersteinrichtung ist der erste Administrator, sein Passwort gilt weiter). Die Oberfläche ist
danach nur noch auf dem Server selbst erreichbar, bis der Zugriff aus dem Firmennetz
eingeschaltet wird. Das Datenbankschema wird beim ersten Start auf V6 gehoben; vor dem Update
den Datenordner sichern.

### Phase D – Installationspaket und Handbuch (2026-11-20)
- Setup mit zwei Installationsarten: „Server: Dienst und Verwaltung" und „Arbeitsplatz: nur
  Verwaltung". Startmenü- und optionale Desktop-Verknüpfung starten die Verwaltungsanwendung;
  optional Autostart des Symbols im Infobereich.
- Aufgabe „Zugriff aus dem Firmennetz erlauben": führt `netzbetrieb-einrichten.ps1` aus
  (Zertifikat, Firewall-Regel) und schreibt `config\network.yaml`; schlägt das fehl, bleibt die
  Installation lokal nutzbar. Die Deinstallation entfernt die Firewall-Regel; Zertifikat und
  Daten bleiben erhalten.
- Setup prüft die WebView2-Laufzeit und nennt bei Fehlen die Lösung; eine Arbeitsplatz-
  Installation über einem vorhandenen Dienst wird abgelehnt.
- Handbuch überarbeitet: Anwendung und Infobereich, Anmeldung und Benutzer, neuer Abschnitt
  „Weitere Arbeitsplätze im Firmennetz", Update-Hinweise, Fehlersuche, IT-Hinweise.
- Version 1.1.0.

### Phase A – Anmeldung für alle Funktionen (2026-11-20)
- V6: Tabelle `user_account` (append-only, PBKDF2-SHA256-Hash) mit den Rollen Administrator
  und Sachbearbeiter; das bisherige Verwaltungskonto aus V5 wird als erster Administrator
  übernommen, das Passwort gilt weiter.
- Alle Seiten verlangen eine Anmeldung über `/anmelden` (Sitzung, Abmelden in der Kopfzeile);
  Verwaltung und Export-Einstellungen nur für Administratoren. HTTP Basic entfällt.
- Verwaltung → **Benutzer**: anlegen, Rolle ändern, sperren/freigeben, Passwort neu vergeben;
  „Mein Konto" zum Ändern des eigenen Passworts. Der letzte Administrator ist geschützt.
- Fünf Fehlversuche sperren einen Benutzernamen für fünf Minuten; gesperrte oder in der Rolle
  geänderte Konten verlieren ihre Sitzung mit der nächsten Anfrage.
- CSRF-Schutz für alle schreibenden Anfragen; Sitzungs-Cookie HttpOnly und SameSite=Strict.
- Der angemeldete Benutzer ersetzt die Freitextfelder „Benutzer" (Export, Reprocess, Versand,
  Belegtransfer, Zusatzfelder, PDF-Upload, Einstellungen).
- Geändert: Der Einrichtungs-Assistent ist nur noch ohne Anmeldung erreichbar, solange kein
  Administrator existiert; `ADMIN_PASSWORD` bleibt als Betreiber-Zugang mit Administratorrechten.
- Neue Laufzeitabhängigkeit `spring-boot-starter-security` (Apache 2.0, freigegeben).

### Phase B – Netzzugriff und Verschlüsselung (2026-11-20)
- **Geändert:** Die Oberfläche ist standardmäßig nur noch auf dem Computer des Dienstes
  erreichbar (HTTP auf `127.0.0.1:8080`); bisher lauschte der Dienst auf allen Schnittstellen.
- Neu: Zugriff aus dem Firmennetz als bewusste Einstellung (Verwaltung → Netzzugriff, wirkt
  nach Dienst-Neustart): HTTPS auf Port 8443 (TLS 1.2/1.3) zusätzlich zum lokalen Zugang.
- Zertifikat und privater Schlüssel liegen im Windows-Zertifikatsspeicher (nicht exportierbar,
  keine Schlüsseldatei, kein Passwort). `deploy/windows/netzbetrieb-einrichten.ps1` erzeugt
  das Zertifikat und die Firewall-Regel (nur Domänen- und private Netze).
- Verwaltung und Systemstatus zeigen den wirksamen Modus, die Adresse und den Fingerabdruck
  (SHA-256). Ist das Zertifikat nicht nutzbar, bleibt der Dienst lokal und nennt den Grund.
- Modus `open` (HTTP auf allen Schnittstellen) für Container und externen Reverse Proxy; das
  Docker-Image setzt `APP_NETWORK_MODE=open` und verhält sich wie bisher.
- jlink-Laufzeit des Installationspakets um `jdk.crypto.mscapi` erweitert.

### Phase C – Windows-Verwaltungsanwendung (2026-11-20)
- Neu: `PdfZuERechnung.Verwaltung.exe` (`desktop/windows/`, WinForms + WebView2): eigenes
  Fenster ohne Adresszeile, Symbol im Infobereich mit Dienststatus, Dienst starten/stoppen/neu
  starten auf dem Server, Protokollordner, Handbuch; nur eine Instanz, Fensterlage wird gemerkt.
- Verbindung mit „Dieser Computer" oder einem Server im Firmennetz; der Fingerabdruck des
  Serverzertifikats wird beim ersten Verbinden bestätigt und danach bei jeder Verbindung
  geprüft. Bei Abweichung wird nicht verbunden.
- Downloads mit „Speichern unter"; Navigation außerhalb des Dienstes wird blockiert, fremde
  Links öffnen im Standardbrowser. Verständliche Meldungen statt Fehlerseiten.
- Es werden keine Passwörter gespeichert (Einstellungen je Windows-Benutzer unter `%APPDATA%`).
- Build mit `desktop/windows/build-desktop.ps1` (C#-Compiler aus Windows, WebView2-SDK
  1.0.3856.49 mit geprüfter SHA-256 und Signatur, BSD-3-Clause).
- Behoben (Fehler aus Phase A): Die PDF-Vorschau in der manuellen Prüfung wurde vom
  Schutz-Header `X-Frame-Options: DENY` blockiert; jetzt `SAMEORIGIN`.

## [1.0.0] – 2026-09-25

### Rechnungsvorlage für Word/Excel-Nutzer (2026-10-08)
- Einrichtungs-Assistent und Seite „Profile" bieten `Rechnungsvorlage.docx` (WordprocessingML,
  ohne Makros, aus XML erzeugt) und `Rechnungsvorlage-Felder.html` (alle Pflichtangaben, Aufbau,
  Positionstabelle, Anschriftenposition, PDF/A-Export in Word, Excel, LibreOffice) zum Download;
  abrufbar unter `/vorlagen/` auch vor der Einrichtung.
- Profil `standard`: Kundennummer (`Kunden-Nr.:`, BT-46) und Rückfall der Käuferreferenz BT-10
  auf Leitweg-ID → Bestellung → Kundennummer; Käufer-E-Mail BT-49 ist Pflicht (manuelle
  Prüfung statt Validierungsfehler); Einheiten-Kurzformen (Stk., Std., m, km, Tag, Pauschal)
  werden auf UN/ECE-Codes abgebildet.

### Kalibrierung und Korrekturen aus dem ersten Praxistest (2026-10-08)
- Profil `profiles/hofmann-it.yaml` für Ausgangsrechnungen aus DATEV Rechnungsschreibung
  online (nur XRechnung, da kein PDF/A): Belegnummer, Belegdatum, Leistungsdatum,
  Kundennummer, Anschrift, Summen, Steuer, Zahlungsziel, Positionstabelle mit Einheiten-Umsetzung
  (Meter → MTR usw.). Entscheidungen: BT-10 aus Leitweg-ID, Bestellnummer oder Kundennummer;
  BT-49 wird mit dem Label „E-Mail:" auf die Rechnung gedruckt und ist Pflicht.
- Behoben: Reprocess einer Rechnung in manueller Prüfung konnte die Prüfablage nicht schreiben,
  weil der Ordner aus Lauf 1 existierte; ab dem zweiten Lauf tragen Ablagen in
  `manual-review/`, `failed/` und `rejected/` das Suffix `_run-NNN`.
- Favicon; fehlende Adressen liefern 404 ohne Fehlerprotokoll; Rechnungsliste aktualisiert sich
  nach „PDF einlesen" automatisch.

### Einrichtungs-Assistent (2026-09-25, ADR 0013)
- V5: Tabelle `admin_credential` (append-only, PBKDF2-SHA256-Hash).
- Erster Aufruf ohne Mandant oder Passwort leitet auf `/einrichtung`: Unternehmen mit
  Verkäuferangaben, Verzeichnisse, Watcher, Versand und Verwaltungszugang in einem Formular;
  schreibt `config/tenant.yaml` (mit Sicherung), lädt Mandanten ohne Neustart neu.
- Admin-Passwort wird in der Anwendung vergeben und in der Verwaltung geändert;
  `ADMIN_PASSWORD` bleibt als zusätzlicher Zugang für Betreiber.
- Verwaltung: „Mandanten und Profile neu laden" für manuelle Dateiänderungen.
- Start ohne Mandanten ist erlaubt (Einrichtungsmodus); Versionsnummer 1.0.0 im Seitenfuß.

### Verwaltungsbereich und PDF-Einlesen (2026-09-25, ADR 0012)
- V4: Tabelle `app_settings` (append-only, JSON-Gesamtstand je Datensatz).
- Seite **Verwaltung** (`/verwaltung`): Verzeichnisse, Watcher, Parallelität, Prüfprotokolle
  und SMTP-Server in der Oberfläche pflegen; Änderungen wirken sofort ohne Neustart,
  Historie mit Benutzer, Zeitpunkt und Notiz. Nur-Neustart-Einstellungen werden
  schreibgeschützt angezeigt. Zugangsdaten bleiben ausschließlich in Umgebungsvariablen.
- Zugriffsschutz per HTTP Basic (`admin` / `ADMIN_PASSWORD`) für Verwaltung und
  Export-Einstellungen; ohne Passwort gesperrt.
- Schaltfläche **PDF einlesen** auf „Ausgangsrechnungen": Upload in die Inbox des Mandanten,
  Verarbeitung über die unveränderte Pipeline, nie überschreiben.

### Windows-Installationspaket (2026-09-25, ADR 0014)
- `installer/windows/`: Inno-Setup-Skript, Dienstvorlage und Build-Skript. Das Setup enthält
  eine per `jlink` erzeugte Java-Laufzeit (mit der Testsuite geprüft), WinSW 2.12.0 (geprüfte
  Prüfsumme), Validierungsressourcen und Handbuch; richtet den Dienst ein, startet ihn und
  öffnet den Einrichtungs-Assistenten. Daten unter `C:\ProgramData\PDF-zu-ERechnung`, nie
  durch Deinstallation gelöscht.
- `docs/Handbuch.html`: Installations- und Betriebshandbuch für Kunden.
- CLI `calibrate` meldet ohne konfigurierten Mandanten einen verständlichen Fehler.
- Behoben (2026-10-08): Setup registrierte den Dienst, bevor die Dienstkonfiguration
  geschrieben war; Registrierung und Start laufen jetzt im Code-Teil nach dem Schreiben,
  Ausgaben unter `logs\service\setup-*.log`.

### Phase 6 – Betrieb (2026-09-24)
- Dockerfile (Multi-Stage, JRE 21, unprivilegierter Benutzer, Validierungsressourcen im Image),
  `docker-compose.yaml` mit den Volumes inbox, processing, output, archive, config, profiles, data
  sowie failed, manual-review, rejected, inbound-validation, logs.
- WinSW-Dienstkonfiguration (`deploy/windows/pdf-zu-erechnung.xml`) und systemd-Unit mit
  Härtung (`deploy/linux/pdf-zu-erechnung.service`, `pdf-zu-erechnung.env.example`).
- README mit Installation (Windows, Linux, Docker), Konfiguration, Profil-Einrichtung,
  Kalibrierung, Validierung, Reprocess, Versand, Export/DATEV, Betriebs-Checkliste, Backup,
  Restore, Troubleshooting; `THIRD-PARTY.md` mit den Lizenzen aller Laufzeitabhängigkeiten.
- Stabiler Artefaktname `target/pdf-zu-erechnung.jar` (`finalName` im POM).
- Docker-Image und Compose-Betrieb mit Docker Desktop/WSL 2 verifiziert: Dienstbenutzer auf
  UID/GID 10001 (1000 ist im Ubuntu-Basisimage vergeben).
- Behoben: Die Sperre für Shell-Interpreter im lokalen Kommando-Postprozess erkannte unter
  Linux keine Windows-Pfade (`C:\...\cmd.exe`), weil `\` dort kein Pfadtrenner ist; die
  Prüfung ist jetzt plattformunabhängig.

### Export und DATEV (Zwischenschritte nach Phase 5, 2026-09-24)
- Export des Rechnungsausgangsbuchs als CSV und als DATEV-Format „Buchungsstapel" (EXTF 700,
  Formatversion 13) mit Vorschau, Download und Exportprotokoll (ADR 0009).
- V2: Export-Einstellungen je Mandant in der Datenbank (Historie), Rechnungs-Zusatzfelder
  (#115/#116, #117, #40), Exportprotokoll; zusätzliche Ledger-Felder BT-9, BT-72, BT-48, BT-46
  (ADR 0010). Pflegeseiten in der Oberfläche.
- V3: Übergabe der ZUGFeRD-PDF an das lokale DATEV-Belegtransfer-Verzeichnis, automatisch bei
  Erstverarbeitung, manuell wiederholbar, nie überschreibend, protokolliert (ADR 0011).

### Phase 5 – Web-Oberfläche (2026-09-24)
- Dashboard, Ausgangsrechnungen mit Filtern und Detailansicht (Prozess-Timeline, Extraktion,
  Artefakte, Historie, Reprocess, Versand), E-Rechnung prüfen mit Upload, Manuelle Prüfung,
  Profile mit Profil-Test, Systemstatus. Spring MVC, Thymeleaf, HTMX (lokal), ohne externe
  Ressourcen (ADR 0008).

### Phase 4 – Postprozess und CLI (2026-09-24)
- Optionaler SMTP-Versand (Zugangsdaten nur aus Umgebungsvariablen), sichere lokale
  Prozessausführung ohne Shell, CLI `calibrate` und `reprocess`, Reprocess nur mit
  Begründung und Bestätigung, nie mit automatischem erneutem Versand (ADR 0007).

### Phase 3 – Inbound-Validierung (2026-09-24)
- Separate Prüfung empfangener E-Rechnungen (XRechnung CII/UBL, ZUGFeRD/Factur-X) mit KoSIT
  und Mustang; Originale werden nie verändert; optionales Prüfprotokoll (ADR 0005).

### Phase 2 – Ausgangs-Pipeline (2026-09-24)
- Watcher → Extraktion (PDFBox, positionsbasiert) → Mapping (Profile: anchor, regex, region,
  table, fixed) → Plausibilität → Erzeugung XRechnung CII/UBL und ZUGFeRD (Mustang) →
  Validierung (KoSIT, Mustang) → Ablage → Ledger → Events → Archiv; Idempotenz über
  Mandant + SHA-256; Wiederanlauf nach Neustart (ADR 0001–0004, 0006).

### Phase 1 – Architektur und Gerüst (2026-09-24)
- Maven-Projekt, Modulstruktur mit geprüfter Abhängigkeitsmatrix, Flyway-Basisschema mit
  Append-only-Triggern, Beispiel `config/tenant.yaml`, vollständig kommentiertes
  `profiles/standard.yaml`, ADRs.
