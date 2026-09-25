# ADR 0012: V4 – Verwaltungsbereich mit Laufzeiteinstellungen und PDF-Einlesen

- Status: Akzeptiert (2026-09-25); Speicherort (Datenbank, Live-Übernahme) und Zugriffsschutz
  (Admin-Passwort aus Umgebungsvariable) vom Auftraggeber entschieden
- Betrifft: `db/migration/V4__app_settings.sql`, neues Modul `admin`, Module `configuration`,
  `ledger`, `watcher`, `dispatch`, `inboundvalidation`, `web`

## Kontext

Der Auftraggeber möchte alle benötigten Einstellungen (Pfade, Watcher, SMTP usw.) in der
Oberfläche pflegen und Rechnungen per Schaltfläche einlesen statt Dateien in `inbox/` zu
kopieren. Die Oberfläche hat bisher keine Anmeldung; Pfade und SMTP-Server sind
sicherheitsrelevant. Zugangsdaten dürfen laut Vorgabe nur aus Umgebungsvariablen kommen.

## Entscheidungen

1. **Laufzeiteinstellungen (`RuntimeConfig`)** umfassen genau das, was ohne Neustart wirken
   kann: die Arbeitsverzeichnisse inbox, processing, output, failed, manual-review, rejected,
   archive, inbound-validation; Watcher (aktiv, Intervall, stabile Prüfungen); Parallelität
   zwischen Mandanten; Ablage der Prüfprotokolle; SMTP-Server (aktiv, Host, Port, STARTTLS,
   SSL, Anmeldung, Absender, Timeout). **Nicht** enthalten und nur per YAML mit Neustart:
   Port, Datenverzeichnis (offene Datenbank), Profil- und Validator-Verzeichnisse (beim Start
   geladen und gehasht), Logverzeichnis, Upload-Grenze. Die Verwaltung zeigt sie schreibgeschützt.
2. **Speicherung:** Tabelle `app_settings` (append-only) mit dem vollständigen Stand als JSON
   je Datensatz plus Benutzer, Zeitpunkt, Notiz. Ein Gesamtstand statt Einzelwerten hält das
   Schema stabil, wenn Einstellungen hinzukommen, und macht jede Historienzeile für sich lesbar.
   Der jüngste Datensatz gilt; gibt es keinen, gilt `config/application.yaml`.
3. **Live-Übernahme:** `RuntimeSettings` (Modul `configuration`) hält den wirksamen Stand und
   benachrichtigt Verbraucher. `DirectoryLayout`, `EmailDispatcher`, `InboundValidationService`
   lesen bei jeder Verwendung; `InboxWatcher` plant sich bei Intervall- oder Aktivierungsänderung
   neu; `TenantExecutors` passt die Obergrenze für wartende Verarbeitungen an, laufende werden
   nicht unterbrochen. `AdminSettingsService` (Modul `admin`) übernimmt beim Start den
   Datenbankstand vor dem Start des Watchers und prüft/speichert Änderungen. Ein unbrauchbarer
   Datenbankstand wird protokolliert und ignoriert; es gilt dann die YAML.
4. **Prüfregeln:** Verzeichnisse nicht leer, nicht identisch, werden angelegt und müssen
   beschreibbar sein; das Verarbeitungsverzeichnis darf nur gewechselt werden, wenn das bisherige
   leer ist (sonst gingen laufende Vorgänge bis zum Neustart verloren). Intervall 250 ms bis 1 h,
   stabile Prüfungen 1–20, Parallelität 1–32, SMTP-Port und -Timeout in Grenzen, bei aktivem
   Versand Host und gültige Absenderadresse, STARTTLS und SSL nicht zugleich.
5. **Zugangsdaten** (SMTP, Admin) stehen weder in der Datenbank noch im Formular. Die
   Verwaltung zeigt nur, ob `SMTP_USERNAME` gesetzt ist.
6. **Zugriffsschutz:** `AdminAuthFilter` (HTTP Basic, eigener Servlet-Filter, keine neue
   Bibliothek) schützt `/verwaltung/**` und die Export-Einstellungen mit `app.admin.username`
   (Standard `admin`) und dem Passwort aus `ADMIN_PASSWORD`. Ohne Passwort antwortet der Bereich
   mit 503 „gesperrt", nie offen. Vergleich in konstanter Zeit. Die übrige Oberfläche bleibt
   wie in ADR 0008 ohne Anmeldung.
7. **PDF einlesen:** Schaltfläche auf „Ausgangsrechnungen" mit Mandantenauswahl, Datei und
   Benutzername. Die Datei wird nach Prüfung des `%PDF-`-Kopfs mit bereinigtem Namen in die
   Inbox des Mandanten gelegt (temporärer Name, dann Umbenennen, damit der Watcher nur
   vollständige Dateien sieht; Namenskollision ergibt einen Zeitstempel-Suffix, nie
   Überschreiben) und läuft durch die unveränderte Pipeline. Damit gelten Idempotenz, Ledger,
   Archiv und Reprocess-Semantik unverändert; es gibt keinen zweiten Verarbeitungsweg.

## Alternativen

- YAML aus der Oberfläche schreiben mit Neustart: verworfen durch Entscheidung des
  Auftraggebers zugunsten sofortiger Wirkung.
- Spring Security für die Anmeldung: neue Laufzeitabhängigkeit, daher nicht ohne Freigabe;
  für einen einzelnen geschützten Bereich genügt der Filter.
- Upload direkt in die Pipeline (ohne Inbox): verworfen, würde Watcher-Semantik (Stabilität,
  Duplikaterkennung, Mandantenzuordnung) duplizieren.

## Konsequenzen

- Flyway-Stand 4; Modulmatrix um `admin -> ledger, configuration, model, security` ergänzt.
- `AppProperties` erhält `admin`; `DirectoryLayout`, `InboxWatcher`, `TenantExecutors`,
  `EmailDispatcher`, `InboundValidationService`, `StatusController` lesen Laufzeiteinstellungen.
- Für den Betrieb ist `ADMIN_PASSWORD` zu setzen (README, Compose, systemd-Env, WinSW-Hinweis).

## Offene Punkte

- Anmeldung für die gesamte Oberfläche bleibt offen (ADR 0008).
- Test-Mail aus der Verwaltung (Verbindungsprüfung des SMTP-Servers) nicht umgesetzt.
- Änderungen an `config/tenant.yaml` und Profilen weiterhin nur per Datei und Neustart.
