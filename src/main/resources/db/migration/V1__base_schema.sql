-- =====================================================================================
-- V1: Basisschema PDF-zu-E-Rechnung (SQLite)
--
-- Grundsätze (Vorgabe Abschnitte 15–20, ADR 0003/0004):
--   * source_document: tenant_id + sha256 eindeutig (Idempotenz)
--   * processing_run: unveränderlich; nur finished_at/result dürfen genau einmal gesetzt werden
--   * ledger_entry, ledger_tax_line, processing_event, artifact, validation_result,
--     inbound_validation: append-only, UPDATE und DELETE werden durch Trigger abgewiesen
--   * Zeitstempel als ISO-8601-Text (UTC, z. B. 2026-09-24T10:15:30.123Z)
--   * Geldbeträge als Dezimal-Text (z. B. '1234.56'), nie als REAL (keine Rundungsfehler)
--   * Der in der UI angezeigte Status wird aus processing_event projiziert und NICHT gespeichert
-- =====================================================================================

CREATE TABLE source_document (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id         TEXT    NOT NULL,
    sha256            TEXT    NOT NULL CHECK (length(sha256) = 64),
    original_filename TEXT    NOT NULL,
    size_bytes        INTEGER NOT NULL CHECK (size_bytes >= 0),
    first_seen_at     TEXT    NOT NULL,
    CONSTRAINT uq_source_document_tenant_sha UNIQUE (tenant_id, sha256)
);

CREATE TABLE processing_run (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    source_document_id  INTEGER NOT NULL REFERENCES source_document (id),
    run_number          INTEGER NOT NULL CHECK (run_number >= 1),
    trigger_type        TEXT    NOT NULL CHECK (trigger_type IN ('AUTO', 'MANUAL_REPROCESS')),
    parent_run_id       INTEGER REFERENCES processing_run (id),
    requested_by        TEXT,
    reason              TEXT,
    profile_name        TEXT    NOT NULL,
    profile_hash        TEXT    NOT NULL CHECK (length(profile_hash) = 64),
    application_version TEXT    NOT NULL,
    correlation_id      TEXT    NOT NULL,
    started_at          TEXT    NOT NULL,
    finished_at         TEXT,
    result              TEXT    CHECK (result IS NULL OR result IN ('SUCCESS', 'REVIEW', 'FAILED', 'REJECTED', 'DUPLICATE')),
    CONSTRAINT uq_processing_run_number UNIQUE (source_document_id, run_number),
    CONSTRAINT ck_processing_run_finished CHECK ((finished_at IS NULL) = (result IS NULL)),
    CONSTRAINT ck_processing_run_reprocess CHECK (
        (trigger_type = 'AUTO' AND parent_run_id IS NULL)
        OR (trigger_type = 'MANUAL_REPROCESS' AND parent_run_id IS NOT NULL AND requested_by IS NOT NULL AND reason IS NOT NULL)
    )
);

CREATE INDEX ix_processing_run_source ON processing_run (source_document_id);
CREATE INDEX ix_processing_run_parent ON processing_run (parent_run_id);

-- Ein Run darf nur abgeschlossen (finished_at + result gesetzt), nie verändert werden.
CREATE TRIGGER trg_processing_run_no_update
BEFORE UPDATE ON processing_run
BEGIN
    SELECT RAISE(ABORT, 'processing_run ist unveraenderlich (nur einmaliger Abschluss erlaubt)')
    WHERE OLD.finished_at IS NOT NULL
       OR NEW.finished_at IS NULL
       OR NEW.result IS NULL
       OR NEW.id                  IS NOT OLD.id
       OR NEW.source_document_id  IS NOT OLD.source_document_id
       OR NEW.run_number          IS NOT OLD.run_number
       OR NEW.trigger_type        IS NOT OLD.trigger_type
       OR NEW.parent_run_id       IS NOT OLD.parent_run_id
       OR NEW.requested_by        IS NOT OLD.requested_by
       OR NEW.reason              IS NOT OLD.reason
       OR NEW.profile_name        IS NOT OLD.profile_name
       OR NEW.profile_hash        IS NOT OLD.profile_hash
       OR NEW.application_version IS NOT OLD.application_version
       OR NEW.correlation_id      IS NOT OLD.correlation_id
       OR NEW.started_at          IS NOT OLD.started_at;
END;

CREATE TRIGGER trg_processing_run_no_delete
BEFORE DELETE ON processing_run
BEGIN
    SELECT RAISE(ABORT, 'processing_run darf nicht geloescht werden');
END;

CREATE TRIGGER trg_source_document_no_update
BEFORE UPDATE ON source_document
BEGIN
    SELECT RAISE(ABORT, 'source_document ist unveraenderlich');
END;

CREATE TRIGGER trg_source_document_no_delete
BEFORE DELETE ON source_document
BEGIN
    SELECT RAISE(ABORT, 'source_document darf nicht geloescht werden');
END;

-- -------------------------------------------------------------------------------------
-- Rechnungsausgangsbuch (append-only). Genau ein Eintrag je Run, der eine Rechnung
-- fachlich erkannt hat. Spätere Zustände (Versand etc.) ausschließlich über processing_event.
-- -------------------------------------------------------------------------------------
CREATE TABLE ledger_entry (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    processing_run_id   INTEGER NOT NULL UNIQUE REFERENCES processing_run (id),
    tenant_id           TEXT    NOT NULL,
    document_type       TEXT    NOT NULL CHECK (document_type IN ('INVOICE', 'CREDIT_NOTE')),
    invoice_number      TEXT    NOT NULL,
    invoice_date        TEXT    NOT NULL,
    customer_name       TEXT    NOT NULL,
    currency            TEXT    NOT NULL,
    net_total           TEXT    NOT NULL,
    tax_total           TEXT    NOT NULL,
    gross_total         TEXT    NOT NULL,
    payable_amount      TEXT    NOT NULL,
    generated_formats   TEXT    NOT NULL,
    source_sha256       TEXT    NOT NULL CHECK (length(source_sha256) = 64),
    profile_name        TEXT    NOT NULL,
    profile_hash        TEXT    NOT NULL,
    application_version TEXT    NOT NULL,
    business_case       TEXT,
    recorded_at         TEXT    NOT NULL
);

CREATE INDEX ix_ledger_entry_invoice_number ON ledger_entry (invoice_number);
CREATE INDEX ix_ledger_entry_invoice_date   ON ledger_entry (invoice_date);
CREATE INDEX ix_ledger_entry_customer       ON ledger_entry (customer_name);
CREATE INDEX ix_ledger_entry_tenant         ON ledger_entry (tenant_id);

CREATE TRIGGER trg_ledger_entry_no_update
BEFORE UPDATE ON ledger_entry
BEGIN
    SELECT RAISE(ABORT, 'ledger_entry ist append-only');
END;

CREATE TRIGGER trg_ledger_entry_no_delete
BEFORE DELETE ON ledger_entry
BEGIN
    SELECT RAISE(ABORT, 'ledger_entry ist append-only');
END;

-- Netto und Steuer je Steuersatz/Steuerkategorie (BT-116, BT-117, BT-118, BT-119)
CREATE TABLE ledger_tax_line (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    ledger_entry_id   INTEGER NOT NULL REFERENCES ledger_entry (id),
    vat_category_code TEXT    NOT NULL,
    vat_rate          TEXT    NOT NULL,
    taxable_amount    TEXT    NOT NULL,
    tax_amount        TEXT    NOT NULL,
    CONSTRAINT uq_ledger_tax_line UNIQUE (ledger_entry_id, vat_category_code, vat_rate)
);

CREATE TRIGGER trg_ledger_tax_line_no_update
BEFORE UPDATE ON ledger_tax_line
BEGIN
    SELECT RAISE(ABORT, 'ledger_tax_line ist append-only');
END;

CREATE TRIGGER trg_ledger_tax_line_no_delete
BEFORE DELETE ON ledger_tax_line
BEGIN
    SELECT RAISE(ABORT, 'ledger_tax_line ist append-only');
END;

-- -------------------------------------------------------------------------------------
-- Append-only Events (Vorgabe Abschnitt 18)
-- -------------------------------------------------------------------------------------
CREATE TABLE processing_event (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    processing_run_id INTEGER NOT NULL REFERENCES processing_run (id),
    event_type        TEXT    NOT NULL CHECK (event_type IN (
                          'PROCESSING_STARTED', 'EXTRACTION_COMPLETED', 'PLAUSIBILITY_PASSED',
                          'REVIEW_REQUIRED', 'GENERATION_COMPLETED', 'VALIDATION_FAILED',
                          'VALIDATION_SUCCEEDED', 'ARCHIVED', 'DISPATCH_ATTEMPTED',
                          'DISPATCH_SUCCEEDED', 'DISPATCH_FAILED', 'REPROCESS_REQUESTED',
                          'REPROCESS_STARTED', 'REPROCESS_COMPLETED',
                          'REJECTED', 'DUPLICATE_DETECTED', 'PROCESSING_FAILED')),
    occurred_at       TEXT    NOT NULL,
    actor             TEXT    NOT NULL DEFAULT 'system',
    message           TEXT,
    details_json      TEXT
);

CREATE INDEX ix_processing_event_run ON processing_event (processing_run_id, id);

CREATE TRIGGER trg_processing_event_no_update
BEFORE UPDATE ON processing_event
BEGIN
    SELECT RAISE(ABORT, 'processing_event ist append-only');
END;

CREATE TRIGGER trg_processing_event_no_delete
BEFORE DELETE ON processing_event
BEGIN
    SELECT RAISE(ABORT, 'processing_event ist append-only');
END;

-- -------------------------------------------------------------------------------------
-- Artefakte je Run (Vorgabe Abschnitt 20). Alte Artefakte werden nie überschrieben.
-- -------------------------------------------------------------------------------------
CREATE TABLE artifact (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    processing_run_id INTEGER NOT NULL REFERENCES processing_run (id),
    artifact_type     TEXT    NOT NULL CHECK (artifact_type IN (
                          'SOURCE_PDF', 'XRECHNUNG_CII', 'XRECHNUNG_UBL', 'ZUGFERD_PDF',
                          'VALIDATION_XML', 'VALIDATION_HTML', 'EXTRACTION_LOG')),
    path              TEXT    NOT NULL,
    sha256            TEXT    NOT NULL CHECK (length(sha256) = 64),
    size_bytes        INTEGER NOT NULL CHECK (size_bytes >= 0),
    created_at        TEXT    NOT NULL,
    CONSTRAINT uq_artifact_path UNIQUE (path)
);

CREATE INDEX ix_artifact_run ON artifact (processing_run_id);

CREATE TRIGGER trg_artifact_no_update
BEFORE UPDATE ON artifact
BEGIN
    SELECT RAISE(ABORT, 'artifact ist append-only');
END;

CREATE TRIGGER trg_artifact_no_delete
BEFORE DELETE ON artifact
BEGIN
    SELECT RAISE(ABORT, 'artifact ist append-only');
END;

-- -------------------------------------------------------------------------------------
-- Validierungsergebnisse je Run, Validator und Zielformat (getrennt protokolliert, Abschnitt 11)
-- -------------------------------------------------------------------------------------
CREATE TABLE validation_result (
    id                     INTEGER PRIMARY KEY AUTOINCREMENT,
    processing_run_id      INTEGER NOT NULL REFERENCES processing_run (id),
    validated_artifact_id  INTEGER REFERENCES artifact (id),
    validator              TEXT    NOT NULL CHECK (validator IN ('KOSIT', 'MUSTANG')),
    target_format          TEXT    NOT NULL,
    outcome                TEXT    NOT NULL CHECK (outcome IN ('VALID', 'INVALID', 'NOT_APPLICABLE', 'ERROR')),
    ruleset                TEXT,
    error_count            INTEGER NOT NULL DEFAULT 0,
    warning_count          INTEGER NOT NULL DEFAULT 0,
    report_xml_artifact_id INTEGER REFERENCES artifact (id),
    report_html_artifact_id INTEGER REFERENCES artifact (id),
    validated_at           TEXT    NOT NULL
);

CREATE INDEX ix_validation_result_run ON validation_result (processing_run_id);

CREATE TRIGGER trg_validation_result_no_update
BEFORE UPDATE ON validation_result
BEGIN
    SELECT RAISE(ABORT, 'validation_result ist append-only');
END;

CREATE TRIGGER trg_validation_result_no_delete
BEFORE DELETE ON validation_result
BEGIN
    SELECT RAISE(ABORT, 'validation_result ist append-only');
END;

-- -------------------------------------------------------------------------------------
-- Optionales Prüfprotokoll der Eingangs-Validierung (Vorgabe Abschnitt 13 "Speicherung").
-- Getrennt von der Ausgangs-Pipeline; kein Bezug zu source_document/processing_run.
-- -------------------------------------------------------------------------------------
CREATE TABLE inbound_validation (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    sha256             TEXT    NOT NULL CHECK (length(sha256) = 64),
    original_filename  TEXT    NOT NULL,
    size_bytes         INTEGER NOT NULL CHECK (size_bytes >= 0),
    document_type      TEXT    NOT NULL,
    detected_profile   TEXT,
    detected_version   TEXT,
    ruleset            TEXT,
    overall_outcome    TEXT    NOT NULL CHECK (overall_outcome IN ('VALID', 'INVALID', 'ERROR')),
    kosit_outcome      TEXT    CHECK (kosit_outcome IS NULL OR kosit_outcome IN ('VALID', 'INVALID', 'NOT_APPLICABLE', 'ERROR')),
    mustang_outcome    TEXT    CHECK (mustang_outcome IS NULL OR mustang_outcome IN ('VALID', 'INVALID', 'NOT_APPLICABLE', 'ERROR')),
    error_count        INTEGER NOT NULL DEFAULT 0,
    warning_count      INTEGER NOT NULL DEFAULT 0,
    stored_directory   TEXT,
    validated_at       TEXT    NOT NULL,
    validated_by       TEXT
);

CREATE INDEX ix_inbound_validation_sha ON inbound_validation (sha256);

CREATE TRIGGER trg_inbound_validation_no_update
BEFORE UPDATE ON inbound_validation
BEGIN
    SELECT RAISE(ABORT, 'inbound_validation ist append-only');
END;

CREATE TRIGGER trg_inbound_validation_no_delete
BEFORE DELETE ON inbound_validation
BEGIN
    SELECT RAISE(ABORT, 'inbound_validation ist append-only');
END;
