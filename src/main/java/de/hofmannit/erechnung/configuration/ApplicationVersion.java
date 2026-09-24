package de.hofmannit.erechnung.configuration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Anwendungsversion, die je Processing-Run und Ledger-Eintrag gespeichert wird.
 * Quelle: {@code META-INF/build-info.properties} (spring-boot-maven-plugin, Goal build-info).
 * Außerhalb eines Maven-Builds (z. B. IDE) wird {@code dev} verwendet.
 */
@Component
public class ApplicationVersion {

    private final String version;

    public ApplicationVersion(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties bp = buildProperties.getIfAvailable();
        this.version = bp != null && bp.getVersion() != null ? bp.getVersion() : "dev";
    }

    public String value() {
        return version;
    }
}
