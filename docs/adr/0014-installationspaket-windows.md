# ADR 0014: Windows-Installationspaket mit eigener Laufzeit

- Status: Akzeptiert (2026-09-25)
- Betrifft: `installer/windows/`, `docs/Handbuch.html`; Vorgabe Phase 6 (Betrieb)

## Kontext

Die Anwendung soll an Testkunden ohne technische Betreuung ausgeliefert werden. Die bisherige
Installation (JDK installieren, Dateien kopieren, WinSW einrichten, Umgebungsvariablen) setzt
Administrationswissen voraus. Gefordert ist ein Setup, das ohne Probleme durchläuft, und eine
Installations-/Betriebsdokumentation für Kunden.

## Entscheidungen

1. **Inno Setup 6** erzeugt eine signierbare `Setup.exe` (deutscher Assistent, Admin-Rechte,
   64 Bit). WiX/MSI wurde verworfen: `jpackage` in JDK 21 verlangt WiX 3 und legt die
   Anwendung in `Program Files` ab, wo die Arbeitsverzeichnisse nicht liegen dürfen. Inno Setup
   erlaubt getrennte Programm- und Datenordner, Dienstregistrierung und Nachlaufaktionen.
2. **Eigene Java-Laufzeit** per `jlink` aus dem Build-JDK (Temurin 21), 28 Module,
   ~64 MB. Die Modulliste wurde mit der vollständigen Testsuite auf genau dieser Laufzeit
   geprüft (`mvn test -Djvm=<runtime>\bin\java.exe`, 101 Tests). `jdk.attach` ist enthalten,
   weil die Testsuite (Mockito) es braucht; so ist die getestete Laufzeit die ausgelieferte.
   Der Kunde installiert kein Java.
3. **Windows-Dienst über WinSW 2.12.0** (MIT, 18 MB, ohne .NET-Abhängigkeit). Das Build-Skript
   lädt die Binärdatei mit fester Version und prüft die SHA-256-Summe
   `05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da`; die Lizenz wird mit
   ausgeliefert. Die Dienstkonfiguration entsteht bei der Installation aus einer Vorlage mit den
   tatsächlichen Pfaden.
4. **Ordnerstruktur:** Programm unter `C:\Program Files\PDF-zu-ERechnung` (Jar, `runtime\`,
   `validator\`, WinSW, `docs\`), Daten unter `C:\ProgramData\PDF-zu-ERechnung` als
   Arbeitsverzeichnis des Dienstes (`config\`, `profiles\`, `inbox\` … `archive\`, `data\`,
   `logs\`). Die Validator-Pfade werden dem Dienst als Startargumente übergeben, alle übrigen
   Pfade bleiben relativ zum Datenordner. `inbox`, `output`, `failed`, `manual-review`,
   `rejected` sind für Benutzer beschreibbar, damit Rechnungen per Explorer abgelegt werden.
5. **Keine Mandantendatei im Paket:** Nach der Installation gibt es keinen Mandanten und kein
   Passwort; der Browser öffnet automatisch den Einrichtungs-Assistenten (ADR 0013). Das Setup
   wartet bis zu 90 s, bis die Oberfläche antwortet, und öffnet erst dann den Browser.
6. **Update und Deinstallation:** Ein Update stoppt und entfernt den Dienst, ersetzt die
   Programmdateien, richtet den Dienst neu ein; `config\application.yaml` und
   `profiles\standard.yaml` werden nur angelegt, wenn sie fehlen. Die Deinstallation entfernt den
   Dienst und die Programmdateien, **nie** den Datenordner (Archiv, Ledger); ein Hinweis
   informiert darüber.
7. **Handbuch** `docs/Handbuch.html` für Kunden ohne technische Kenntnisse (Installation,
   Ersteinrichtung, tägliche Nutzung, Ergebnisse, Prüfung, Export, Versand, Verwaltung,
   Sicherung, Update, Fehlerbehebung, Hinweise für die IT); wird im Startmenü verlinkt.
   Standalone-HTML, druckbar als PDF, ohne externe Ressourcen.

## Alternativen

- MSI mit WiX: siehe oben; zudem zusätzliche Toolchain.
- Vollständige JRE statt jlink: ~3× größer, keine Vorteile.
- Dienst mit `sc.exe`/NSSM: WinSW ist bereits die dokumentierte Dienstlösung (Phase 6).

## Konsequenzen

- Build-Werkzeuge auf dem Entwicklerrechner: JDK 21, Maven, Inno Setup 6 (winget
  `JRSoftware.InnoSetup`). Build: `installer\windows\build-installer.ps1`, Ergebnis
  `target\installer\PDF-zu-ERechnung-<Version>-Setup.exe` mit `.sha256`.
- Die Setup-Datei ist nicht code-signiert; Windows SmartScreen zeigt beim ersten Start eine
  Warnung („Weitere Informationen → Trotzdem ausführen"). Für eine Auslieferung an Kunden
  empfiehlt sich ein Code-Signing-Zertifikat (offener Punkt).
- Port 8080 ist fest vorbelegt; bei Konflikt Anpassung in `config\application.yaml`.

## Offene Punkte

- Code-Signierung von Setup und WinSW-Wrapper.
- Der Installationslauf selbst (UAC, Dienstregistrierung, Browserstart) konnte in der
  Entwicklungsumgebung nicht ausgeführt werden (kein interaktives Admin-Fenster); geprüft
  wurden Kompilierung des Setups, die Laufzeit mit der Testsuite und der Start der Anwendung
  aus der Paketstruktur im CLI-Modus. Der erste Testlauf erfolgt auf einem Zielrechner.
