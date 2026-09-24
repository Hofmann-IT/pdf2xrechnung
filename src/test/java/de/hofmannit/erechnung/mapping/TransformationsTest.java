package de.hofmannit.erechnung.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import de.hofmannit.erechnung.mapping.Transformations.TransformationException;

import org.junit.jupiter.api.Test;

class TransformationsTest {

    @Test
    void germanDecimals() throws Exception {
        assertThat(Transformations.parseDecimal("1.234,56", "de")).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(Transformations.parseDecimal("1.234,56 €", "de")).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(Transformations.parseDecimal("-12,50", "de")).isEqualByComparingTo(new BigDecimal("-12.50"));
        assertThat(Transformations.parseDecimal("12,50-", "de")).isEqualByComparingTo(new BigDecimal("-12.50"));
        assertThat(Transformations.parseDecimal("1,234.56", "en")).isEqualByComparingTo(new BigDecimal("1234.56"));
    }

    @Test
    void dateAndChain() throws Exception {
        assertThat(Transformations.apply(" 24.09.2026 ", List.of("trim", "date:dd.MM.yyyy"))).isEqualTo("2026-09-24");
        assertThat(Transformations.apply("DE02 1203 0000", List.of("removeWhitespace", "upper"))).isEqualTo("DE0212030000");
        assertThat(Transformations.apply("19 %", List.of("percent:de"))).isEqualTo("19");
        assertThat(Transformations.apply("12345 Musterstadt", List.of("regexReplace:/^(\\d{5})\\s+.*$/$1/"))).isEqualTo("12345");
    }

    @Test
    void invalidValuesAreReportedNotGuessed() {
        assertThatThrownBy(() -> Transformations.apply("31.02.2026", List.of("date:dd.MM.yyyy"))).isInstanceOf(TransformationException.class);
        assertThatThrownBy(() -> Transformations.apply("abc", List.of("decimal:de"))).isInstanceOf(TransformationException.class);
        assertThatThrownBy(() -> Transformations.apply("x", List.of("unknownTransform"))).isInstanceOf(TransformationException.class);
    }
}
