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
    void keepsOrderNumbersAndUuidsSearchable() {
        String text = "Order OMS-20261029-000042 event 3b1e5f20-409d-4a07-a071-d57f105894cb ref A1234567890";
        assertThat(LogMasking.mask(text)).isEqualTo(text);
    }

    @Test
    void stillMasksPhoneNextToPunctuation() {
        assertThat(LogMasking.mask("phone=4155550134, alt:(415) 555-0134.")).isEqualTo("phone=***, alt:***.");
    }

    @Test
    void handlesNullAndEmpty() {
        assertThat(LogMasking.mask(null)).isNull();
        assertThat(LogMasking.mask("")).isEmpty();
    }
}
