package de.hofmannit.erechnung.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import de.hofmannit.erechnung.admin.SetupService;
import de.hofmannit.erechnung.admin.UserAccountService;
import de.hofmannit.erechnung.configuration.RuntimeSettings;
import de.hofmannit.erechnung.configuration.profile.ProfileRegistry;
import de.hofmannit.erechnung.watcher.InboxWatcher;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

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

    /** Alle Anfragen tragen ein gueltiges CSRF-Token (wie die gerenderten Formulare); angemeldet wird ueber das Formular. */
    @TestConfiguration
    static class Csrf {
        @Bean
        MockMvcBuilderCustomizer csrfByDefault() {
            return builder -> builder.defaultRequest(get("/").with(csrf()));
        }
    }

    @Autowired MockMvc mvc;
    @Autowired UserAccountService accounts;
    @Autowired SetupService setup;
    @Autowired ProfileRegistry registry;
    @Autowired RuntimeSettings runtimeSettings;
    @Autowired InboxWatcher watcher;

    /** Anmeldung ueber das Formular wie im Browser (mit CSRF-Token). */
    private static RequestBuilder login(String username, String password) {
        return formLogin("/anmelden").userParameter("benutzer").passwordParam("passwort").user(username).password(password);
    }

    private static MockHttpSession session(MvcResult result) {
        return (MockHttpSession) result.getRequest().getSession(false);
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
        // Rechnungsvorlage und Feldbeschreibung sind schon vor der Einrichtung abrufbar
        MvcResult docx = mvc.perform(get("/vorlagen/Rechnungsvorlage.docx")).andExpect(status().isOk()).andReturn();
        assertThat(docx.getResponse().getContentType()).contains("officedocument.wordprocessingml");
        assertThat(docx.getResponse().getContentAsByteArray()).startsWith(new byte[] {0x50, 0x4B});
        String fields = body(mvc.perform(get("/vorlagen/Rechnungsvorlage-Felder.html")).andExpect(status().isOk()).andReturn());
        assertThat(fields).contains("Pflichtangaben").contains("PDF/A").contains("Rechnungsnummer:").contains("E-Mail:");
        assertThat(body(mvc.perform(get("/einrichtung")).andReturn())).contains("/vorlagen/Rechnungsvorlage.docx");
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
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/anmelden?eingerichtet"));

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

        // Ohne Anmeldung führt jede Seite zur Anmeldeseite; der Assistent ist nicht mehr frei zugänglich
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/anmelden"));
        mvc.perform(get("/einrichtung")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/anmelden"));
        mvc.perform(post("/einrichtung").param("name", "x")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/anmelden"));
        assertThat(body(mvc.perform(get("/anmelden").param("eingerichtet", "")).andExpect(status().isOk()).andReturn()))
                .contains("Einrichtung abgeschlossen").contains("name=\"benutzer\"").contains("name=\"passwort\"").contains("name=\"_csrf\"");

        // Anmeldung: falsches Passwort abgewiesen, richtiges öffnet eine Sitzung als Administrator
        mvc.perform(login("admin", "falsch")).andExpect(unauthenticated()).andExpect(redirectedUrl("/anmelden?fehler"));
        MockHttpSession admin = session(mvc.perform(login("admin", "sehr-geheim-123")).andExpect(authenticated().withUsername("admin").withRoles("ADMIN"))
                .andExpect(redirectedUrl("/")).andReturn());
        mvc.perform(get("/").session(admin)).andExpect(status().isOk());
        assertThat(body(mvc.perform(get("/einrichtung").session(admin)).andExpect(status().isOk()).andReturn())).contains("Einrichtung abgeschlossen");
        mvc.perform(post("/einrichtung").session(admin).param("name", "x")).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", "/verwaltung"));
        String page = body(mvc.perform(get("/verwaltung").session(admin)).andExpect(status().isOk()).andReturn());
        assertThat(page).contains("mueller-soehne-gmbh").contains("Benutzer anlegen").contains("Administrator").contains("Abmelden");

        // Benutzer anlegen: Sachbearbeiterin darf arbeiten, aber nicht in die Verwaltung
        mvc.perform(post("/verwaltung/benutzer").session(admin).param("username", "erika").param("role", "USER")
                .param("password", "kurz").param("passwordRepeat", "kurz")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/verwaltung/benutzer").session(admin).param("username", "erika").param("role", "USER")
                .param("password", "erikas-passwort-1").param("passwordRepeat", "erikas-passwort-1")).andExpect(flash().attributeExists("notice"));
        mvc.perform(post("/verwaltung/benutzer").session(admin).param("username", "Erika").param("role", "ADMIN")
                .param("password", "erikas-passwort-1").param("passwordRepeat", "erikas-passwort-1")).andExpect(flash().attributeExists("error"));
        assertThat(accounts.accounts()).extracting(a -> a.username() + ":" + a.role() + ":" + a.active()).containsExactly("admin:ADMIN:true", "erika:USER:true");
        MockHttpSession erika = session(mvc.perform(login("erika", "erikas-passwort-1")).andExpect(authenticated().withUsername("erika").withRoles("USER")).andReturn());
        assertThat(body(mvc.perform(get("/").session(erika)).andExpect(status().isOk()).andReturn())).contains("erika").doesNotContain("href=\"/verwaltung\"");
        mvc.perform(get("/verwaltung").session(erika)).andExpect(status().isForbidden());
        mvc.perform(post("/verwaltung/benutzer/rolle").session(erika).param("username", "erika").param("role", "ADMIN")).andExpect(status().isForbidden());

        // Eigenes Passwort ändern verlangt das bisherige; danach gilt nur noch das neue
        mvc.perform(post("/konto/passwort").session(erika).param("currentPassword", "falsch").param("password", "neues-passwort-9")
                .param("passwordRepeat", "neues-passwort-9")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/konto/passwort").session(erika).param("currentPassword", "erikas-passwort-1").param("password", "neues-passwort-9")
                .param("passwordRepeat", "neues-passwort-9")).andExpect(flash().attributeExists("notice"));
        mvc.perform(login("erika", "erikas-passwort-1")).andExpect(unauthenticated());
        mvc.perform(login("erika", "neues-passwort-9")).andExpect(authenticated());

        // Schreibende Anfrage ohne gültiges CSRF-Token wird abgewiesen
        mvc.perform(post("/verwaltung/neu-laden").session(admin).with(csrf().useInvalidToken())).andExpect(status().isForbidden());

        // Der letzte Administrator kann weder gesperrt noch herabgestuft werden
        mvc.perform(post("/verwaltung/benutzer/aktiv").session(admin).param("username", "admin").param("active", "false")).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/verwaltung/benutzer/rolle").session(admin).param("username", "admin").param("role", "USER")).andExpect(flash().attributeExists("error"));

        // Sperren beendet die laufende Sitzung mit der nächsten Anfrage und verhindert die Anmeldung
        mvc.perform(post("/verwaltung/benutzer/aktiv").session(admin).param("username", "erika").param("active", "false")).andExpect(flash().attributeExists("notice"));
        mvc.perform(get("/").session(erika)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/anmelden"));
        mvc.perform(login("erika", "neues-passwort-9")).andExpect(unauthenticated());

        // Fünf Fehlversuche sperren den Benutzernamen vorübergehend, auch für das richtige Passwort
        mvc.perform(post("/verwaltung/benutzer").session(admin).param("username", "karl").param("role", "USER")
                .param("password", "karls-passwort-1").param("passwordRepeat", "karls-passwort-1")).andExpect(flash().attributeExists("notice"));
        for (int i = 0; i < 4; i++) {
            mvc.perform(login("karl", "falsch-" + i)).andExpect(redirectedUrl("/anmelden?fehler"));
        }
        mvc.perform(login("karl", "falsch-5")).andExpect(redirectedUrl("/anmelden?gesperrt"));
        mvc.perform(login("karl", "karls-passwort-1")).andExpect(unauthenticated()).andExpect(redirectedUrl("/anmelden?gesperrt"));
        // Ein Administrator hebt die Sperre auf, indem er das Passwort neu vergibt
        mvc.perform(post("/verwaltung/benutzer/passwort").session(admin).param("username", "karl")
                .param("password", "karls-passwort-2").param("passwordRepeat", "karls-passwort-2")).andExpect(flash().attributeExists("notice"));
        mvc.perform(login("karl", "karls-passwort-2")).andExpect(authenticated());

        // Abmelden beendet die Sitzung
        mvc.perform(post("/abmelden").session(admin)).andExpect(redirectedUrl("/anmelden?abgemeldet"));
        mvc.perform(get("/verwaltung").session(admin)).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/anmelden"));
        admin = session(mvc.perform(login("admin", "sehr-geheim-123")).andExpect(authenticated()).andReturn());

        // Manuelle Änderung der Datei: gültig → übernommen; ungültig → abgelehnt, alter Stand bleibt
        Files.writeString(file, yaml.replace("name: \"Müller & Söhne GmbH\"", "name: \"Müller und Söhne GmbH\""), StandardCharsets.UTF_8);
        mvc.perform(post("/verwaltung/neu-laden").session(admin))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("notice"));
        assertThat(registry.tenant("mueller-soehne-gmbh").orElseThrow().name()).isEqualTo("Müller und Söhne GmbH");
        Files.writeString(file, yaml.replace("- standard", "- gibt-es-nicht"), StandardCharsets.UTF_8);
        mvc.perform(post("/verwaltung/neu-laden").session(admin))
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
