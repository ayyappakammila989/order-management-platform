package com.retail.oms.common.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ErrorHandlingTest {

    @Autowired
    MockMvc mvc;

    @Test
    void omsExceptionUsesItsStatusAndCode() throws Exception {
        mvc.perform(get("/demo/oms").header(CorrelationId.HEADER, "corr-123"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("ORD-409"))
                .andExpect(jsonPath("$.message").value("Order cannot be cancelled after shipment"))
                .andExpect(jsonPath("$.path").value("/demo/oms"))
                .andExpect(jsonPath("$.traceId").value("corr-123"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/demo/body").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("must not be blank"));
    }

    @Test
    void invalidRequestParamReturns400WithFieldErrors() throws Exception {
        mvc.perform(get("/demo/param").param("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"));
    }

    @Test
    void malformedJsonReturns400NotTheGeneric500() throws Exception {
        mvc.perform(post("/demo/body").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"));
    }

    @Test
    void unknownPathReturns404() throws Exception {
        mvc.perform(get("/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SYS-404"))
                .andExpect(jsonPath("$.path").value("/nope"));
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mvc.perform(post("/demo/oms"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.errorCode").value("SYS-405"));
    }

    @Test
    void accessDeniedReturns403() throws Exception {
        mvc.perform(get("/demo/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC-403"))
                .andExpect(jsonPath("$.message").value("Access denied"));
    }

    @Test
    void openCircuitReturns503() throws Exception {
        mvc.perform(get("/demo/circuit"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SYS-503"));
    }

    @Test
    void unexpectedExceptionReturnsGenericMessageWithoutLeakingDetails() throws Exception {
        mvc.perform(get("/demo/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.errorCode").value("SYS-500"))
                .andExpect(jsonPath("$.message").value("Unexpected error"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("hunter2"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("IllegalStateException"))));
    }

    @Test
    void errorResponsesCarryTheCorrelationIdHeader() throws Exception {
        mvc.perform(get("/demo/oms"))
                .andExpect(header().exists(CorrelationId.HEADER));
    }
}
