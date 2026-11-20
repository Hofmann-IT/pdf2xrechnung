package de.hofmannit.erechnung.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;

import de.hofmannit.erechnung.configuration.NetworkAccess.Mode;
import de.hofmannit.erechnung.testsupport.TestCertificates;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;

/**
 * Netzzugriff (ADR 0015, Phase B): Modus, Zertifikatssuche und Bindung des Webservers.
 * Grundsatz unter Test: Jede Störung endet bei „nur dieser Computer", nie bei offenem HTTP.
 */
class NetworkAccessTest {

    static final Clock NOW = Clock.fixed(Instant.now().plus(Duration.ofHours(1)), ZoneOffset.UTC);
    static TestCertificates certificates;

    @BeforeAll
    static void createCertificates() {
        // Zwei Einträge mit demselben Anzeigenamen (wie Windows sie nach einer Erneuerung führt) und ein fremder
        certificates = TestCertificates.create("netz-")
                .add("PDF-zu-ERechnung", "Server-Alt", 0, 30)
                .add("pdf-zu-erechnung (1)", "Server-Neu", 0, 400)
                .add("Anderes Zertifikat", "Fremd", 0, 900);
    }

    private static AppProperties.Network network(String mode, int port, String alias, String thumbprint) {
        return new AppProperties.Network(mode, port, "Test", alias, thumbprint, Path.of("target", "test-data", "unbenutzt.yaml"));
    }

    @Test
    void standardIstNurDieserComputer() {
        NetworkAccess access = new NetworkAccess(network("local", 8443, "PDF-zu-ERechnung", null), 8080, failing(), NOW);

        assertThat(access.requested()).isEqualTo(Mode.LOCAL);
        assertThat(access.effective()).isEqualTo(Mode.LOCAL);
        assertThat(access.problem()).isNull();
        assertThat(access.tls()).isEmpty();
        assertThat(access.certificate()).isNull();
    }

    @Test
    void unbekannterModusBleibtLokalUndNenntDenGrund() {
        NetworkAccess access = new NetworkAccess(network("alle", 8443, "PDF-zu-ERechnung", null), 8080, certificates.source(), NOW);

        assertThat(access.effective()).isEqualTo(Mode.LOCAL);
        assertThat(access.problem()).contains("Unbekannter Modus 'alle'");
        assertThat(access.tls()).isEmpty();
    }

    @Test
    void netzzugriffNimmtDasAmLaengstenGueltigeZertifikatMitDemAnzeigenamen() throws Exception {
        NetworkAccess access = new NetworkAccess(network("network", 8443, "PDF-zu-ERechnung", null), 8080, certificates.source(), NOW);

        assertThat(access.problem()).isNull();
        assertThat(access.effective()).isEqualTo(Mode.NETWORK);
        assertThat(access.certificate().subject()).isEqualTo("CN=Server-Neu");
        assertThat(access.certificate().names()).contains("localhost", "server-neu");
        assertThat(access.certificateValid()).isTrue();
        assertThat(access.tls()).isPresent();
        assertThat(access.tls().get().alias()).isEqualTo("pdf-zu-erechnung (1)");
        // Fingerabdruck = SHA-256 über das DER-kodierte Zertifikat, wie Windows und Browser ihn anzeigen
        KeyStore store = certificates.keyStore();
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(store.getCertificate("pdf-zu-erechnung (1)").getEncoded()));
        assertThat(access.certificate().sha256()).isEqualTo(expected);
        assertThat(access.certificate().sha256Grouped()).hasSize(95).matches("([0-9A-F]{2}:){31}[0-9A-F]{2}");
    }

    @Test
    void fingerabdruckWaehltGenauEinZertifikat() throws Exception {
        KeyStore store = certificates.keyStore();
        String thumbprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(store.getCertificate("anderes zertifikat").getEncoded()));
        // Schreibweise wie in der Windows-Zertifikatsanzeige: Großbuchstaben mit Leerzeichen
        String windowsStyle = thumbprint.toUpperCase(java.util.Locale.ROOT).replaceAll("(..)", "$1 ").trim();

        NetworkAccess access = new NetworkAccess(network("network", 8443, "PDF-zu-ERechnung", windowsStyle), 8080, certificates.source(), NOW);

        assertThat(access.effective()).isEqualTo(Mode.NETWORK);
        assertThat(access.certificate().subject()).isEqualTo("CN=Fremd");
        assertThat(access.certificate().thumbprint()).isEqualTo(thumbprint);
    }

    @Test
    void fehlendesZertifikatFuehrtZuLokalemBetriebStattOffenemHttp() {
        NetworkAccess access = new NetworkAccess(network("network", 8443, "Gibt es nicht", null), 8080, certificates.source(), NOW);

        assertThat(access.requested()).isEqualTo(Mode.NETWORK);
        assertThat(access.effective()).isEqualTo(Mode.LOCAL);
        assertThat(access.problem()).contains("Kein Zertifikat mit dem Anzeigenamen 'Gibt es nicht'").contains("netzbetrieb-einrichten.ps1");
        assertThat(access.tls()).isEmpty();
    }

    @Test
    void nichtVerfuegbarerSpeicherFuehrtZuLokalemBetrieb() {
        NetworkAccess linux = new NetworkAccess(network("network", 8443, "PDF-zu-ERechnung", null), 8080,
                NetworkAccess.storeSource("Gibt-es-nicht-Speicher"), NOW);

        assertThat(linux.effective()).isEqualTo(Mode.LOCAL);
        assertThat(linux.problem()).contains("ist nicht verfügbar").contains("Reverse Proxy");
        assertThat(linux.tls()).isEmpty();
    }

    @Test
    void httpsPortDarfNichtDerLokalePortSein() {
        NetworkAccess access = new NetworkAccess(network("network", 8080, "PDF-zu-ERechnung", null), 8080, certificates.source(), NOW);

        assertThat(access.effective()).isEqualTo(Mode.LOCAL);
        assertThat(access.problem()).contains("bereits der lokale Port");
    }

    @Test
    void abgelaufenesZertifikatWirdVerwendetAberGemeldet() {
        Clock later = Clock.offset(NOW, Duration.ofDays(500));

        NetworkAccess access = new NetworkAccess(network("network", 8443, "PDF-zu-ERechnung", null), 8080, certificates.source(), later);

        assertThat(access.effective()).isEqualTo(Mode.NETWORK);
        assertThat(access.certificateValid()).isFalse();
    }

    @Test
    void bindungLokalNurLoopbackOhneWeiterenConnector() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        new NetworkConfiguration.Binding(new NetworkAccess(network("local", 8443, "PDF-zu-ERechnung", null), 8080, failing(), NOW)).customize(factory);

        assertThat(factory.getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
        assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
    }

    @Test
    void bindungNetzLoopbackFuerHttpUndEinHttpsConnector() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        new NetworkConfiguration.Binding(new NetworkAccess(network("network", 8443, "PDF-zu-ERechnung", null), 8080, certificates.source(), NOW))
                .customize(factory);

        assertThat(factory.getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
        assertThat(factory.getAdditionalTomcatConnectors()).hasSize(1);
        var https = factory.getAdditionalTomcatConnectors().get(0);
        assertThat(https.getPort()).isEqualTo(8443);
        assertThat(https.getScheme()).isEqualTo("https");
        assertThat(https.getSecure()).isTrue();
        assertThat(https.findSslHostConfigs()).hasSize(1);
        assertThat(https.findSslHostConfigs()[0].getProtocols()).contains("TLSv1.2", "TLSv1.3").doesNotContain("TLSv1.1", "TLSv1", "SSLv3");
    }

    @Test
    void bindungGestoertesNetzBleibtLoopbackOhneHttps() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();

        new NetworkConfiguration.Binding(new NetworkAccess(network("network", 8443, "Gibt es nicht", null), 8080, certificates.source(), NOW))
                .customize(factory);

        assertThat(factory.getAddress()).isEqualTo(InetAddress.getLoopbackAddress());
        assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
    }

    @Test
    void bindungOpenLaesstAdresseUnveraendert() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        NetworkAccess access = new NetworkAccess(network("OPEN", 8443, "PDF-zu-ERechnung", null), 8080, failing(), NOW);

        new NetworkConfiguration.Binding(access).customize(factory);

        assertThat(access.effective()).isEqualTo(Mode.OPEN);
        assertThat(factory.getAddress()).isNull();
        assertThat(factory.getAdditionalTomcatConnectors()).isEmpty();
    }

    /** Quelle, die nicht gelesen werden darf: lokal und open fassen den Zertifikatsspeicher nicht an. */
    private static CertificateSource failing() {
        return () -> {
            throw new AssertionError("Zertifikatsspeicher darf in diesem Modus nicht gelesen werden");
        };
    }
}
