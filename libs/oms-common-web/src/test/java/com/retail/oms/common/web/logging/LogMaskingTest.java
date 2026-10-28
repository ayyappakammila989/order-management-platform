package com.retail.oms.common.web.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskingTest {

    @Test
    void masksEmail() {
        assertThat(LogMasking.mask("sent to jane.doe+oms@example.co.uk now")).isEqualTo("sent to ***@*** now");
    }

    @Test
    void masksPhoneNumbers() {
        assertThat(LogMasking.mask("call +1 415 555 0134 or (415) 555-0134")).isEqualTo("call *** or ***");
    }

    @Test
    void leavesOrdinaryTextAndShortNumbersAlone() {
        String text = "order 12345 moved to SHIPPED, 3 lines";
        assertThat(LogMasking.mask(text)).isEqualTo(text);
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(LogMasking.mask(null)).isNull();
        assertThat(LogMasking.mask("")).isEmpty();
    }
}
