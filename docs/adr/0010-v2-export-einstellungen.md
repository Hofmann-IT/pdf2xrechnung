# ADR 0010: V2-Migration – Export-Einstellungen, Rechnungs-Zusatzfelder, Exportprotokoll

- Status: Akzeptiert (2026-09-24), Schemaänderung vom Auftraggeber freigegeben
- Betrifft: `db/migration/V2__export.sql`, Module `ledger`, `export`, `web`; ergänzt ADR 0003 und 0009

## Kontext

Nach ADR 0009 sollen Kontenrahmen, Konten, Berater- und Mandantennummer vom Kunden in der
Oberfläche gepflegt werden können, ebenso die DATEV-Felder Leistungsdatum (#115), Datum
Zuordnung Steuerperiode (#116), Fälligkeit (#117) und USt-IdNr. des Käufers (#40) je Rechnung.
Außerdem soll nachvollziehbar sein, wann welcher Export mit welchem Inhalt heruntergeladen wurde.

Vorab gegen die DATEV-Dokumentation geprüft (developer.datev.de, Buchungsstapel, abgerufen
2026-09-24):

- #40: Länderkürzel (2 Zeichen, Griechenland `EL`, Nordirland `XI`) plus USt-IdNr. bis 13 Zeichen,
  Beispiel `"DE133546770"`; nur für EU-Sachverhalte.
- #115: Format TTMMJJJJ; beim Import **muss** #116 gefüllt sein; der Einsatz ist mit dem
  Steuerberater abzustimmen.
- #117: Format TTMMJJJJ, nur bei OPOS relevant; dann ist Belegfeld 2 (#12) nicht für
  OPOS-Informationen nutzbar.

DATEV Belegtransfer (Wissensplattform Dok.-Nr. 1020025, Stand 16.09.2026): Der lokale
DATEV-Client legt je Unternehmen und Belegtyp Verzeichnisse an, überwacht sie („Automatisch
hochladen"), überträgt PDF, GIF, JPG, TIF, PNG und XML (max. 20 MB je Datei) nach DATEV
Unternehmen online und verschiebt hochgeladene Dateien in einen eigenen Archivordner. Für diese
Anwendung heißt das: Eine Anbindung besteht aus dem Kopieren der erzeugten Belege in ein
konfiguriertes Verzeichnis; die Netzwerkverbindung hält ausschließlich der DATEV-Client.
Das ist ein eigener, noch nicht freigegebener Schritt.

## Entscheidungen

1. **`ledger_entry` erhält vier nullable Spalten** für bereits extrahierte Werte: `due_date`
   (BT-9), `delivery_date` (BT-72), `buyer_vat_id` (BT-48), `buyer_id` (BT-46). Sie werden von
   der Pipeline mitgeschrieben und in der CSV ausgegeben. Bestehende Zeilen bleiben unverändert.
2. **`export_settings`** (append-only, Historie mit Benutzer, Zeitstempel und Notiz): die
   DATEV-Einstellungen je Mandant. Wirksam ist der jüngste Datensatz; gibt es keinen, gilt
   `export.datev` aus `config/tenant.yaml`. Gespeichert wird nur, was dieselben Prüfregeln
   besteht wie die YAML beim Start (`ProfileRegistry.validateDatev`). Kontenzuordnungen liegen
   als JSON-Text (`customer_accounts_json`, `revenue_accounts_json`).
3. **`invoice_export_field`** (append-only): je Quelldokument vom Benutzer ergänzte Werte für
   #115/#116, #117 und #40. Schlüssel ist das Quelldokument, damit die Angaben einen Reprocess
   überdauern. Datenbank und Service erzwingen: #115 nur zusammen mit #116; USt-IdNr. mit
   Länderkürzel. Benutzerangaben haben Vorrang vor extrahierten Werten. Das Leistungsdatum wird
   **nie** automatisch befüllt, weil DATEV die Absprache mit dem Steuerberater verlangt.
4. **`export_log`** (append-only): jeder Download mit Variante, Zeitraum, Dateiname, SHA-256,
   Größe, Anzahl Datensätze/Rechnungen/übersprungene Vorgänge, Benutzer, Zeitpunkt. Die Vorschau
   wird nicht protokolliert. Der Benutzername ist beim Download Pflicht (wie bei Reprocess).
5. **Kein neues Event** und keine Änderung an `processing_event`: Export ist lesend und betrifft
   keinen einzelnen Run.

## Alternativen

- Einstellungen weiterhin nur in YAML mit Neustart: verworfen, weil der Kunde die Werte selbst
  pflegen soll und die Historie (wer hat wann welches Konto geändert) revisionsrelevant ist.
- Zusatzfelder als Spalten in `ledger_entry` per Reprocess: verworfen, ein Reprocess erzeugt
  neue Artefakte und ist für eine Datumsergänzung unverhältnismäßig.

## Konsequenzen

- Flyway-Stand ist 2; `ApplicationContextAndSchemaTest` prüft Tabellen, Spalten und Trigger.
- `LedgerEntryRow` hat vier zusätzliche Felder; alle Aufrufer wurden angepasst.
- Oberfläche: Export-Download verlangt `user`; Exportseite zeigt Herkunft der Einstellungen
  (Datenbank / YAML) und das Exportprotokoll. Die Pflegeseiten (Einstellungen bearbeiten,
  Rechnungsfelder ergänzen) sind der nächste Schritt; die Services dafür sind vorhanden und getestet.

## Offene Punkte

- **Pflegeseiten** für `export_settings` und `invoice_export_field` (Schritt 3).
- **Belegtransfer-Verzeichnis** als Postprozess (Schritt 4); benötigt vermutlich ein neues
  Event in `processing_event` und damit V3.
- **Zugriffsschutz** bleibt wie in ADR 0008 offen; Kontenänderungen sind ohne Anmeldung möglich,
  aber lückenlos protokolliert.
