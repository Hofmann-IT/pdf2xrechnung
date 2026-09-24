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

## Offene Punkte

- Punkt 6 (Wiederanlauf-Run mit `trigger=AUTO` und `parent_run_id`) weicht von der
  `CHECK`-Bedingung ab, die `parent_run_id` nur bei `MANUAL_REPROCESS` zulässt. Vor Phase 2
  ist zu entscheiden: eigener Trigger `RESTART_RECOVERY` (Schemaergänzung in Phase 2 zulässig)
  oder Wiederanlauf ohne Parent-Verweis. Empfehlung: eigener Trigger, da nachvollziehbarer.
