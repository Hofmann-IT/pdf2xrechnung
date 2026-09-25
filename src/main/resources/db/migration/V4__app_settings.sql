-- =====================================================================================
-- V4: Verwaltungsbereich (ADR 0012)
--
--   app_settings: in der Oberfläche gespeicherte Laufzeiteinstellungen (Verzeichnisse,
--   Watcher, Parallelität, SMTP-Server ohne Zugangsdaten, Eingangs-Validierung) als JSON-
--   Gesamtstand je Datensatz. Append-only; der jüngste Datensatz gilt und wird beim Start
--   sowie bei jeder Speicherung ohne Neustart übernommen. Zugangsdaten stehen nie darin.
-- =====================================================================================

CREATE TABLE app_settings (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT    NOT NULL,
    created_by    TEXT    NOT NULL,
    note          TEXT,
    settings_json TEXT    NOT NULL CHECK (json_valid(settings_json))
);

CREATE TRIGGER trg_app_settings_no_update
BEFORE UPDATE ON app_settings
BEGIN
    SELECT RAISE(ABORT, 'app_settings ist append-only');
END;

CREATE TRIGGER trg_app_settings_no_delete
BEFORE DELETE ON app_settings
BEGIN
    SELECT RAISE(ABORT, 'app_settings ist append-only');
END;
