package com.retail.oms.orchestrator.api;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import com.retail.oms.orchestrator.application.engine.SagaEngine;
import com.retail.oms.orchestrator.config.SecurityConfig;
import com.retail.oms.orchestrator.domain.OrderInfo;
import com.retail.oms.orchestrator.domain.SagaData;
import com.retail.oms.orchestrator.domain.SagaInstance;
import com.retail.oms.orchestrator.domain.SagaStepLog;
import com.retail.oms.orchestrator.domain.StepOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SagaController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class SagaApiTest {

    @Autowired MockMvc mvc;
    @MockitoBean SagaEngine engine;

    static final Instant T = Instant.parse("2026-10-29T10:00:00Z");

    SagaInstance saga() {
        var order = new OrderInfo(UUID.randomUUID().toString(), "OMS-20261029-000042", "cust-1", "10001", "USD", "45.97",
                List.of(new OrderInfo.Line("SKU-1", 2)));
        var s = new SagaInstance(UUID.fromString(order.orderId()), "ORDER_PLACEMENT",
                SagaData.start(order).withResult("reservationId", "r-1"), T);
        when(engine.find(s.getSagaId())).thenReturn(Optional.of(s));
        when(engine.findByOrder(s.getOrderId())).thenReturn(Optional.of(s));
        when(engine.steps(s.getSagaId())).thenReturn(List.of(
                new SagaStepLog(s.getSagaId(), "SAGA", StepOutcome.STARTED, null, T),
                new SagaStepLog(s.getSagaId(), "RESERVE_STOCK", StepOutcome.STARTED, null, T)));
        return s;
    }

    @Test
    void withoutTokenIs401() throws Exception {
        mvc.perform(get("/internal/v1/sagas/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void customerIs403() throws Exception {
        mvc.perform(get("/internal/v1/sagas/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportAgentSeesStateAndStepsByOrder() throws Exception {
        var s = saga();
        mvc.perform(get("/internal/v1/sagas").param("orderId", s.getOrderId().toString())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.orderNumber").value("OMS-20261029-000042"))
                .andExpect(jsonPath("$.results.reservationId").value("r-1"))
                .andExpect(jsonPath("$.steps[1].step").value("RESERVE_STOCK"));
    }

    @Test
    void unknownSagaIs404() throws Exception {
        mvc.perform(get("/internal/v1/sagas/" + UUID.randomUUID())
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SAG-404"));
    }
}
