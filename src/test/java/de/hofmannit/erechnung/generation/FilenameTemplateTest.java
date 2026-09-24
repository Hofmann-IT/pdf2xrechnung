package de.hofmannit.erechnung.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class FilenameTemplateTest {

    @Test
    void rendersAndSanitizes() {
        Map<String, LocalDate> dates = new HashMap<>();
        dates.put("invoiceDate", LocalDate.of(2026, 9, 24));
        String name = FilenameTemplate.render("{invoiceNumber}_{invoiceDate:yyyyMMdd}_{customerName}",
                Map.of("invoiceNumber", "RE/2026 4711", "customerName", "Müller & Söhne GmbH"), dates);
        assertThat(name).isEqualTo("RE_2026_4711_20260924_Mueller_Soehne_GmbH");
    }

    @Test
    void rejectsPathTraversalAndUnknownPlaceholders() {
        assertThat(FilenameTemplate.sanitize("../../etc/passwd")).doesNotContain("..").doesNotContain("/");
        assertThatThrownBy(() -> FilenameTemplate.render("{foo}", Map.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilenameTemplate.render("{customerName}", Map.of("customerName", "///"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
