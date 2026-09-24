package de.hofmannit.erechnung.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import javax.xml.transform.TransformerException;
import javax.xml.transform.URIResolver;

import org.junit.jupiter.api.Test;

class LocalOnlyResolvingStrategyTest {

    @Test
    void rejectsRemoteReferencesButResolvesLocalOnes() throws Exception {
        Path repo = Path.of("validator", "xrechnung").toAbsolutePath();
        URIResolver resolver = new LocalOnlyResolvingStrategy().createResolver(repo.toUri());
        assertThatThrownBy(() -> resolver.resolve("http://example.invalid/evil.xsl", null))
                .isInstanceOf(TransformerException.class)
                .hasMessageContaining("Netzwerkzugriff nicht erlaubt");
        assertThatThrownBy(() -> resolver.resolve("x.xsl", "https://example.invalid/base/"))
                .isInstanceOf(TransformerException.class);
        assertThat(resolver.resolve("resources/xrechnung-report.xsl", repo.toUri().toString())).isNotNull();
    }

    @Test
    void schemeDetection() {
        assertThat(LocalOnlyResolvingStrategy.isRemote("http")).isTrue();
        assertThat(LocalOnlyResolvingStrategy.isRemote("HTTPS")).isTrue();
        assertThat(LocalOnlyResolvingStrategy.isRemote("file")).isFalse();
        assertThat(LocalOnlyResolvingStrategy.isRemote(null)).isFalse();
    }
}
