package de.hofmannit.erechnung.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import de.hofmannit.erechnung.admin.NetworkSettingsService;
import de.hofmannit.erechnung.admin.UserAccountService;
import de.hofmannit.erechnung.admin.UserAccountService.Role;
import de.hofmannit.erechnung.configuration.CertificateSource;
import de.hofmannit.erechnung.configuration.NetworkAccess;
import de.hofmannit.erechnung.configuration.NetworkAccess.Mode;
import de.hofmannit.erechnung.testsupport.TestCertificates;
import de.hofmannit.erechnung.testsupport.TestLogin;

import org.apache.catalina.connector.Connector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Netzzugriff mit echtem Webserver (ADR 0015, Phase B): Der HTTP-Zugang hängt nur an der
 * Loopback-Adresse, das Firmennetz erreicht den Dienst ausschließlich über HTTPS mit dem
 * Zertifikat aus dem Speicher; die Verwaltung speichert Änderungen in {@code network.yaml}.
 * Der Windows-Zertifikatsspeicher ist durch einen Testspeicher ersetzt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class NetworkHttpsTest {

    static final Path ROOT = createRoot();
    static final int HTTPS_PORT = freePort();
    static final TestCertificates CERTIFICATES = TestCertificates.create("https-").add("PDF-zu-ERechnung", "Testserver", 0, 30);

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "netz-https-").toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        for (String d : List.of("inbox", "processing", "output", "failed", "manual-review", "rejected", "archive", "data", "inbound-validation")) {
            r.add("app.directories." + d, () -> ROOT.resolve(d).toString());
        }
        r.add("app.logging.directory", () -> ROOT.resolve("logs").toString());
        r.add("app.watcher.enabled", () -> "false");
        r.add("app.network.mode", () -> "network");
        r.add("app.network.port", () -> String.valueOf(HTTPS_PORT));
        r.add("app.network.file", () -> ROOT.resolve("config").resolve("network.yaml").toString());
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        CertificateSource testCertificateSource() {
            return CERTIFICATES.source();
        }
    }

    @Autowired ServletWebServerApplicationContext context;
    @Autowired NetworkAccess access;
    @Autowired NetworkSettingsService settings;
    @Autowired UserAccountService accounts;
    @Autowired MockMvc mvc;

    @BeforeEach
    void accounts() throws Exception {
        if (accounts.find("uwe").isEmpty()) {
            accounts.create("uwe", Role.ADMIN, "Test-Passwort-1", "Test-Passwort-1", "test");
            accounts.create("erika", Role.USER, "Test-Passwort-2", "Test-Passwort-2", "test");
        }
    }

    @Test
    void httpNurAufLoopbackUndHttpsAufAllenSchnittstellen() {
        assertThat(access.effective()).isEqualTo(Mode.NETWORK);
        TomcatWebServer server = (TomcatWebServer) context.getWebServer();
        Connector http = server.getTomcat().getConnector();
        assertThat(http.getScheme()).isEqualTo("http");
        assertThat(http.getProperty("address")).isEqualTo(InetAddress.getLoopbackAddress());

        List<Connector> others = Stream.of(server.getTomcat().getService().findConnectors()).filter(c -> c != http).toList();
        assertThat(others).hasSize(1);
        Connector https = others.get(0);
        assertThat(https.getScheme()).isEqualTo("https");
        assertThat(https.getLocalPort()).isEqualTo(HTTPS_PORT);
        // Keine Adresse = alle Schnittstellen
        assertThat(https.getProperty("address")).isNull();
    }

    @Test
    void httpsLiefertDieAnmeldeseiteMitDemZertifikatAusDemSpeicher() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://localhost:" + HTTPS_PORT + "/anmelden").toURL().openConnection();
        connection.setSSLSocketFactory(acceptAnyForFingerprintCheck().getSocketFactory());
        connection.setInstanceFollowRedirects(false);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(200);
            X509Certificate served = (X509Certificate) connection.getServerCertificates()[0];
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(served.getEncoded()));
            assertThat(fingerprint).isEqualTo(access.certificate().sha256());
            assertThat(connection.getCipherSuite()).startsWith("TLS_");
            try (InputStream in = connection.getInputStream()) {
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains("name=\"benutzer\"").contains("name=\"passwort\"");
            }
            // Über HTTPS ausgestellte Sitzungs-Cookies sind an HTTPS gebunden
            List<String> cookies = connection.getHeaderFields().getOrDefault("Set-Cookie", List.of());
            assertThat(cookies).filteredOn(c -> c.startsWith("ERECHNUNG_SESSION=")).isNotEmpty()
                    .allSatisfy(c -> assertThat(c).contains("Secure").contains("HttpOnly").contains("SameSite=Strict"));
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void geschuetzteSeiteLeitetUeberHttpsZurAnmeldungAufDemselbenPort() throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create("https://localhost:" + HTTPS_PORT + "/rechnungen").toURL().openConnection();
        connection.setSSLSocketFactory(acceptAnyForFingerprintCheck().getSocketFactory());
        connection.setInstanceFollowRedirects(false);
        try {
            assertThat(connection.getResponseCode()).isEqualTo(302);
            assertThat(connection.getHeaderField("Location")).isEqualTo("https://localhost:" + HTTPS_PORT + "/anmelden");
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void verwaltungZeigtFingerabdruckUndSpeichertAenderungFuerDenNeustart() throws Exception {
        Path file = ROOT.resolve("config").resolve("network.yaml");
        mvc.perform(get("/verwaltung").with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(access.certificate().sha256Grouped())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(":" + HTTPS_PORT)));

        mvc.perform(post("/verwaltung/netz").param("mode", "local").param("port", String.valueOf(HTTPS_PORT)).with(csrf()).with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(redirectedUrl("/verwaltung#netz"))
                .andExpect(flash().attributeExists("notice"));

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("app:\n  network:\n    mode: local\n    port: " + HTTPS_PORT);
        assertThat(settings.pending()).isPresent();
        assertThat(settings.pending().get().mode()).isEqualTo(Mode.LOCAL);
        // Der laufende Dienst bleibt unverändert, bis er neu gestartet wird
        assertThat(access.effective()).isEqualTo(Mode.NETWORK);
        mvc.perform(get("/verwaltung").with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Gespeichert, aber noch nicht wirksam")));

        // Zurück auf den laufenden Stand: vorhandene Datei wird gesichert, nichts steht mehr aus
        mvc.perform(post("/verwaltung/netz").param("mode", "network").param("port", String.valueOf(HTTPS_PORT)).with(csrf()).with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(flash().attributeExists("notice"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).contains("mode: network");
        assertThat(settings.pending()).isEmpty();
        try (Stream<Path> s = Files.list(file.getParent())) {
            assertThat(s.filter(p -> p.getFileName().toString().startsWith("network.yaml.bak-")).count()).isEqualTo(1);
        }
    }

    @Test
    void ungueltigeEingabenUndFremdeRollenAendernNichts() throws Exception {
        mvc.perform(post("/verwaltung/netz").param("mode", "open").param("port", "8443").with(csrf()).with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("Modus ungültig")));
        mvc.perform(post("/verwaltung/netz").param("mode", "network").param("port", "80").with(csrf()).with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(flash().attribute("error", org.hamcrest.Matchers.containsString("1024 bis 65535")));
        mvc.perform(post("/verwaltung/netz").param("mode", "local").param("port", "8443").with(csrf()).with(TestLogin.as("erika", "USER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/verwaltung/netz").param("mode", "local").param("port", "8443").with(TestLogin.as("uwe", "ADMIN")))
                .andExpect(status().isForbidden());
    }

    /**
     * Der Test prüft das Zertifikat selbst über den Fingerabdruck (wie später die
     * Verwaltungsanwendung); die Kettenprüfung des selbstsignierten Testzertifikats entfällt.
     */
    private static SSLContext acceptAnyForFingerprintCheck() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        return context;
    }
}
