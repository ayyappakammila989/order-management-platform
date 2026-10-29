package com.retail.oms.order.api;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import com.retail.oms.order.application.IdempotentOrderCreator;
import com.retail.oms.order.application.OrderService;
import com.retail.oms.order.config.SecurityConfig;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderStatus;
import com.retail.oms.order.exception.IdempotencyInProgressException;
import com.retail.oms.order.exception.IdempotencyKeyReusedException;
import com.retail.oms.order.exception.InvalidOrderStateException;
import com.retail.oms.order.exception.OrderNotFoundException;
import com.retail.oms.order.exception.OrderTotalsMismatchException;
import com.retail.oms.order.infrastructure.persistence.OrderRepository;
import com.retail.oms.order.mapper.OrderApiMapperImpl;
import com.retail.oms.order.support.TestOrders;
import java.math.BigDecimal;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Controllers + security rules + error mapping (architecture 12.2), with the real SecurityConfig, the real
 * owner check and the shared exception handler; the application layer is mocked.
 */
@WebMvcTest(controllers = {OrderController.class, InternalOrderController.class})
@Import({SecurityConfig.class, OrderAccess.class, OrderApiMapperImpl.class})
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class OrderApiTest {

    static final String VALID_BODY = """
            {"customerId":"cust-1","channel":"WEB","currency":"USD",
             "lines":[{"sku":"SKU-1","quantity":2,"unitPrice":19.99,"discount":2.00,"tax":3.00,"fulfillmentType":"SHIP"},
                      {"sku":"SKU-2","quantity":1,"unitPrice":5.00,"tax":0.40,"fulfillmentType":"SHIP"}],
             "shippingAddress":{"name":"Jane Doe","line1":"1 Main St","city":"New York","state":"NY","zip":"10001"},
             "shippingTotal":4.99,"expectedGrandTotal":51.37}""";

    @Autowired MockMvc mvc;
    @MockitoBean OrderService orderService;
    @MockitoBean IdempotentOrderCreator creator;
    @MockitoBean OrderRepository orderRepository;   // used by the real OrderAccess

    Order order;

    @BeforeEach
    void setUp() {
        order = TestOrders.withLineIds(TestOrders.order("cust-1"));
        when(orderRepository.findCustomerIdById(order.getOrderId())).thenReturn(Optional.of("cust-1"));
        when(orderService.get(order.getOrderId())).thenReturn(order);
    }

    // ---- POST /internal/v1/orders ----

    @Test
    void serviceCreatesOrder201WithLocation() throws Exception {
        when(creator.create(eq("checkout-0001"), anyString(), any(), eq("cart-service")))
                .thenReturn(new OrderService.CreateResult(order, false));

        mvc.perform(createOrder("checkout-0001", VALID_BODY).with(service()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/orders/" + order.getOrderId()))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.orderNumber").value(order.getOrderNumber()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.grandTotal").value(51.37))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.shippingAddress.zip").value("10001"));
    }

    @Test
    void replayIsFlaggedInHeader() throws Exception {
        when(creator.create(anyString(), anyString(), any(), anyString()))
                .thenReturn(new OrderService.CreateResult(order, true));

        mvc.perform(createOrder("checkout-0001", VALID_BODY).with(service()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
    }

    @Test
    void sameBodyGivesSameFingerprint() throws Exception {
        when(creator.create(anyString(), anyString(), any(), anyString()))
                .thenReturn(new OrderService.CreateResult(order, false));
        mvc.perform(createOrder("checkout-0002", VALID_BODY).with(service()));
        mvc.perform(createOrder("checkout-0002", VALID_BODY.replace("\n", " ")).with(service()));

        org.mockito.ArgumentCaptor<String> fingerprints = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(creator, org.mockito.Mockito.times(2)).create(eq("checkout-0002"), fingerprints.capture(), any(), any());
        org.assertj.core.api.Assertions.assertThat(fingerprints.getAllValues().get(0))
                .hasSize(64).isEqualTo(fingerprints.getAllValues().get(1));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(createOrder(null, VALID_BODY).with(service()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDM-400"))
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));
        verify(creator, never()).create(any(), any(), any(), any());
    }

    @Test
    void malformedIdempotencyKeyIs400() throws Exception {
        mvc.perform(createOrder("short", VALID_BODY).with(service()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("IDM-400"));
    }

    @Test
    void invalidBodyIs400WithFieldList() throws Exception {
        String bad = VALID_BODY.replace("\"currency\":\"USD\"", "\"currency\":\"usd\"")
                .replace("\"quantity\":2", "\"quantity\":0")
                .replace("\"unitPrice\":19.99", "\"unitPrice\":19.999")
                .replace("\"zip\":\"10001\"", "\"zip\":\"\"");

        mvc.perform(createOrder("checkout-0003", bad).with(service()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"))
                .andExpect(jsonPath("$.fieldErrors[*].field", containsInAnyOrder(
                        "currency", "lines[0].quantity", "lines[0].unitPrice", "shippingAddress.zip")));
    }

    @Test
    void noTokenIs401InApiErrorFormat() throws Exception {
        mvc.perform(createOrder("checkout-0004", VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(jsonPath("$.errorCode").value("SEC-401"))
                .andExpect(jsonPath("$.path").value("/internal/v1/orders"));
    }

    @Test
    void customerCannotCallInternalApi() throws Exception {
        mvc.perform(createOrder("checkout-0005", VALID_BODY).with(customer("cust-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC-403"));
    }

    @Test
    void businessErrorsMapToStatusAndCode() throws Exception {
        expectError(new IdempotencyKeyReusedException(), 422, "IDM-422");
        expectError(new IdempotencyInProgressException(), 409, "IDM-409");
        expectError(new OrderTotalsMismatchException(new BigDecimal("50.00"), new BigDecimal("51.37")), 422, "ORD-422");
    }

    // ---- GET /api/v1/orders/{id} ----

    @Test
    void customerSeesOwnOrder() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}", order.getOrderId()).with(customer("cust-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.getOrderId().toString()))
                .andExpect(jsonPath("$.customerId").value("cust-1"));
    }

    @Test
    void customerCannotSeeSomeoneElsesOrder() throws Exception {
        mvc.perform(get("/api/v1/orders/{id}", order.getOrderId()).with(customer("cust-2")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC-403"));
        verify(orderService, never()).get(any());
    }

    @Test
    void agentSeesAnyOrderAndUnknownIdIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        when(orderService.get(unknown)).thenThrow(new OrderNotFoundException(unknown));

        mvc.perform(get("/api/v1/orders/{id}", order.getOrderId()).with(staff("SUPPORT_AGENT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/orders/{id}", unknown).with(staff("SUPPORT_AGENT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORD-404"));
    }

    @Test
    void notAUuidIs400() throws Exception {
        mvc.perform(get("/api/v1/orders/not-a-uuid").with(staff("SUPPORT_AGENT")))
                .andExpect(status().isBadRequest());
    }

    // ---- POST /api/v1/orders/{id}/cancel ----

    @Test
    void ownerCancels() throws Exception {
        when(orderService.cancel(order.getOrderId(), "changed my mind", "customer1")).thenReturn(order);

        mvc.perform(cancel(order.getOrderId(), "changed my mind").with(customer("cust-1")))
                .andExpect(status().isOk());
        verify(orderService).cancel(order.getOrderId(), "changed my mind", "customer1");
    }

    @Test
    void cancelAfterShipmentIs409() throws Exception {
        when(orderService.cancel(any(), anyString(), anyString()))
                .thenThrow(new InvalidOrderStateException("Order cannot be cancelled after shipment"));

        mvc.perform(cancel(order.getOrderId(), "too late").with(staff("SUPPORT_AGENT")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORD-409"))
                .andExpect(jsonPath("$.message").value("Order cannot be cancelled after shipment"))
                .andExpect(jsonPath("$.path").value("/api/v1/orders/" + order.getOrderId() + "/cancel"));
    }

    @Test
    void financeAndOpsCannotCancel() throws Exception {
        mvc.perform(cancel(order.getOrderId(), "x").with(staff("FINANCE"))).andExpect(status().isForbidden());
        mvc.perform(cancel(order.getOrderId(), "x").with(staff("OPS_ADMIN"))).andExpect(status().isForbidden());
    }

    @Test
    void cancelNeedsAReason() throws Exception {
        mvc.perform(cancel(order.getOrderId(), "").with(staff("SUPPORT_AGENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("reason"));
    }

    // ---- PATCH /internal/v1/orders/{id}/status ----

    @Test
    void sagaUpdatesStatus() throws Exception {
        when(orderService.changeStatus(order.getOrderId(), OrderStatus.CONFIRMED, "saga ok", "cart-service"))
                .thenReturn(order);

        mvc.perform(patch("/internal/v1/orders/{id}/status", order.getOrderId()).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CONFIRMED\",\"reason\":\"saga ok\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void unknownStatusValueIs400() throws Exception {
        mvc.perform(patch("/internal/v1/orders/{id}/status", order.getOrderId()).with(service())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"LOST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VAL-400"));
    }

    private void expectError(RuntimeException ex, int status, String code) throws Exception {
        org.mockito.Mockito.doThrow(ex).when(creator).create(anyString(), anyString(), any(), anyString());
        mvc.perform(createOrder("checkout-err-" + code, VALID_BODY).with(service()))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.errorCode").value(code))
                .andExpect(jsonPath("$.message").value(ex.getMessage()));
    }

    private static MockHttpServletRequestBuilder createOrder(String key, String body) {
        MockHttpServletRequestBuilder request = post("/internal/v1/orders")
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static MockHttpServletRequestBuilder cancel(UUID orderId, String reason) {
        return post("/api/v1/orders/{id}/cancel", orderId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"" + reason + "\"}");
    }

    private static JwtRequestPostProcessor customer(String sub) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", "customer1"))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private static JwtRequestPostProcessor staff(String role) {
        return jwt().jwt(j -> j.subject("staff-1").claim("preferred_username", "staff1"))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static JwtRequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("svc").claim("preferred_username", "service-account-cart-service")
                        .claim("azp", "cart-service"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }
}
