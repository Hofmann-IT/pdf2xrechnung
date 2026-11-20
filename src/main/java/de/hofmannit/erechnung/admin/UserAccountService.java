package de.hofmannit.erechnung.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.ledger.UserAccountRepository;
import de.hofmannit.erechnung.ledger.UserAccountRepository.UserAccountRow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Benutzerkonten und Anmeldung für alle Funktionen (ADR 0015, löst die reine
 * Verwaltungsanmeldung aus ADR 0013 ab).
 *
 * <p>Konten liegen in {@code user_account} (append-only, PBKDF2-SHA256-Hash). Zusätzlich gilt,
 * als Zugang für Betreiber, {@code ADMIN_PASSWORD} aus der Umgebung mit Administratorrechten.
 * Nach {@value #MAX_FAILURES} Fehlversuchen ist ein Benutzername für
 * {@value #LOCK_MINUTES} Minuten gesperrt. Vergleiche laufen in konstanter Zeit; Passwörter
 * werden nie protokolliert oder gespeichert.
 */
@Service
public class UserAccountService {

    private static final Logger log = LoggerFactory.getLogger(UserAccountService.class);

    static final int ITERATIONS = 120_000;
    static final int KEY_BITS = 256;
    public static final int MIN_LENGTH = 10;
    static final int MAX_FAILURES = 5;
    static final int LOCK_MINUTES = 5;

    /** Rollen: Administrator (alles) und Sachbearbeiter (alles außer Verwaltung). */
    public enum Role {
        ADMIN("Administrator"), USER("Sachbearbeiter");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Aktueller Stand eines Kontos (ohne Passwort-Hash). */
    public record Account(String username, Role role, boolean active, Instant changedAt, String changedBy, boolean fromEnvironment) {
    }

    /** Ergebnis einer Anmeldung: Konto bei Erfolg; {@code locked}, wenn der Benutzername gesperrt ist. */
    public record LoginResult(Optional<Account> account, boolean locked) {
    }

    private record Failures(int count, Instant lockedUntil) {
    }

    private final UserAccountRepository repository;
    private final AppProperties.Admin env;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();
    /** Für die Prüfung unbekannter Benutzer: gleicher Rechenaufwand wie bei bekannten. */
    private final String dummyHash;

    public UserAccountService(UserAccountRepository repository, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.env = properties.admin();
        this.clock = clock;
        this.dummyHash = hash("unbekannter-benutzer-" + random.nextLong());
    }

    // ---- Abfragen ---------------------------------------------------------------------------

    /** Mindestens ein Administrator kann sich anmelden (Konto oder ADMIN_PASSWORD). */
    public boolean adminAvailable() {
        return envConfigured() || repository.current().stream().anyMatch(r -> r.active() && Role.ADMIN.name().equals(r.role()));
    }

    public boolean envConfigured() {
        return env != null && env.configured();
    }

    public String envUsername() {
        return envConfigured() ? env.username() : null;
    }

    public List<Account> accounts() {
        return repository.current().stream().map(UserAccountService::toAccount).toList();
    }

    /** Aktueller Stand eines Kontos; der Betreiber-Zugang aus der Umgebung gilt als aktiver Administrator. */
    public Optional<Account> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        if (envConfigured() && username.equalsIgnoreCase(env.username())) {
            return Optional.of(new Account(env.username(), Role.ADMIN, true, null, null, true));
        }
        return repository.latest(username).map(UserAccountService::toAccount);
    }

    // ---- Anmeldung --------------------------------------------------------------------------

    public LoginResult login(String username, String password) {
        if (username == null || password == null || username.isBlank()) {
            return new LoginResult(Optional.empty(), false);
        }
        String key = username.trim().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        Failures state = failures.get(key);
        if (state != null && state.lockedUntil() != null && now.isBefore(state.lockedUntil())) {
            return new LoginResult(Optional.empty(), true);
        }
        Optional<Account> account = verify(username.trim(), password);
        if (account.isPresent()) {
            failures.remove(key);
            return new LoginResult(account, false);
        }
        int count = (state == null || state.lockedUntil() != null ? 0 : state.count()) + 1;
        if (count >= MAX_FAILURES) {
            failures.put(key, new Failures(count, now.plus(Duration.ofMinutes(LOCK_MINUTES))));
            log.warn("Anmeldung für Benutzer '{}' nach {} Fehlversuchen für {} Minuten gesperrt", key, count, LOCK_MINUTES);
            return new LoginResult(Optional.empty(), true);
        }
        failures.put(key, new Failures(count, null));
        return new LoginResult(Optional.empty(), false);
    }

    private Optional<Account> verify(String username, String password) {
        Account result = null;
        if (envConfigured() && constantTimeEquals(username, env.username()) & constantTimeEquals(password, env.password())) {
            result = new Account(env.username(), Role.ADMIN, true, null, null, true);
        }
        Optional<UserAccountRow> stored = repository.latest(username);
        boolean hashOk = verifyHash(password, stored.map(UserAccountRow::passwordHash).orElse(dummyHash));
        if (result == null && stored.isPresent() && stored.get().active() && hashOk) {
            result = toAccount(stored.get());
        }
        return Optional.ofNullable(result);
    }

    // ---- Pflege -----------------------------------------------------------------------------

    /** Legt ein neues Konto an. */
    public void create(String username, Role role, String password, String passwordRepeat, String actor) throws SettingsException {
        String user = validUsername(username);
        if (repository.latest(user).isPresent()) {
            throw new SettingsException(List.of("Benutzer '" + user + "' existiert bereits"));
        }
        if (envConfigured() && user.equalsIgnoreCase(env.username())) {
            throw new SettingsException(List.of("Benutzername '" + user + "' ist für den Betreiber-Zugang (ADMIN_PASSWORD) vergeben"));
        }
        validPassword(password, passwordRepeat);
        repository.save(clock.instant(), actor(actor, user), user, required(role).name(), hash(password), true);
        log.info("Benutzer '{}' ({}) angelegt durch {}", user, role, actor(actor, user));
    }

    /**
     * Vergibt das Administratorkonto der Einrichtung: legt es an oder setzt bei vorhandenem
     * Konto Passwort, Rolle ADMIN und aktiv.
     */
    public void setAdministrator(String username, String password, String passwordRepeat, String actor) throws SettingsException {
        String user = validUsername(username == null || username.isBlank() ? "admin" : username);
        validPassword(password, passwordRepeat);
        String name = repository.latest(user).map(UserAccountRow::username).orElse(user);
        repository.save(clock.instant(), actor(actor, user), name, Role.ADMIN.name(), hash(password), true);
    }

    public void changePassword(String username, String password, String passwordRepeat, String actor) throws SettingsException {
        UserAccountRow row = existing(username);
        validPassword(password, passwordRepeat);
        repository.save(clock.instant(), actor(actor, row.username()), row.username(), row.role(), hash(password), row.active());
        failures.remove(row.username().toLowerCase(Locale.ROOT));
        log.info("Passwort für Benutzer '{}' geändert durch {}", row.username(), actor(actor, row.username()));
    }

    /** Eigenes Passwort ändern: verlangt das bisherige Passwort. */
    public void changeOwnPassword(String username, String currentPassword, String password, String passwordRepeat) throws SettingsException {
        UserAccountRow row = existing(username);
        if (currentPassword == null || !verifyHash(currentPassword, row.passwordHash())) {
            throw new SettingsException(List.of("Das bisherige Passwort ist nicht korrekt"));
        }
        changePassword(row.username(), password, passwordRepeat, row.username());
    }

    public void changeRole(String username, Role role, String actor) throws SettingsException {
        UserAccountRow row = existing(username);
        if (required(role) != Role.ADMIN) {
            guardLastAdministrator(row);
        }
        repository.save(clock.instant(), actor(actor, row.username()), row.username(), role.name(), row.passwordHash(), row.active());
        log.info("Rolle von Benutzer '{}' auf {} gesetzt durch {}", row.username(), role, actor(actor, row.username()));
    }

    public void setActive(String username, boolean active, String actor) throws SettingsException {
        UserAccountRow row = existing(username);
        if (!active) {
            guardLastAdministrator(row);
        }
        repository.save(clock.instant(), actor(actor, row.username()), row.username(), row.role(), row.passwordHash(), active);
        log.info("Benutzer '{}' {} durch {}", row.username(), active ? "aktiviert" : "deaktiviert", actor(actor, row.username()));
    }

    private void guardLastAdministrator(UserAccountRow row) throws SettingsException {
        if (!row.active() || !Role.ADMIN.name().equals(row.role())) {
            return;
        }
        long others = repository.current().stream()
                .filter(r -> r.active() && Role.ADMIN.name().equals(r.role()) && !r.username().equalsIgnoreCase(row.username())).count();
        if (others == 0) {
            throw new SettingsException(List.of("'" + row.username() + "' ist der letzte aktive Administrator und kann nicht deaktiviert oder herabgestuft werden"));
        }
    }

    private UserAccountRow existing(String username) throws SettingsException {
        return repository.latest(username == null ? "" : username.trim())
                .orElseThrow(() -> new SettingsException(List.of("Benutzer '" + username + "' existiert nicht")));
    }

    private static Role required(Role role) throws SettingsException {
        if (role == null) {
            throw new SettingsException(List.of("Rolle fehlt"));
        }
        return role;
    }

    public static String validUsername(String username) throws SettingsException {
        String user = username == null ? "" : username.trim();
        if (!user.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new SettingsException(List.of("Benutzername: nur Buchstaben, Ziffern, Punkt, Unterstrich, Bindestrich (1–64 Zeichen)"));
        }
        return user;
    }

    /** Prüft die Passwortregeln, ohne zu speichern. */
    public static void validPassword(String password, String passwordRepeat) throws SettingsException {
        if (password == null || password.length() < MIN_LENGTH) {
            throw new SettingsException(List.of("Passwort muss mindestens " + MIN_LENGTH + " Zeichen haben"));
        }
        if (!password.equals(passwordRepeat)) {
            throw new SettingsException(List.of("Passwort und Wiederholung stimmen nicht überein"));
        }
    }

    private static String actor(String actor, String fallback) {
        return actor == null || actor.isBlank() ? fallback : actor.trim();
    }

    private static Account toAccount(UserAccountRow row) {
        return new Account(row.username(), Role.valueOf(row.role()), row.active(), row.createdAt(), row.createdBy(), false);
    }

    // ---- Hash -------------------------------------------------------------------------------

    String hash(String password) {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return "pbkdf2-sha256$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derived);
    }

    static boolean verifyHash(String password, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals("pbkdf2-sha256")) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(derive(password, salt, iterations), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("PBKDF2 nicht verfügbar", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
