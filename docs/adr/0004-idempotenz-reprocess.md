# ADR 0004: Idempotenz und Reprocess

- Status: Akzeptiert (2026-09-24)
- Betrifft: Module `watcher`, `ledger`, Vorgabe Abschnitte 5.2, 15, 16, 35, 36

## Kontext

Dieselbe Quell-PDF darf durch den Watcher nie zweimal verarbeitet werden. Ein manueller
Reprocess ist eine kontrollierte Ausnahme, die einen neuen, vollständig nachvollziehbaren Run
erzeugt und nichts überschreibt. Nach einem Neustart müssen Dateien in `processing/` erneut
geprüft werden; kein kritischer Zustand darf nur im Speicher liegen.

## Entscheidung

1. **Identität:** `tenant_id + SHA-256(Quell-PDF)`; als `UNIQUE` in `source_document`
   erzwungen. Der Mandant ergibt sich aus dem Inbox-(Unter-)Verzeichnis
   (`tenants[].inbox-subdirectory`); zwei aktive Mandanten dürfen nicht dasselbe Verzeichnis
   verwenden (beim Start geprüft).
2. **Watcher-Ablauf:** Datei stabil (Größe über `stable-checks` Intervalle unverändert,
   vollständig lesbar) → SHA-256 berechnen → `source_document` anlegen bzw. finden →
   existiert bereits ein Run mit `trigger=AUTO`: Duplikat (Event `DUPLICATE_DETECTED` am
   ursprünglichen Run mit Dateiname/Zeitpunkt, Datei nach `rejected/duplicates/` mit
   Hash-Suffix, kein neuer Run) → sonst Run `run_number=1`, Move nach `processing/`.
3. **Run-Nummern:** fortlaufend je Quelldokument (`UNIQUE(source_document_id, run_number)`).
   Korrelations-ID `sha-prefix(8)/run-NNN`.
4. **Reprocess:** nur über explizite Benutzeraktion (UI/CLI), nie automatisch. Erzeugt Run mit
   `trigger=MANUAL_REPROCESS`, `parent_run_id`, `requested_by`, `reason` (alle per
   `CHECK`-Constraint verpflichtend), Profilname/-hash und Anwendungsversion zum Zeitpunkt des
   Reprocess. Artefakte landen unter `run-NNN/`; frühere Runs bleiben vollständig erhalten.
   Reprocess einer versendeten Rechnung löst **niemals** einen Versand aus; erneuter Versand
   ist eine eigene Aktion mit eigenen Dispatch-Events.
5. **Statusregeln für Reprocess:** `FAILED`/`REVIEW`: nach Benutzeraktion erlaubt.
   `VALID`/`ARCHIVED`/`DISPATCHED`: zusätzliche explizite Bestätigung (UI: zweistufig;
   CLI: `--confirm`).
6. **Absturzsicherheit:** Jeder Zustandsübergang wird als Event persistiert, bevor der nächste
   Schritt beginnt. Beim Start: für jede Datei in `processing/` wird der offene Run
   (`finished_at IS NULL`) gesucht; existiert er, wird er mit `PROCESSING_FAILED`
   ("abgebrochen durch Neustart") abgeschlossen und ein neuer Run mit `trigger=AUTO`,
   `parent_run_id` = abgebrochener Run angelegt (Wiederanlauf ist keine Benutzeraktion und
   kein Reprocess im fachlichen Sinn, aber vollständig nachvollziehbar). Existiert keine
   Datenbankspur (Absturz vor Anlage), wird die Datei wie neu aus der Inbox behandelt.
7. **Sequentialität:** Innerhalb eines Mandanten genau ein Verarbeitungsthread; zwischen
   Mandanten `tenant-parallelism`.

## Alternativen

- **Idempotenz über Dateiname:** unzuverlässig (Umbenennung, gleiche Namen). Verworfen.
- **Reprocess durch Löschen des alten Runs:** verletzt Unveränderlichkeit. Verworfen.
- **Wiederanlauf als stiller Neustart desselben Runs:** Zwischenergebnisse wären nicht mehr
  zuordenbar. Verworfen.

## Konsequenzen

- Eine inhaltlich geänderte PDF (anderer Hash) ist ein neues Quelldokument, auch bei gleicher
  Rechnungsnummer; Kollisionen im Archiv/Output werden als Fehler behandelt (ADR 0006).
- Duplikate sind in der UI am ursprünglichen Run sichtbar.

## Entscheidung vom 2026-09-24 (Phase 2)

- Wiederanlauf erhält den eigenen Trigger **`RESTART_RECOVERY`** mit `parent_run_id` auf den
  abgebrochenen Run, ohne `requested_by`/`reason` (CHECK-Constraint in V1 entsprechend
  erweitert). Umgesetzt in `StartupRecovery`; der End-to-End-Test weist nach: abgebrochener Run
  → `PROCESSING_FAILED` + Abschluss `FAILED`, neuer Run `RESTART_RECOVERY` → `SUCCESS`.
- Dateien in `processing/` ohne offenen Run, deren Quelldokument bereits abgeschlossene Runs
  hat, werden nach `failed/verwaist/` verschoben und nicht erneut verarbeitet.
- Duplikate werden am jüngsten Run des Quelldokuments mit `DUPLICATE_DETECTED` protokolliert
  und nach `rejected/duplikate/` verschoben (Dateiname mit SHA-Präfix und Zeitstempel, damit
  auch mehrfache Duplikate nie kollidieren).
