package com.careertrack.gateway.filter;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientAddressFilterTest {

    @Test
    void refusesAShortBffKey() {
        assertThatThrownBy(() -> new ClientAddressFilter("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }

    @Test
    void acceptsAnEmptyKeyAsDisabled() {
        assertThatCode(() -> new ClientAddressFilter("")).doesNotThrowAnyException();
        assertThatCode(() -> new ClientAddressFilter(null)).doesNotThrowAnyException();
    }

    @Test
    void acceptsA32CharacterKey() {
        assertThatCode(() -> new ClientAddressFilter("k".repeat(32)))
                .doesNotThrowAnyException();
    }
}
