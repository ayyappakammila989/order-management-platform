package com.retail.oms.query.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import com.retail.oms.query.application.OrderDocument;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.application.OrderSearch;
import com.retail.oms.query.config.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;

@WebMvcTest(OrderQueryController.class)
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class OrderQueryApiTest {

    @Autowired MockMvc mvc;
    @MockitoBean OrderIndex index;

    static RequestPostProcessor as(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    OrderDocument doc(String orderId, String customerId) {
        return new OrderDocument(orderId, "OMS-20261029-000001", "PENDING", "WEB", "USD", "10001",
                new OrderDocument.Customer(customerId, null, null),
                new OrderDocument.Totals(new BigDecimal("39.98"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("45.97")),
                List.of(), List.of(), List.of(), "2026-10-29T10:00:00Z", "2026-10-29T10:00:00Z");
    }

    @Test
    void needsAToken() throws Exception {
        mvc.perform(get("/api/v1/order-query/orders/o-1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/order-query/orders")).andExpect(status().isUnauthorized());
    }

    @Test
    void customerSeesOwnOrder() throws Exception {
        when(index.find("o-1")).thenReturn(Optional.of(doc("o-1", "cust-1")));

        mvc.perform(get("/api/v1/order-query/orders/o-1").with(as("cust-1", "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNumber").value("OMS-20261029-000001"))
                .andExpect(jsonPath("$.totals.grandTotal").value(45.97));
    }

    @Test
    void customerAskingForSomeoneElsesOrderGetsTheSame404AsForAnUnknownOne() throws Exception {
        when(index.find("o-1")).thenReturn(Optional.of(doc("o-1", "cust-1")));
        when(index.find("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/order-query/orders/o-1").with(as("cust-2", "CUSTOMER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("QRY-404"));
        mvc.perform(get("/api/v1/order-query/orders/nope").with(as("cust-2", "CUSTOMER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("QRY-404"));
    }

    @Test
    void staffSeeAnyOrder() throws Exception {
        when(index.find("o-1")).thenReturn(Optional.of(doc("o-1", "cust-1")));

        mvc.perform(get("/api/v1/order-query/orders/o-1").with(as("agent-1", "SUPPORT_AGENT"))).andExpect(status().isOk());
    }

    @Test
    void customerSearchIsAlwaysLimitedToTheirOwnOrdersWhateverTheyAsk() throws Exception {
        when(index.search(any())).thenReturn(new OrderSearch.Page(List.of(), 0, 0, 20));

        mvc.perform(get("/api/v1/order-query/orders").param("customerId", "cust-2").param("q", "OMS-2026")
                .with(as("cust-1", "CUSTOMER"))).andExpect(status().isOk());

        ArgumentCaptor<OrderSearch> captor = ArgumentCaptor.forClass(OrderSearch.class);
        verify(index).search(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo("cust-1");
        assertThat(captor.getValue().q()).isEqualTo("OMS-2026");
    }

    @Test
    void staffSearchCanFilterByAnyCustomerAndStatus() throws Exception {
        when(index.search(any())).thenReturn(new OrderSearch.Page(List.of(doc("o-1", "cust-9")), 1, 0, 20));

        mvc.perform(get("/api/v1/order-query/orders").param("customerId", "cust-9").param("status", "pending")
                .with(as("agent-1", "SUPPORT_AGENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].customer.id").value("cust-9"));

        ArgumentCaptor<OrderSearch> captor = ArgumentCaptor.forClass(OrderSearch.class);
        verify(index).search(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo("cust-9");
        assertThat(captor.getValue().status()).isEqualTo("pending");
    }

    @Test
    void pageSizeIsLimited() throws Exception {
        mvc.perform(get("/api/v1/order-query/orders").param("size", "1000").with(as("agent-1", "SUPPORT_AGENT")))
                .andExpect(status().isBadRequest());
    }
}
