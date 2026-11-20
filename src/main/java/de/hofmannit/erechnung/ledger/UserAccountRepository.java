package de.hofmannit.erechnung.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Zugriff auf {@code user_account} (V6, append-only). Je Benutzername gilt der jüngste
 * Datensatz; der Vergleich des Benutzernamens ignoriert Groß-/Kleinschreibung (Spalte NOCASE).
 */
@Repository
public class UserAccountRepository {

    /** Stand eines Benutzerkontos: Rolle, Passwort-Hash und Aktiv-Kennzeichen. */
    public record UserAccountRow(long id, Instant createdAt, String createdBy, String username, String role, String passwordHash,
                                 boolean active) {
    }

    private static final RowMapper<UserAccountRow> ROW = (rs, i) -> new UserAccountRow(rs.getLong("id"),
            Instant.parse(rs.getString("created_at")), rs.getString("created_by"), rs.getString("username"), rs.getString("role"),
            rs.getString("password_hash"), rs.getInt("active") == 1);

    private final JdbcTemplate jdbc;

    public UserAccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(Instant createdAt, String createdBy, String username, String role, String passwordHash, boolean active) {
        jdbc.update("INSERT INTO user_account (created_at, created_by, username, role, password_hash, active) VALUES (?,?,?,?,?,?)",
                createdAt.toString(), createdBy, username, role, passwordHash, active ? 1 : 0);
    }

    /** Aktueller Stand eines Kontos. */
    public Optional<UserAccountRow> latest(String username) {
        return jdbc.query("SELECT * FROM user_account WHERE username = ? ORDER BY id DESC LIMIT 1", ROW, username).stream().findFirst();
    }

    /** Aktueller Stand aller Konten, nach Benutzername sortiert. */
    public List<UserAccountRow> current() {
        return jdbc.query("SELECT * FROM user_account WHERE id IN (SELECT MAX(id) FROM user_account GROUP BY username) ORDER BY username",
                ROW);
    }
}
