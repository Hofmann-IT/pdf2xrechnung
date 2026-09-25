-- =====================================================================================
-- V5: Einrichtungs-Assistent (ADR 0013)
--
--   admin_credential: Anmeldung für den Verwaltungsbereich, im Assistenten oder in der
--   Verwaltung vergeben. Gespeichert wird ausschließlich ein salted PBKDF2-Hash
--   (Format pbkdf2-sha256$<Iterationen>$<Salt Base64>$<Hash Base64>), nie das Passwort.
--   Append-only; der jüngste Datensatz gilt. ADMIN_PASSWORD bleibt als Überschreiber für
--   Betreiber möglich.
-- =====================================================================================

CREATE TABLE admin_credential (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at    TEXT    NOT NULL,
    created_by    TEXT    NOT NULL,
    username      TEXT    NOT NULL CHECK (length(username) BETWEEN 1 AND 64),
    password_hash TEXT    NOT NULL CHECK (password_hash LIKE 'pbkdf2-sha256$%')
);

CREATE TRIGGER trg_admin_credential_no_update
BEFORE UPDATE ON admin_credential
BEGIN
    SELECT RAISE(ABORT, 'admin_credential ist append-only');
END;

CREATE TRIGGER trg_admin_credential_no_delete
BEFORE DELETE ON admin_credential
BEGIN
    SELECT RAISE(ABORT, 'admin_credential ist append-only');
END;
