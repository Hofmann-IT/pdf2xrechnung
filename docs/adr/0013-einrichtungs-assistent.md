# ADR 0013: V5 – Einrichtungs-Assistent, Admin-Passwort in der Anwendung, Mandanten neu laden

- Status: Akzeptiert (2026-09-25); Passwort als Hash in der Datenbank und Mandantendatei mit
  Live-Neuladen vom Auftraggeber entschieden
- Betrifft: `db/migration/V5__admin_credential.sql`, Module `admin`, `configuration`,
  `ledger`, `web`; ergänzt ADR 0012

## Kontext

Die Anwendung wird bei mehreren Kunden installiert. Beim ersten Aufruf sollen alle nötigen
Werte (Mandant mit Verkäuferangaben, Verzeichnisse, Watcher, Versand, Verwaltungszugang) in der
Oberfläche erfasst werden, ohne YAML-Dateien zu editieren oder Umgebungsvariablen zu setzen.
Bisher scheiterte der Start ohne Mandanten (fail fast), und das Admin-Passwort kam nur aus
`ADMIN_PASSWORD`.

## Entscheidungen

1. **Einrichtungsmodus statt Startabbruch:** Ohne Mandanten startet die Anwendung; ein Filter
   leitet jede Seite (außer Assistent und statischen Ressourcen) auf `/einrichtung` um, solange
   kein aktiver Mandant oder keine Anmeldung existiert. Der Assistent ist in dieser Phase ohne
   Anmeldung erreichbar (es gibt noch keine); danach zeigt er nur noch einen Hinweis.
2. **Ein Formular, vier Abschnitte:** Unternehmen/Mandant (Kennung, Name, Verkäuferangaben
   BT-27/35/37/38/40/31/32/43/41/42, IBAN/BIC/Kontoinhaber, Währung), Verzeichnisse und
   Verarbeitung, Versand (optional), Verwaltungszugang. Die Kennung wird aus dem Firmennamen
   vorgeschlagen. Geprüft werden Pflichtfelder, Ländercode, USt-IdNr.-Form, IBAN-Prüfziffer
   (XRechnung BR-DE-19) und dass USt-IdNr. oder Steuernummer vorhanden ist (BR-DE-16).
3. **Mandantendatei bleibt Quelle der Wahrheit:** Der Assistent schreibt `config/tenant.yaml`
   (Pfad `app.tenant-file`) aus einer Vorlage mit den festen Werten, die
   `profiles/standard.yaml` verwendet; DATEV und Belegtransfer bleiben deaktiviert und werden
   in der Oberfläche gepflegt. Eine vorhandene Datei wird vorher als `tenant.yaml.bak-<Zeit>`
   gesichert. Schlägt das Neuladen fehl, wird die vorherige Datei zurückgespielt.
4. **Neuladen ohne Neustart:** `ProfileRegistry` hält Profile und Mandanten als austauschbaren
   Stand; `reload()` liest Profile und die Mandantendatei mit denselben Bindungsregeln wie Spring
   Boot beim Start (`YamlPropertySourceLoader` + `Binder`, kebab-case, Defaults) und validiert
   wie bisher. Ein fehlerhafter Stand wird abgewiesen, der bisherige bleibt wirksam. Die
   Verwaltung bietet „Mandanten und Profile neu laden" für manuelle Dateiänderungen.
5. **Admin-Passwort in der Anwendung:** Tabelle `admin_credential` (append-only) mit
   Benutzername und PBKDF2-SHA256-Hash (120 000 Iterationen, 16 Byte Salt, JDK-Bordmittel).
   Mindestlänge 10 Zeichen, Wiederholung. `ADMIN_PASSWORD` bleibt als zusätzlicher gültiger
   Zugang für Betreiber (z. B. Notfall bei vergessenem Passwort). Vergleiche in konstanter Zeit,
   Passwörter werden nie protokolliert.
6. **Ablauf des Abschlusses:** prüfen → Mandantendatei schreiben und neu laden →
   Laufzeiteinstellungen speichern (ADR 0012) → Passwort speichern. Fehler in einem Schritt
   brechen ab und melden alle Gründe; bereits geschriebene Dateien werden zurückgespielt.

## Alternativen

- Mandanten in der Datenbank: verworfen (Entscheidung des Auftraggebers), Datei bleibt für
  Backup, Docker-Volume und manuelle Pflege sichtbar.
- Passwort weiterhin nur per Umgebungsvariable: verworfen, für Kunden ohne IT-Betreuung zu
  umständlich.
- Mehrseitiger Assistent mit Zwischenspeicherung: unnötig, ein Formular mit Abschnitten reicht
  und vermeidet halbfertige Zustände.

## Konsequenzen

- Flyway-Stand 5; `AppProperties` erhält `tenantFile`; Start ohne Mandanten ist erlaubt.
- Seiten, die einen ersten Mandanten voraussetzen (Export), melden ohne Mandanten einen
  verständlichen Fehler statt abzustürzen.
- README: Erstinstallation läuft über den Assistenten; `ADMIN_PASSWORD` ist optional.

## Offene Punkte

- Zweiter und weitere Mandanten werden weiterhin in der Datei gepflegt (Assistent legt genau
  einen an); eine Mandantenverwaltung in der Oberfläche wäre ein eigener Schritt.
- Passwort-Reset ohne Umgebungsvariable (z. B. per CLI) nicht umgesetzt.
