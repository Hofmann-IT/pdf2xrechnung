package de.hofmannit.erechnung.configuration.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.Direction;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.FieldMapping;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.PlausibilityCheck;
import de.hofmannit.erechnung.configuration.profile.ProfileDefinition.RegionMode;
import de.hofmannit.erechnung.model.MappingRuleType;
import de.hofmannit.erechnung.model.OutputFormat;
import de.hofmannit.erechnung.security.Sha256;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileLoaderTest {

    private static final Path PROFILES_DIR = Path.of("profiles");

    @Test
    void standardProfileLoadsAndDemonstratesEveryRuleType() throws IOException {
        Map<String, LoadedProfile> profiles = new ProfileLoader().loadAll(PROFILES_DIR);
        assertThat(profiles).containsKey("standard");

        LoadedProfile loaded = profiles.get("standard");
        assertThat(loaded.sha256()).isEqualTo(Sha256.ofFile(PROFILES_DIR.resolve("standard.yaml")));
        ProfileDefinition def = loaded.definition();

        assertThat(def.profile().name()).isEqualTo("standard");
        assertThat(def.profile().schemaVersion()).isEqualTo(1);

        // Klassifizierung
        assertThat(def.classification().invoiceIndicators()).isNotEmpty();
        assertThat(def.classification().nonInvoiceIndicators()).isNotEmpty();
        assertThat(def.classification().businessCases())
                .extracting(ProfileDefinition.BusinessCase::id)
                .containsExactlyInAnyOrder("DOMESTIC_STANDARD", "NO_VAT", "EU_REVERSE_CHARGE", "THIRD_COUNTRY");
        assertThat(def.classification().businessCases()).filteredOn(ProfileDefinition.BusinessCase::defaultCase)
                .singleElement().extracting(ProfileDefinition.BusinessCase::vatCategoryCode).isEqualTo("S");

        // Jede Regelart ist vertreten
        assertThat(def.fields()).extracting(f -> f.rule().type())
                .contains(MappingRuleType.ANCHOR, MappingRuleType.REGEX, MappingRuleType.REGION, MappingRuleType.FIXED);
        assertThat(def.lineItems()).isNotNull();
        assertThat(def.lineItems().columns()).hasSizeGreaterThanOrEqualTo(5);
        assertThat(def.lineItems().rowDetection().keyColumnBusinessTerm()).isEqualTo("BT-126");

        // Einzelne Regeln stichprobenartig
        FieldMapping bt1 = field(def, "BT-1");
        assertThat(bt1.required()).isTrue();
        assertThat(bt1.rule().type()).isEqualTo(MappingRuleType.ANCHOR);
        assertThat(bt1.rule().label()).isEqualTo("Rechnungsnummer:");
        assertThat(bt1.rule().direction()).isEqualTo(Direction.RIGHT);
        assertThat(bt1.rule().maxDistance()).isEqualTo(200.0);

        FieldMapping bt2 = field(def, "BT-2");
        assertThat(bt2.rule().type()).isEqualTo(MappingRuleType.REGEX);
        assertThat(bt2.rule().group()).isEqualTo(1);
        assertThat(bt2.transform()).containsExactly("trim", "date:dd.MM.yyyy");

        FieldMapping bt44 = field(def, "BT-44");
        assertThat(bt44.rule().type()).isEqualTo(MappingRuleType.REGION);
        assertThat(bt44.rule().regionMode()).isEqualTo(RegionMode.FIRST_LINE);
        assertThat(bt44.rule().width()).isEqualTo(240.0);

        FieldMapping bt27 = field(def, "BT-27");
        assertThat(bt27.rule().type()).isEqualTo(MappingRuleType.FIXED);
        assertThat(bt27.rule().key()).isEqualTo("seller-name");

        // Plausibilität, Erzeugung, Postprozess
        assertThat(def.plausibility().tolerance()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(def.plausibility().checks()).containsExactlyInAnyOrder(PlausibilityCheck.values());
        assertThat(def.generation().formats())
                .containsExactly(OutputFormat.XRECHNUNG_CII, OutputFormat.XRECHNUNG_UBL, OutputFormat.ZUGFERD_EN16931);
        assertThat(def.generation().filenameTemplate()).isEqualTo("{invoiceNumber}_{invoiceDate:yyyyMMdd}_{customerName}");
        assertThat(def.generation().invoiceTypeCodes()).containsEntry("invoice", "380").containsEntry("credit-note", "381");
        assertThat(def.postProcess().email().enabled()).isFalse();
        assertThat(def.postProcess().command().enabled()).isFalse();
    }

    @Test
    void profileNameMustMatchFileName(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("other.yaml");
        Files.writeString(file, minimalProfile("standard"), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ProfileLoader().load(file))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("muss dem Dateinamen");
    }

    @Test
    void unknownBusinessTermAndBadRegexAreReported(@TempDir Path tmp) throws IOException {
        String yaml = minimalProfile("bad") + """
                fields:
                  - businessTerm: BT-999
                    rule: { type: regex, pattern: "([unclosed" }
                """;
        Path file = tmp.resolve("bad.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ProfileLoader().load(file))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("BT-999")
                .hasMessageContaining("regulärer Ausdruck");
    }

    @Test
    void exactlyOneDefaultBusinessCaseIsRequired(@TempDir Path tmp) throws IOException {
        String yaml = """
                profile:
                  name: nodefault
                classification:
                  invoiceIndicators:
                    - regex: "Rechnung"
                  businessCases:
                    - id: A
                      vatCategoryCode: S
                    - id: B
                      vatCategoryCode: AE
                generation:
                  formats: [XRECHNUNG_CII]
                  filenameTemplate: "{invoiceNumber}"
                """;
        Path file = tmp.resolve("nodefault.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ProfileLoader().load(file))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("defaultCase");
    }

    @Test
    void tableRuleOutsideLineItemsIsRejected(@TempDir Path tmp) throws IOException {
        String yaml = minimalProfile("tbl") + """
                fields:
                  - businessTerm: BT-1
                    rule: { type: table }
                """;
        Path file = tmp.resolve("tbl.yaml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new ProfileLoader().load(file))
                .isInstanceOf(ProfileException.class)
                .hasMessageContaining("nur unter lineItems");
    }

    @Test
    void hashChangesWhenFileChanges(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("h.yaml");
        Files.writeString(file, minimalProfile("h"), StandardCharsets.UTF_8);
        String first = new ProfileLoader().load(file).sha256();
        Files.writeString(file, minimalProfile("h") + "# Kommentar\n", StandardCharsets.UTF_8);
        String second = new ProfileLoader().load(file).sha256();
        assertThat(first).hasSize(64).isNotEqualTo(second);
    }

    private static FieldMapping field(ProfileDefinition def, String bt) {
        return def.fields().stream().filter(f -> f.businessTerm().equals(bt)).findFirst()
                .orElseThrow(() -> new AssertionError("Feld " + bt + " fehlt im Profil"));
    }

    private static String minimalProfile(String name) {
        return """
                profile:
                  name: %s
                classification:
                  invoiceIndicators:
                    - regex: "Rechnung"
                  businessCases:
                    - id: DOMESTIC_STANDARD
                      vatCategoryCode: S
                      defaultCase: true
                generation:
                  formats: [XRECHNUNG_CII]
                  filenameTemplate: "{invoiceNumber}"
                """.formatted(name);
    }
}
