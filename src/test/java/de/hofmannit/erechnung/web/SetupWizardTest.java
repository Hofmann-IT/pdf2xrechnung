package de.hofmannit.erechnung.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import de.hofmannit.erechnung.admin.SetupService;
import de.hofmannit.erechnung.configuration.RuntimeSettings;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.watcher.InboxWatcher;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Einrichtungs-Assistent (ADR 0013): Start ohne aktiven Mandanten und ohne Passwort, Umleitung
 * aller Seiten, Abschluss schreibt die Mandantendatei (temporär), lädt neu, setzt Passwort und
 * Laufzeiteinstellungen; danach normale Oberfläche und Verwaltung mit dem vergebenen Passwort.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SetupWizardTest {

    static final Path ROOT = createRoot();

    private static Path createRoot() {
        try {
            Path base = Path.of("target", "test-data");
            Files.createDirectories(base);
            return Files.createTempDirectory(base, "setup-").toAbsolutePath();
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
        // Mandantendatei des Tests (existiert noch nicht) statt config/tenant.yaml; der aus der
        // Projekt-YAML gebundene Mandant wird deaktiviert → Einrichtung nötig.
        r.add("app.tenant-file", () -> ROOT.resolve("config").resolve("tenant.yaml").toString());
        r.add("tenants[0].id", () -> "alt");
        r.add("tenants[0].name", () -> "Alt");
        r.add("tenants[0].enabled", () -> "false");
        r.add("tenants[0].profiles[0]", () -> "standard");
    }

    @Autowired MockMvc mvc;
    @Autowired SetupService setup;
    @Autowired ProfileRegistry registry;
    @Autowired RuntimeSettings runtimeSettings;
    @Autowired InboxWatcher watcher;

    private static String basic(String userAndPassword) {
        return "Basic " + Base64.getEncoder().encodeToString(userAndPassword.getBytes(StandardCharsets.UTF_8));
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @Order(1)
    void everythingRedirectsToTheWizardUntilSetupIsDone() throws Exception {
        assertThat(setup.needed()).isTrue();
        for (String path : List.of("/", "/rechnungen", "/status", "/verwaltung", "/pruefen")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/einrichtung"));
        }
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
        String page = body(mvc.perform(get("/einrichtung")).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("Willkommen").contains("name=\"iban\"").contains("name=\"adminPassword\"").contains("name=\"inbox\"")
                .contains(ROOT.resolve("inbox").toString().replace("\\", "\\"));
    }

    @Test
    @Order(2)
    void invalidInputIsRejectedWithoutWritingAnything() throws Exception {
        String page = body(mvc.perform(post("/einrichtung").param("name", "Muster GmbH").param("street", "Weg 1").param("postCode", "12345")
                .param("city", "Ort").param("countryCode", "DE").param("email", "keine").param("contactName", "M").param("phone", "1")
                .param("iban", "DE00123456780000000000").param("currency", "EUR")
                .param("inbox", ROOT.resolve("inbox").toString()).param("processing", ROOT.resolve("processing").toString())
                .param("output", ROOT.resolve("output").toString()).param("failed", ROOT.resolve("failed").toString())
                .param("manualReview", ROOT.resolve("manual-review").toString()).param("rejected", ROOT.resolve("rejected").toString())
                .param("archive", ROOT.resolve("archive").toString()).param("inboundValidation", ROOT.resolve("inbound-validation").toString())
                .param("pollIntervalSeconds", "5").param("stableChecks", "2").param("tenantParallelism", "1").param("smtpPort", "587")
                .param("smtpTimeoutSeconds", "30").param("adminPassword", "kurz").param("adminPasswordRepeat", "kurz"))
                .andExpect(status().isOk()).andReturn());
        assertThat(page).contains("Bitte korrigieren").contains("USt-IdNr. (BT-31) oder Steuernummer").contains("E-Mail-Adresse")
                .contains("IBAN (BT-84) ist ungültig").contains("value=\"Muster GmbH\"");
        assertThat(ROOT.resolve("config").resolve("tenant.yaml")).doesNotExist();
        assertThat(setup.needed()).isTrue();
    }

    @Test
    @Order(3)
    void completingTheWizardWritesTenantFileReloadsAndUnlocks() throws Exception {
        mvc.perform(post("/einrichtung").param("name", "Müller & Söhne GmbH").param("street", "Hauptstraße 7").param("postCode", "80331")
                .param("city", "München").param("countryCode", "DE").param("vatId", "DE 123456789").param("email", "rechnung@mueller.de")
                .param("contactName", "Anna Müller").param("phone", "+49 89 1234").param("iban", "DE02 1203 0000 0000 2020 51").param("bic", "BYLADEM1001")
                .param("currency", "EUR")
                .param("inbox", ROOT.resolve("inbox").toString()).param("processing", ROOT.resolve("processing").toString())
                .param("output", ROOT.resolve("output").toString()).param("failed", ROOT.resolve("failed").toString())
                .param("manualReview", ROOT.resolve("manual-review").toString()).param("rejected", ROOT.resolve("rejected").toString())
                .param("archive", ROOT.resolve("archive").toString()).param("inboundValidation", ROOT.resolve("inbound-validation").toString())
                .param("watcherEnabled", "on").param("pollIntervalSeconds", "2").param("stableChecks", "2").param("tenantParallelism", "1")
                .param("smtpPort", "587").param("smtpTimeoutSeconds", "30")
                .param("adminUser", "admin").param("adminPassword", "sehr-geheim-123").param("adminPasswordRepeat", "sehr-geheim-123")
                .param("actor", "Anna"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/")).andExpect(flash().attributeExists("notice"));

        // Mandantendatei geschrieben und neu geladen: Kennung aus dem Firmennamen, feste Werte normalisiert
        Path file = ROOT.resolve("config").resolve("tenant.yaml");
        assertThat(file).exists();
        String yaml = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(yaml).contains("id: \"mueller-soehne-gmbh\"").contains("seller-vat-id: \"DE123456789\"")
                .contains("payment-account-iban: \"DE02120300000000202051\"").contains("KEINE SECRETS");
        assertThat(registry.tenants()).extracting(t -> t.id()).containsExactly("mueller-soehne-gmbh");
        assertThat(registry.tenant("mueller-soehne-gmbh").orElseThrow().fixedValues()).containsEntry("seller-name", "Müller & Söhne GmbH")
                .containsEntry("payment-means-type-code", "58");
        assertThat(setup.needed()).isFalse();
        assertThat(runtimeSettings.source()).isEqualTo(RuntimeSettings.Source.DATABASE);
        assertThat(watcher.activeIntervalMillis()).isEqualTo(2000);

        // Oberfläche frei, Assistent zeigt nur noch den Hinweis, Verwaltung mit dem vergebenen Passwort
        mvc.perform(get("/")).andExpect(status().isOk());
        assertThat(body(mvc.perform(get("/einrichtung")).andExpect(status().isOk()).andReturn())).contains("Einrichtung abgeschlossen");
        mvc.perform(get("/verwaltung")).andExpect(status().isUnauthorized());
        mvc.perform(get("/verwaltung").header("Authorization", basic("admin:falsch"))).andExpect(status().isUnauthorized());
        String admin = body(mvc.perform(get("/verwaltung").header("Authorization", basic("admin:sehr-geheim-123"))).andExpect(status().isOk()).andReturn());
        assertThat(admin).contains("Passwort ist in der Anwendung vergeben").contains("mueller-soehne-gmbh");
        mvc.perform(post("/einrichtung").param("name", "x")).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/verwaltung"));

        // Passwort ändern und Mandanten neu laden über die Verwaltung
        mvc.perform(post("/verwaltung/passwort").header("Authorization", basic("admin:sehr-geheim-123")).param("adminUser", "admin")
                .param("adminPassword", "neues-passwort-9").param("adminPasswordRepeat", "neues-passwort-9").param("user", "Anna"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        mvc.perform(get("/verwaltung").header("Authorization", basic("admin:sehr-geheim-123"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/verwaltung").header("Authorization", basic("admin:neues-passwort-9"))).andExpect(status().isOk());

        // Manuelle Änderung der Datei: gültig → übernommen; ungültig → abgelehnt, alter Stand bleibt
        Files.writeString(file, yaml.replace("name: \"Müller & Söhne GmbH\"", "name: \"Müller und Söhne GmbH\""), StandardCharsets.UTF_8);
        mvc.perform(post("/verwaltung/neu-laden").header("Authorization", basic("admin:neues-passwort-9")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        assertThat(registry.tenant("mueller-soehne-gmbh").orElseThrow().name()).isEqualTo("Müller und Söhne GmbH");
        Files.writeString(file, yaml.replace("- standard", "- gibt-es-nicht"), StandardCharsets.UTF_8);
        mvc.perform(post("/verwaltung/neu-laden").header("Authorization", basic("admin:neues-passwort-9")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(registry.tenant("mueller-soehne-gmbh").orElseThrow().name()).isEqualTo("Müller und Söhne GmbH");
    }

    @Test
    void helpers() {
        assertThat(SetupService.suggestId("Müller & Söhne GmbH")).isEqualTo("mueller-soehne-gmbh");
        assertThat(SetupService.suggestId("  ")).isEqualTo("mandant");
        assertThat(SetupService.ibanChecksumValid("DE02120300000000202051")).isTrue();
        assertThat(SetupService.ibanChecksumValid("DE00120300000000202051")).isFalse();
        assertThat(SetupService.q("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }
}
