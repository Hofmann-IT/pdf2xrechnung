-- =====================================================================================
-- V6: Benutzerkonten für die Anmeldung an allen Funktionen (ADR 0015)
--
--   user_account: Benutzer mit Rolle (ADMIN = alles, USER = Sachbearbeitung ohne Verwaltung).
--   Gespeichert wird ausschließlich ein salted PBKDF2-Hash (Format wie V5), nie das Passwort.
--   Append-only: Jede Änderung (Passwort, Rolle, aktiv/inaktiv) ist ein neuer Datensatz;
--   je Benutzername (ohne Beachtung der Groß-/Kleinschreibung) gilt der jüngste.
--
--   Das in V5 vergebene Verwaltungskonto wird als erster Administrator übernommen.
--   admin_credential bleibt als Historie erhalten und wird nicht mehr beschrieben.
-- =====================================================================================

CREATE TABLE user_account (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT    NOT NULL,
    created_by    TEXT    NOT NULL,
    username      TEXT    NOT NULL COLLATE NOCASE CHECK (length(username) BETWEEN 1 AND 64),
    role          TEXT    NOT NULL CHECK (role IN ('ADMIN', 'USER')),
    password_hash TEXT    NOT NULL CHECK (password_hash LIKE 'pbkdf2-sha256$%'),
    active        INTEGER NOT NULL CHECK (active IN (0, 1))
);

CREATE INDEX idx_user_account_username ON user_account (username, id);

CREATE TRIGGER trg_user_account_no_update
BEFORE UPDATE ON user_account
BEGIN
    SELECT RAISE(ABORT, 'user_account ist append-only');
END;

CREATE TRIGGER trg_user_account_no_delete
BEFORE DELETE ON user_account
BEGIN
    SELECT RAISE(ABORT, 'user_account ist append-only');
END;

INSERT INTO user_account (created_at, created_by, username, role, password_hash, active)
SELECT created_at, 'migration-v6', username, 'ADMIN', password_hash, 1
FROM admin_credential
ORDER BY id DESC
LIMIT 1;
