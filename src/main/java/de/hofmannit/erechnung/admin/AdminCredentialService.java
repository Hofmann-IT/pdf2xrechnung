package de.hofmannit.erechnung.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import de.hofmannit.erechnung.admin.AdminSettingsService.SettingsException;
import de.hofmannit.erechnung.configuration.AppProperties;
import de.hofmannit.erechnung.ledger.AdminCredentialRepository;
import de.hofmannit.erechnung.ledger.AdminCredentialRepository.CredentialRow;

import org.springframework.stereotype.Service;

/**
 * Anmeldung für den Verwaltungsbereich (ADR 0013). Gültig ist entweder das im Assistenten oder
 * in der Verwaltung vergebene Passwort (PBKDF2-SHA256-Hash in {@code admin_credential}) oder,
 * als Überschreiber für Betreiber, {@code ADMIN_PASSWORD} aus der Umgebung. Vergleiche laufen
 * in konstanter Zeit; Passwörter werden nie protokolliert oder gespeichert.
 */
@Service
public class AdminCredentialService {

    static final int ITERATIONS = 120_000;
    static final int KEY_BITS = 256;
    static final int MIN_LENGTH = 10;

    private final AdminCredentialRepository repository;
    private final AppProperties.Admin env;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public AdminCredentialService(AdminCredentialRepository repository, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.env = properties.admin();
        this.clock = clock;
    }

    /** Anmeldung möglich (Passwort vergeben oder ADMIN_PASSWORD gesetzt). */
    public boolean configured() {
        return envConfigured() || repository.latest().isPresent();
    }

    public boolean envConfigured() {
        return env != null && env.configured();
    }

    public boolean storedConfigured() {
        return repository.latest().isPresent();
    }

    /** Benutzername für die Anmeldung (gespeichert, sonst aus der Konfiguration). */
    public String username() {
        return repository.latest().map(CredentialRow::username).orElse(env == null || env.username() == null ? "admin" : env.username());
    }

    public boolean verify(String username, String password) {
        if (username == null || password == null) {
            return false;
        }
        boolean ok = false;
        if (envConfigured()) {
            ok |= constantTimeEquals(username, env.username()) & constantTimeEquals(password, env.password());
        }
        Optional<CredentialRow> stored = repository.latest();
        if (stored.isPresent()) {
            ok |= constantTimeEquals(username, stored.get().username()) & verifyHash(password, stored.get().passwordHash());
        }
        return ok;
    }

    /** Vergibt ein neues Passwort (neuer Datensatz; Historie bleibt). */
    public void set(String username, String password, String passwordRepeat, String actor) throws SettingsException {
        String user = username == null || username.isBlank() ? "admin" : username.trim();
        if (!user.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new SettingsException(List.of("Benutzername: nur Buchstaben, Ziffern, Punkt, Unterstrich, Bindestrich (1–64 Zeichen)"));
        }
        if (password == null || password.length() < MIN_LENGTH) {
            throw new SettingsException(List.of("Passwort muss mindestens " + MIN_LENGTH + " Zeichen haben"));
        }
        if (!password.equals(passwordRepeat)) {
            throw new SettingsException(List.of("Passwort und Wiederholung stimmen nicht überein"));
        }
        repository.save(clock.instant(), actor == null || actor.isBlank() ? user : actor.trim(), user, hash(password));
    }

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
