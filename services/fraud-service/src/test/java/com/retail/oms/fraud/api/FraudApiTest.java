package com.retail.oms.fraud.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import com.retail.oms.fraud.application.CaseNotOpenException;
import com.retail.oms.fraud.application.FraudService;
import com.retail.oms.fraud.config.SecurityConfig;
import com.retail.oms.fraud.domain.CaseStatus;
import com.retail.oms.fraud.domain.CheckKind;
import com.retail.oms.fraud.domain.Decision;
import com.retail.oms.fraud.domain.Evaluation;
import com.retail.oms.fraud.domain.FraudCase;
import com.retail.oms.fraud.domain.FraudCheck;
import com.retail.oms.fraud.domain.Subject;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Roles (architecture 7.4), validation and error codes, with the real SecurityConfig; the service is mocked. */
@WebMvcTest(controllers = {FraudCheckController.class, FraudCaseController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class FraudApiTest {

    static final Instant NOW = Instant.parse("2026-10-29T10:00:00Z");
    static final String CHECK = """
            {"kind":"REFUND","orderId":"%s","referenceId":"ret-1","customerId":"cust-1","amount":600.00,"currency":"USD"}"""
            .formatted(UUID.randomUUID());

    @Autowired MockMvc mvc;
    @MockitoBean FraudService fraud;

    FraudCheck check;
    FraudCase openCase;

    static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("user-" + role)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    @BeforeEach
    void setUp() {
        var subject = new Subject(CheckKind.REFUND, "cust-1", new BigDecimal("600.00"), "USD", null, null, List.of());
        check = FraudCheck.record(subject, UUID.randomUUID(), "ret-1", null, null,
                new Evaluation(50, Decision.REVIEW, List.of("HIGH_REFUND_VALUE")), NOW);
        openCase = FraudCase.forCheck(check, NOW);
        when(fraud.check(check.getCheckId())).thenReturn(check);
    }

    @Test
    void internalCheckNeedsTheServiceRole() throws Exception {
        mvc.perform(post("/internal/v1/fraud/check").contentType(MediaType.APPLICATION_JSON).content(CHECK))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/v1/fraud/check").with(role("SUPPORT_SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(CHECK))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fraud);
    }

    @Test
    void internalCheckAnswersTheDecisionAndTheCase() throws Exception {
        when(fraud.screenNow(any(), any(), eq("ret-1"))).thenReturn(new FraudService.Screening(check, Optional.of(openCase)));

        mvc.perform(post("/internal/v1/fraud/check").with(role("SERVICE")).contentType(MediaType.APPLICATION_JSON).content(CHECK))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("REVIEW"))
                .andExpect(jsonPath("$.score").value(50))
                .andExpect(jsonPath("$.reasons[0]").value("HIGH_REFUND_VALUE"))
                .andExpect(jsonPath("$.caseId").value(openCase.getCaseId().toString()));
    }

    @Test
    void invalidCheckIs400() throws Exception {
        String bad = CHECK.replace("\"currency\":\"USD\"", "\"currency\":\"usd\"").replace("600.00", "-1");
        mvc.perform(post("/internal/v1/fraud/check").with(role("SERVICE")).contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"));
    }

    @Test
    void agentsAndSupervisorsSeeCasesCustomersDoNot() throws Exception {
        when(fraud.cases(CaseStatus.OPEN, 50)).thenReturn(List.of(openCase));

        mvc.perform(get("/api/v1/fraud/cases").with(role("SUPPORT_AGENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].kind").value("REFUND"))
                .andExpect(jsonPath("$[0].reasons[0]").value("HIGH_REFUND_VALUE"));
        mvc.perform(get("/api/v1/fraud/cases").with(role("SUPPORT_SUPERVISOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/fraud/cases").with(role("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC-403"));
    }

    @Test
    void onlyASupervisorDecides() throws Exception {
        String approve = "{\"decision\":\"APPROVE\",\"note\":\"Called the customer, order is genuine\"}";
        mvc.perform(post("/api/v1/fraud/cases/{id}/decision", openCase.getCaseId()).with(role("SUPPORT_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fraud);

        openCase.decide(true, "user-SUPPORT_SUPERVISOR", "Called the customer, order is genuine", NOW);
        when(fraud.decide(eq(openCase.getCaseId()), eq(true), eq("user-SUPPORT_SUPERVISOR"), anyString(), anyString()))
                .thenReturn(openCase);
        mvc.perform(post("/api/v1/fraud/cases/{id}/decision", openCase.getCaseId()).with(role("SUPPORT_SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(approve))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value("user-SUPPORT_SUPERVISOR"));
        verify(fraud).decide(eq(openCase.getCaseId()), eq(true), eq("user-SUPPORT_SUPERVISOR"), anyString(), anyString());
    }

    @Test
    void decisionNeedsANoteAndAKnownValue() throws Exception {
        mvc.perform(post("/api/v1/fraud/cases/{id}/decision", UUID.randomUUID()).with(role("SUPPORT_SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"MAYBE\",\"note\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.length()").value(2));
    }

    @Test
    void decidingAClosedCaseIs409() throws Exception {
        when(fraud.decide(any(), anyBoolean(), anyString(), anyString(), any()))
                .thenThrow(new CaseNotOpenException("Case x is already CLOSED (the order was cancelled)"));
        mvc.perform(post("/api/v1/fraud/cases/{id}/decision", UUID.randomUUID()).with(role("SUPPORT_SUPERVISOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"REJECT\",\"note\":\"stolen card\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("FRD-409"));
    }
}
