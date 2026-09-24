package de.hofmannit.erechnung.configuration.profile;

import java.nio.file.Path;

/**
 * Ein geladenes Profil inklusive Herkunft und Hash.
 *
 * @param name        Profilname (aus {@code profile.name}; muss dem Dateinamen ohne Endung entsprechen)
 * @param path        Quelldatei
 * @param sha256      SHA-256 der Rohdatei; wird je Run im Ledger gespeichert ({@code profile_hash})
 * @param definition  das gebundene Profil
 */
public record LoadedProfile(String name, Path path, String sha256, ProfileDefinition definition) {
}
