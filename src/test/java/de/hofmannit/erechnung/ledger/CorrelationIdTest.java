package de.hofmannit.erechnung.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CorrelationIdTest {

    private static final String SHA = "ABC123EF0000000000000000000000000000000000000000000000000000ffff";

    @Test
    void sourceCorrelationIdIsLowercaseShaPrefix() {
        assertThat(CorrelationId.forSource(SHA).value()).isEqualTo("abc123ef");
    }

    @Test
    void runCorrelationIdAppendsZeroPaddedRunNumber() {
        assertThat(CorrelationId.forRun(SHA, 2).value()).isEqualTo("abc123ef/run-002");
        assertThat(CorrelationId.forRun(SHA, 123).value()).isEqualTo("abc123ef/run-123");
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> CorrelationId.forRun(SHA, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorrelationId.forSource("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorrelationId(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
