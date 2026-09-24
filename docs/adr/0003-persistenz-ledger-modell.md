# ADR 0003: Persistenz- und Ledger-Modell

- Status: Akzeptiert (2026-09-24)
- Betrifft: Modul `ledger`, `db/migration/V1__base_schema.sql`, Vorgabe Abschnitte 15–20, 47

## Kontext

Das Rechnungsausgangsbuch ist revisionsnah: bestehende fachliche Einträge dürfen nie per
UPDATE/DELETE verändert werden; nachgelagerte Zustände werden als append-only Events
gespeichert; der angezeigte Status ist eine Projektion. Stack: SQLite, Spring JDBC, Flyway,
kein ORM.

## Entscheidung

1. **Tabellen (V1):** `source_document`, `processing_run`, `ledger_entry`, `ledger_tax_line`,
   `processing_event`, `artifact`, `validation_result`, `inbound_validation`.
2. **Unveränderlichkeit wird in der Datenbank erzwungen**, nicht nur per Konvention:
   `BEFORE UPDATE`/`BEFORE DELETE`-Trigger mit `RAISE(ABORT)` auf allen append-only Tabellen.
   `processing_run` erlaubt genau eine Änderung: das einmalige Setzen von `finished_at` und
   `result` (alle anderen Spalten sind per Trigger fixiert). `source_document` ist vollständig
   unveränderlich.
3. **Datentypen:** Zeitstempel als ISO-8601-Text in UTC; Geldbeträge und Steuersätze als
   Dezimal-Text (z. B. `1234.56`), niemals `REAL`. Umrechnung in `BigDecimal` im Repository.
   Formate/Listen als JSON-Text (`generated_formats`, `details_json`).
4. **Status-Projektion:** `InvoiceStatus` wird in Java aus der Event-Folge eines Runs abgeleitet
   (letztes relevantes Event gewinnt; Dispatch-Events überlagern ARCHIVED). Kein Statusfeld in
   der Datenbank, keine SQL-View mit Geschäftslogik.
5. **Ledger-Inhalt** je Run: Rechnungsnummer, Datum, Kunde, Summen, Netto/Steuer je Steuersatz
   (`ledger_tax_line`), erzeugte Formate, Source-SHA-256, Profilname und -hash,
   Anwendungsversion, Zeitstempel. Pfade und Hashes der Artefakte liegen normalisiert in
   `artifact` (1:n je Run) und sind über `processing_run_id` eindeutig zugeordnet;
   Validierungsergebnisse in `validation_result`.
6. **Flyway:** `V1__base_schema.sql` ist die Basis. Bis Ende Phase 2 sind Ergänzungen im Rahmen
   der freigegebenen Phase erlaubt; danach sind Änderungen an veröffentlichten Migrationen
   ausgeschlossen (Stopp-Bedingung). `validate-on-migrate` ist aktiv.
7. **SQLite-Betrieb:** WAL-Modus, `foreign_keys=ON` je Verbindung, `busy_timeout`, Hikari-Pool
   mit genau einer Verbindung (SQLite kennt nur einen Schreiber). Mehrere Anwendungsinstanzen
   auf derselben Datenbank werden nicht unterstützt.

## Alternativen

- **Statusfeld mit UPDATE:** einfacher abzufragen, widerspricht der Vorgabe. Verworfen.
- **Event-Sourcing für alles (kein Ledger-Snapshot):** Ledger-Export und Listen wären teuer;
  Vorgabe verlangt explizit Ledger-Daten. Verworfen.
- **Trigger nur per Anwendungscode ersetzen:** schützt nicht vor direkten DB-Zugriffen. Verworfen.

## Konsequenzen

- Korrekturen fachlicher Daten sind nur über neue Runs (Reprocess) möglich; alte Einträge bleiben.
- Repositories (Phase 2) benötigen keine UPDATE-Statements außer dem Run-Abschluss.
- Tests prüfen in Phase 1 bereits, dass die Trigger UPDATE/DELETE abweisen.

## Offene Punkte

- Aufbewahrungsfristen/Export (DATEV) betreffen nur Lesezugriffe; DATEV-Formatvariante wird vor
  Phase 5 abgefragt (Stopp-Bedingung).
