-- =====================================================================================
-- V3: Übergabe an DATEV Belegtransfer (ADR 0011)
--
--   * export_settings: Zielverzeichnis des lokalen DATEV-Belegtransfer-Clients je Mandant.
--     Bestehende Datensätze bleiben gültig (Standard: deaktiviert).
--   * belegtransfer_transfer: jede Übergabe (Kopie der ZUGFeRD-PDF in das Verzeichnis) mit
--     Ergebnis, Zielpfad, Hash, Akteur und Zeitpunkt; append-only. Kein neuer Ereignistyp in
--     processing_event, damit die Ereignistabelle nicht neu aufgebaut werden muss.
-- =====================================================================================

ALTER TABLE export_settings ADD COLUMN belegtransfer_enabled   INTEGER NOT NULL DEFAULT 0 CHECK (belegtransfer_enabled IN (0, 1));
ALTER TABLE export_settings ADD COLUMN belegtransfer_directory TEXT;

CREATE TABLE belegtransfer_transfer (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    processing_run_id INTEGER NOT NULL REFERENCES processing_run (id),
    artifact_id       INTEGER REFERENCES artifact (id),
    target_path       TEXT,
    sha256            TEXT    CHECK (sha256 IS NULL OR length(sha256) = 64),
    outcome           TEXT    NOT NULL CHECK (outcome IN ('COPIED', 'SKIPPED', 'FAILED')),
    message           TEXT    NOT NULL,
    actor             TEXT    NOT NULL,
    created_at        TEXT    NOT NULL
);

CREATE INDEX ix_belegtransfer_transfer_run ON belegtransfer_transfer (processing_run_id, id);

CREATE TRIGGER trg_belegtransfer_transfer_no_update
BEFORE UPDATE ON belegtransfer_transfer
BEGIN
    SELECT RAISE(ABORT, 'belegtransfer_transfer ist append-only');
END;

CREATE TRIGGER trg_belegtransfer_transfer_no_delete
BEFORE DELETE ON belegtransfer_transfer
BEGIN
    SELECT RAISE(ABORT, 'belegtransfer_transfer ist append-only');
END;
