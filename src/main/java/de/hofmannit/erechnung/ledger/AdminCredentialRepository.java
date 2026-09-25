package de.hofmannit.erechnung.ledger;

import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Zugriff auf {@code admin_credential} (V5, append-only; der jüngste Datensatz gilt). */
@Repository
public class AdminCredentialRepository {

    /** Gespeicherte Anmeldung: nur Benutzername und Passwort-Hash. */
    public record CredentialRow(long id, Instant createdAt, String createdBy, String username, String passwordHash) {
    }

    private static final RowMapper<CredentialRow> ROW = (rs, i) -> new CredentialRow(rs.getLong("id"),
            Instant.parse(rs.getString("created_at")), rs.getString("created_by"), rs.getString("username"), rs.getString("password_hash"));

    private final JdbcTemplate jdbc;

    public AdminCredentialRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Instant createdAt, String createdBy, String username, String passwordHash) {
        jdbc.update("INSERT INTO admin_credential (created_at, created_by, username, password_hash) VALUES (?,?,?,?)",
                createdAt.toString(), createdBy, username, passwordHash);
    }

    public Optional<CredentialRow> latest() {
        return jdbc.query("SELECT * FROM admin_credential ORDER BY id DESC LIMIT 1", ROW).stream().findFirst();
    }
}
