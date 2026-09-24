# ADR 0007: Postprozess, Reprocess und CLI

- Status: Akzeptiert (2026-09-24)
- Betrifft: Module `dispatch`, `watcher` (ReprocessService), `cli`, Vorgabe Abschnitte 16, 22, 23, 32

## Kontext

Nach erfolgreicher Verarbeitung soll optional per SMTP versendet und/oder ein lokales Programm
gestartet werden. Ein Reprocess ist eine kontrollierte Ausnahme von der Idempotenz und darf nie
automatisch erneut versenden. Die Profil-Kalibrierung braucht eine CLI.

## Entscheidungen

1. **Versand nur aus dem Archiv:** Anhänge sind die archivierten Artefakte des Runs
   (`artifact`-Tabelle); es wird nie aus `output/` oder `processing/` versendet. Vorbedingung:
   Run-Ergebnis `SUCCESS` und Status `VALID`/`ARCHIVED`/`DISPATCH_*`. Jeder Versuch erzeugt
   `DISPATCH_ATTEMPTED` und danach `DISPATCH_SUCCEEDED` (mit Message-ID) oder `DISPATCH_FAILED`
   (mit Fehlertext); Empfänger, Betreff und Anhänge stehen in `details_json`.
2. **Automatischer Postprozess** nur bei Trigger `AUTO`. Bei `MANUAL_REPROCESS` nie. Bei
   `RESTART_RECOVERY` nur, wenn der abgebrochene Vorgänger noch keinen `DISPATCH_ATTEMPTED`
   hatte (sonst könnte ein bereits versendetes Dokument doppelt zugestellt werden). Ein erneuter
   Versand ist immer eine eigene Benutzeraktion (`PostProcessService.dispatchManually`).
3. **SMTP-Client:** `JavaMailSenderImpl` aus spring-context-support (Teil des Stacks über
   spring-boot-starter-mail); Host, Port, STARTTLS, SSL, Auth und Timeouts aus
   `app.smtp`, Zugangsdaten aus `SMTP_USERNAME`/`SMTP_PASSWORD`. `starttls.required` folgt der
   STARTTLS-Einstellung, damit bei aktiviertem STARTTLS nie im Klartext gesendet wird.
4. **Lokales Kommando:** `ProcessBuilder` mit Executable und Argumentliste; Shell-Interpreter
   (sh, bash, cmd, powershell, pwsh) sind als Executable verboten; Platzhalter werden je
   Argument ersetzt und nie interpretiert; fehlender Platzhalterwert ist ein Fehler; Timeout mit
   `destroyForcibly`; stdout/stderr werden auf 64 KB begrenzt mitgeschnitten und protokolliert.
   **Persistenz des Kommandoergebnisses:** Die vorgegebene Event-Liste enthält keinen Typ dafür,
   und das Schema ist nach Phase 2 eingefroren (Vorgabe 47). Das Ergebnis wird daher nur
   geloggt; ein Event-Typ `POSTPROCESS_COMMAND_COMPLETED/FAILED` (Migration V2) ist als
   Entscheidung offen.
5. **Reprocess:** `ReprocessService` sucht das Quelldokument über SHA-256 (oder eindeutiges
   Präfix ab 8 Zeichen), verlangt Benutzer und Begründung, verweigert bei offenem Run, verlangt
   bei Status `VALID`/`ARCHIVED`/`DISPATCH_*` die Bestätigung `confirm`, kopiert das archivierte
   Original nach `processing/` und startet die Pipeline mit `MANUAL_REPROCESS` und
   `parent_run_id`. Events: `REPROCESS_REQUESTED` am Vorgänger, `REPROCESS_STARTED` und
   `REPROCESS_COMPLETED` am neuen Run. Damit neue Artefakte alte nie überschreiben, hängt die
   Dateinamensbildung ab Run 2 `_run-NNN` an, sofern das Template `{runNumber}` nicht enthält.
6. **CLI-Modus:** `calibrate` und `reprocess` starten den Spring-Kontext ohne Web-Server und
   ohne Watcher (`SpringApplicationBuilder.web(NONE)`), führen den Befehl aus und beenden sich
   mit Exit-Code (0 ok, 1 fachlich nicht bestanden, 2 technischer Fehler, 3 Reprocess verweigert,
   64 Bedienfehler). Die Kalibrierung erzeugt, archiviert und versendet nichts und schreibt
   keinen Ledger-Eintrag; der Report geht auf stdout oder mit `--out` in eine neue Datei
   (nie überschreiben).

## Alternativen

- **Spring Boot `spring.mail.*`-Autokonfiguration:** würde eine zweite Konfigurationsquelle
  neben `app.smtp` einführen. Verworfen zugunsten einer einzigen, dokumentierten Stelle.
- **Kommando über `cmd /c` bzw. `sh -c`:** von der Vorgabe ausdrücklich verboten.
- **Reprocess aus `manual-review/` oder `failed/`:** die dort liegenden Kopien könnten bereits
  verändert sein; das Archiv ist die verlässliche Quelle.

## Konsequenzen

- GreenMail (Apache 2.0) als Test-Abhängigkeit für den Versandtest.
- Bei aktiviertem Versand ist `app.smtp.from` Pflicht; sonst schlägt der Versand mit
  `DISPATCH_FAILED` fehl und bleibt nachvollziehbar.
