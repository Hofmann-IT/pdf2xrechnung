package de.hofmannit.erechnung.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Sha256Test {

    /** Bekannter Testvektor: SHA-256("abc"). */
    private static final String SHA_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    void knownVectorForString() {
        assertThat(Sha256.ofString("abc")).isEqualTo(SHA_ABC);
    }

    @Test
    void fileAndStreamHashingMatchByteHashing(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("abc.txt");
        Files.writeString(file, "abc", StandardCharsets.UTF_8);
        assertThat(Sha256.ofFile(file)).isEqualTo(SHA_ABC);
        assertThat(Sha256.ofBytes("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(SHA_ABC);
    }

    @Test
    void emptyInputHasWellKnownHash() {
        assertThat(Sha256.ofBytes(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }
}
