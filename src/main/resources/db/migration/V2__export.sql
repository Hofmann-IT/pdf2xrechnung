-- =====================================================================================
-- V2: Export (ADR 0009, ADR 0010)
--
--   * ledger_entry: zusätzliche, aus der Rechnung extrahierte Felder (BT-9 Fälligkeit,
--     BT-72 Lieferdatum, BT-48 USt-IdNr. des Käufers, BT-46 Kundennummer); nullable, damit
--     bestehende Zeilen unverändert gültig bleiben. ALTER TABLE ändert keine Zeile.
--   * export_settings: DATEV-Einstellungen je Mandant, in der Oberfläche pflegbar; append-only,
--     der jüngste Datensatz je Mandant gilt (Historie mit Benutzer und Zeitstempel)
--   * invoice_export_field: vom Benutzer je Rechnung ergänzte DATEV-Felder (Leistungsdatum
--     #115 mit Datum Zuordnung Steuerperiode #116, Fälligkeit #117, USt-IdNr. #40); append-only,
--     Schlüssel ist das Quelldokument, damit die Angaben einen Reprocess überdauern
--   * export_log: Protokoll jedes heruntergeladenen Exports (Zeitraum, Anzahl, SHA-256)
--
-- Alle neuen Tabellen sind append-only (Trigger), wie in V1.
-- =====================================================================================

ALTER TABLE ledger_entry ADD COLUMN due_date      TEXT;
ALTER TABLE ledger_entry ADD COLUMN delivery_date TEXT;
ALTER TABLE ledger_entry ADD COLUMN buyer_vat_id  TEXT;
ALTER TABLE ledger_entry ADD COLUMN buyer_id      TEXT;

-- -------------------------------------------------------------------------------------
CREATE TABLE export_settings (
    id                        INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id                 TEXT    NOT NULL,
    created_at                TEXT    NOT NULL,
    created_by                TEXT    NOT NULL,
    note                      TEXT,
    datev_enabled             INTEGER NOT NULL CHECK (datev_enabled IN (0, 1)),
    consultant_number         TEXT,
    client_number             TEXT,
    fiscal_year_start         TEXT    NOT NULL,
    account_length            INTEGER NOT NULL CHECK (account_length BETWEEN 4 AND 8),
    chart_of_accounts         TEXT    NOT NULL,
    debtor_strategy           TEXT    NOT NULL CHECK (debtor_strategy IN ('COLLECTIVE', 'PER_CUSTOMER')),
    collective_debtor_account TEXT,
    customer_accounts_json    TEXT    NOT NULL,
    revenue_accounts_json     TEXT    NOT NULL,
    origin                    TEXT    NOT NULL,
    exported_by               TEXT    NOT NULL,
    dictation_shortcut        TEXT    NOT NULL,
    lock_records              INTEGER NOT NULL CHECK (lock_records IN (0, 1)),
    booking_text_template     TEXT    NOT NULL
);

CREATE INDEX ix_export_settings_tenant ON export_settings (tenant_id, id);

CREATE TRIGGER trg_export_settings_no_update
BEFORE UPDATE ON export_settings
BEGIN
    SELECT RAISE(ABORT, 'export_settings ist append-only');
END;

CREATE TRIGGER trg_export_settings_no_delete
BEFORE DELETE ON export_settings
BEGIN
    SELECT RAISE(ABORT, 'export_settings ist append-only');
END;

-- -------------------------------------------------------------------------------------
CREATE TABLE invoice_export_field (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    source_document_id INTEGER NOT NULL REFERENCES source_document (id),
    created_at         TEXT    NOT NULL,
    created_by         TEXT    NOT NULL,
    service_date       TEXT,
    tax_period_date    TEXT,
    due_date           TEXT,
    buyer_vat_id       TEXT,
    note               TEXT,
    -- Leistungsdatum (#115) darf laut DATEV nur zusammen mit #116 übergeben werden
    CONSTRAINT ck_invoice_export_field_service CHECK ((service_date IS NULL) = (tax_period_date IS NULL))
);

CREATE INDEX ix_invoice_export_field_source ON invoice_export_field (source_document_id, id);

CREATE TRIGGER trg_invoice_export_field_no_update
BEFORE UPDATE ON invoice_export_field
BEGIN
    SELECT RAISE(ABORT, 'invoice_export_field ist append-only');
END;

CREATE TRIGGER trg_invoice_export_field_no_delete
BEFORE DELETE ON invoice_export_field
BEGIN
    SELECT RAISE(ABORT, 'invoice_export_field ist append-only');
END;

-- -------------------------------------------------------------------------------------
CREATE TABLE export_log (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id     TEXT    NOT NULL,
    variant       TEXT    NOT NULL CHECK (variant IN ('CSV', 'DATEV_BUCHUNGSSTAPEL')),
    date_from     TEXT,
    date_to       TEXT,
    file_name     TEXT    NOT NULL,
    sha256        TEXT    NOT NULL CHECK (length(sha256) = 64),
    size_bytes    INTEGER NOT NULL CHECK (size_bytes >= 0),
    record_count  INTEGER NOT NULL CHECK (record_count >= 0),
    invoice_count INTEGER NOT NULL CHECK (invoice_count >= 0),
    skipped_count INTEGER NOT NULL CHECK (skipped_count >= 0),
    created_at    TEXT    NOT NULL,
    created_by    TEXT    NOT NULL
);

CREATE INDEX ix_export_log_tenant ON export_log (tenant_id, id);

CREATE TRIGGER trg_export_log_no_update
BEFORE UPDATE ON export_log
BEGIN
    SELECT RAISE(ABORT, 'export_log ist append-only');
END;

CREATE TRIGGER trg_export_log_no_delete
BEFORE DELETE ON export_log
BEGIN
    SELECT RAISE(ABORT, 'export_log ist append-only');
END;
