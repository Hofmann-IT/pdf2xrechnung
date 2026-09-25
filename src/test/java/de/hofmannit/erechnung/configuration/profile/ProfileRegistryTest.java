package de.hofmannit.erechnung.configuration.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import de.hofmannit.erechnung.configuration.TenantProperties;
import de.hofmannit.erechnung.configuration.TenantProperties.Tenant;

import org.junit.jupiter.api.Test;

class ProfileRegistryTest {

    private static final Map<String, LoadedProfile> PROFILES = Map.of(
            "standard", new LoadedProfile("standard", Path.of("profiles/standard.yaml"), "0".repeat(64), null));

    @Test
    void validTenantConfigurationPasses() {
        TenantProperties props = new TenantProperties(List.of(
                new Tenant("a", "A", true, "", List.of("standard"), Map.of(), null),
                new Tenant("b", "B", true, "b", List.of("standard"), Map.of(), null)));
        assertThatCode(() -> ProfileRegistry.validateTenants(props, PROFILES)).doesNotThrowAnyException();
    }

    @Test
    void noTenantsMeansSetupModeNotAnError() {
        // ADR 0013: ohne Mandanten startet die Anwendung im Einrichtungsmodus
        assertThat(ProfileRegistry.validateTenants(new TenantProperties(List.of()), PROFILES)).isEmpty();
        assertThat(ProfileRegistry.validateTenants(null, PROFILES)).isEmpty();
    }

    @Test
    void unknownProfileIsRejected() {
        TenantProperties props = new TenantProperties(List.of(
                new Tenant("a", "A", true, "", List.of("gibt-es-nicht"), Map.of(), null)));
        assertThatThrownBy(() -> ProfileRegistry.validateTenants(props, PROFILES))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("gibt-es-nicht");
    }

    @Test
    void duplicateIdsAndSharedInboxAreRejected() {
        TenantProperties props = new TenantProperties(List.of(
                new Tenant("a", "A", true, "", List.of("standard"), Map.of(), null),
                new Tenant("a", "A2", true, "", List.of("standard"), Map.of(), null)));
        assertThatThrownBy(() -> ProfileRegistry.validateTenants(props, PROFILES))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("doppelt")
                .hasMessageContaining("bereits von einem anderen aktiven Mandanten");
    }

    @Test
    void inboxSubdirectoryMustBeRelative() {
        TenantProperties props = new TenantProperties(List.of(
                new Tenant("a", "A", true, "../other", List.of("standard"), Map.of(), null)));
        assertThatThrownBy(() -> ProfileRegistry.validateTenants(props, PROFILES))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("relatives Unterverzeichnis");
    }
}
