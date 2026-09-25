package de.hofmannit.erechnung.ledger;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Zugriff auf {@code app_settings} (V4, append-only; der jüngste Datensatz gilt). */
@Repository
public class AppSettingsRepository {

    /** Gespeicherter Gesamtstand als JSON. */
    public record AppSettingsRow(long id, Instant createdAt, String createdBy, String note, String settingsJson) {
    }

    private static final RowMapper<AppSettingsRow> ROW = (rs, i) -> new AppSettingsRow(rs.getLong("id"),
            Instant.parse(rs.getString("created_at")), rs.getString("created_by"), rs.getString("note"), rs.getString("settings_json"));

    private final JdbcTemplate jdbc;

    public AppSettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AppSettingsRow save(Instant createdAt, String createdBy, String note, String settingsJson) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO app_settings (created_at, created_by, note, settings_json) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, createdAt.toString());
            ps.setString(2, createdBy);
            ps.setString(3, note);
            ps.setString(4, settingsJson);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return new AppSettingsRow(key == null ? 0 : key.longValue(), createdAt, createdBy, note, settingsJson);
    }

    public Optional<AppSettingsRow> latest() {
        return jdbc.query("SELECT * FROM app_settings ORDER BY id DESC LIMIT 1", ROW).stream().findFirst();
    }

    public List<AppSettingsRow> history(int limit) {
        return jdbc.query("SELECT * FROM app_settings ORDER BY id DESC LIMIT ?", ROW, limit);
    }
}
