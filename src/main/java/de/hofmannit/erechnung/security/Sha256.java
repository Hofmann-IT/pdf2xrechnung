package de.hofmannit.erechnung.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256-Hashing für Quelldokumente, Artefakte und Profile. Ergebnis immer als
 * 64-stelliger Hex-String in Kleinbuchstaben.
 */
public final class Sha256 {

    private static final HexFormat HEX = HexFormat.of();

    private Sha256() {
    }

    public static String ofBytes(byte[] data) {
        return HEX.formatHex(digest().digest(data));
    }

    public static String ofString(String text) {
        return ofBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String ofFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ofStream(in);
        }
    }

    public static String ofStream(InputStream in) throws IOException {
        MessageDigest md = digest();
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            md.update(buffer, 0, read);
        }
        return HEX.formatHex(md.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }
}
