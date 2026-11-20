package de.hofmannit.erechnung.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.admin.UserAccountService.Account;
import de.hofmannit.erechnung.admin.UserAccountService.Role;
import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.ledger.UserAccountRepository;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * Benutzerkonten (ADR 0015) gegen eine echte SQLite-Datenbank mit den Flyway-Migrationen:
 * Übernahme des Verwaltungskontos aus V5, Anmeldung, Sperre nach Fehlversuchen, Schutz des
 * letzten Administrators, Append-only-Historie.
 */
class UserAccountServiceTest {

    private static final String V5_PASSWORD = "altes-passwort-1";

    /** Uhr, die der Test weiterstellen kann. */
    static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-20T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final TestClock clock = new TestClock();
    private JdbcTemplate jdbc;
    private UserAccountService service;

    @BeforeEach
    void setUp() throws Exception {
        Path base = Files.createDirectories(Path.of("target", "test-data"));
        Path db = Files.createTempDirectory(base, "accounts-").resolve("erechnung.db");
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + db.toAbsolutePath());
        jdbc = new JdbcTemplate(dataSource);

        // Stand einer Installation 1.0.0 (V5) mit im Assistenten vergebenem Verwaltungskonto …
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("5").load().migrate();
        String hash = service(null).hash(V5_PASSWORD);
        jdbc.update("INSERT INTO admin_credential (created_at, created_by, username, password_hash) VALUES (?,?,?,?)",
                "2026-09-25T10:00:00Z", "Anna", "Chef", "pbkdf2-sha256$1$AAAA$AAAA");
        jdbc.update("INSERT INTO admin_credential (created_at, created_by, username, password_hash) VALUES (?,?,?,?)",
                "2026-10-01T10:00:00Z", "Anna", "Chef", hash);
        // … wird auf V6 gehoben.
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        service = service(null);
    }

    private UserAccountService service(String envPassword) {
        AppProperties props = new AppProperties(null, null, null, null, null, null, null, new AppProperties.Admin("betreiber", envPassword), null, null);
        return new UserAccountService(new UserAccountRepository(jdbc), props, clock);
    }

    @Test
    void migrationCarriesTheLatestAdminCredentialOverAsAdministrator() {
        assertThat(service.accounts()).singleElement().satisfies(a -> {
            assertThat(a.username()).isEqualTo("Chef");
            assertThat(a.role()).isEqualTo(Role.ADMIN);
            assertThat(a.active()).isTrue();
            assertThat(a.changedBy()).isEqualTo("migration-v6");
        });
        assertThat(service.adminAvailable()).isTrue();
        // Anmeldung mit dem bisherigen Passwort; Benutzername ohne Beachtung der Groß-/Kleinschreibung
        assertThat(service.login("chef", V5_PASSWORD).account()).map(Account::username).contains("Chef");
        assertThat(service.login("Chef", "falsch").account()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM admin_credential", Integer.class)).as("V5-Historie bleibt erhalten").isEqualTo(2);
    }

    @Test
    void accountsAreValidatedAndEveryChangeIsANewRow() throws Exception {
        assertThatThrownBy(() -> service.create("mit leerzeichen", Role.USER, "langes-passwort", "langes-passwort", "Chef")).isInstanceOf(SettingsException.class);
        assertThatThrownBy(() -> service.create("erika", Role.USER, "kurz", "kurz", "Chef")).isInstanceOf(SettingsException.class);
        assertThatThrownBy(() -> service.create("erika", Role.USER, "langes-passwort", "anderes-passwort", "Chef")).isInstanceOf(SettingsException.class);
        assertThatThrownBy(() -> service.create("CHEF", Role.USER, "langes-passwort", "langes-passwort", "Chef")).isInstanceOf(SettingsException.class);
        assertThat(service.accounts()).hasSize(1);

        service.create("erika", Role.USER, "erikas-passwort-1", "erikas-passwort-1", "Chef");
        assertThat(service.login("erika", "erikas-passwort-1").account()).map(Account::role).contains(Role.USER);

        service.changeRole("erika", Role.ADMIN, "Chef");
        service.changePassword("erika", "erikas-passwort-2", "erikas-passwort-2", "Chef");
        assertThat(service.login("erika", "erikas-passwort-1").account()).isEmpty();
        assertThat(service.login("erika", "erikas-passwort-2").account()).map(Account::role).contains(Role.ADMIN);

        assertThatThrownBy(() -> service.changeOwnPassword("erika", "falsch", "erikas-passwort-3", "erikas-passwort-3")).isInstanceOf(SettingsException.class);
        service.changeOwnPassword("erika", "erikas-passwort-2", "erikas-passwort-3", "erikas-passwort-3");
        assertThat(service.login("erika", "erikas-passwort-3").account()).isPresent();

        service.setActive("erika", false, "Chef");
        assertThat(service.login("erika", "erikas-passwort-3").account()).as("gesperrtes Konto").isEmpty();
        assertThat(service.find("erika")).map(Account::active).contains(false);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE username = 'erika'", Integer.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_account WHERE password_hash NOT LIKE 'pbkdf2-sha256$120000$%'", Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("UPDATE user_account SET active = 1")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM user_account")).hasMessageContaining("append-only");
    }

    @Test
    void theLastActiveAdministratorCannotBeLockedOrDemoted() throws Exception {
        assertThatThrownBy(() -> service.setActive("Chef", false, "Chef")).isInstanceOf(SettingsException.class);
        assertThatThrownBy(() -> service.changeRole("Chef", Role.USER, "Chef")).isInstanceOf(SettingsException.class);

        service.create("zweiter", Role.ADMIN, "zweites-passwort", "zweites-passwort", "Chef");
        service.changeRole("Chef", Role.USER, "zweiter");
        assertThat(service.find("Chef")).map(Account::role).contains(Role.USER);
        assertThatThrownBy(() -> service.setActive("zweiter", false, "zweiter")).isInstanceOf(SettingsException.class);
        assertThat(service.adminAvailable()).isTrue();
    }

    @Test
    void fiveFailuresLockTheUsernameForFiveMinutes() {
        for (int i = 0; i < 4; i++) {
            assertThat(service.login("Chef", "falsch-" + i).locked()).isFalse();
        }
        assertThat(service.login("chef", "falsch-5").locked()).as("fünfter Fehlversuch sperrt").isTrue();
        assertThat(service.login("Chef", V5_PASSWORD)).as("auch das richtige Passwort wird abgewiesen")
                .satisfies(r -> assertThat(r.locked()).isTrue()).satisfies(r -> assertThat(r.account()).isEmpty());

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertThat(service.login("Chef", V5_PASSWORD).locked()).isTrue();
        clock.advance(Duration.ofSeconds(1));
        assertThat(service.login("Chef", V5_PASSWORD).account()).isPresent();

        // Ein Erfolg setzt den Zähler zurück; unbekannte Benutzer werden genauso behandelt
        for (int i = 0; i < 4; i++) {
            assertThat(service.login("Chef", "falsch").locked()).isFalse();
        }
        assertThat(service.login("Chef", V5_PASSWORD).account()).isPresent();
        assertThat(service.login("Chef", "falsch").locked()).isFalse();
        for (int i = 0; i < 4; i++) {
            assertThat(service.login("gibt-es-nicht", "x")).satisfies(r -> assertThat(r.account()).isEmpty());
        }
        assertThat(service.login("gibt-es-nicht", "x").locked()).isTrue();
    }

    @Test
    void operatorAccessFromTheEnvironmentIsAnAdministrator() throws Exception {
        assertThat(service.envConfigured()).isFalse();
        assertThat(service.login("betreiber", "egal").account()).isEmpty();

        UserAccountService withEnv = service("betreiber-geheim");
        assertThat(withEnv.envConfigured()).isTrue();
        assertThat(withEnv.login("betreiber", "betreiber-geheim").account()).hasValueSatisfying(a -> {
            assertThat(a.role()).isEqualTo(Role.ADMIN);
            assertThat(a.fromEnvironment()).isTrue();
        });
        assertThat(withEnv.login("betreiber", "falsch").account()).isEmpty();
        assertThat(withEnv.find("Betreiber")).map(Account::fromEnvironment).contains(true);
        assertThatThrownBy(() -> withEnv.create("betreiber", Role.USER, "langes-passwort", "langes-passwort", "Chef")).isInstanceOf(SettingsException.class);
        assertThat(withEnv.accounts()).as("Betreiber-Zugang ist kein gespeichertes Konto").extracting(Account::username).containsExactly("Chef");
    }
}
