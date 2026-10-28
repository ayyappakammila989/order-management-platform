package com.retail.oms.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class CorrelationIdFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void incomingIdIsKeptEchoedAndAvailableInMdc() throws Exception {
        mvc.perform(get("/demo/mdc").header(CorrelationId.HEADER, "abc-123"))
                .andExpect(header().string(CorrelationId.HEADER, "abc-123"))
                .andExpect(content().string("abc-123"));
    }

    @Test
    void missingIdIsGenerated() throws Exception {
        String id = mvc.perform(get("/demo/mdc"))
                .andExpect(header().exists(CorrelationId.HEADER))
                .andReturn().getResponse().getHeader(CorrelationId.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
    }

    @Test
    void absurdlyLongIdIsReplaced() throws Exception {
        String id = mvc.perform(get("/demo/mdc").header(CorrelationId.HEADER, "x".repeat(500)))
                .andReturn().getResponse().getHeader(CorrelationId.HEADER);
        assertThat(id).hasSize(36);
    }

    @Test
    void mdcIsClearedAfterTheRequest() throws Exception {
        mvc.perform(get("/demo/mdc").header(CorrelationId.HEADER, "abc-123"));
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }
}
