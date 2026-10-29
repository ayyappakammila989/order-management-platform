package com.retail.oms.order.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.common.web.GlobalExceptionHandler;
import com.retail.oms.order.api.InternalOrderController;
import com.retail.oms.order.application.CreateOrderCommand;
import com.retail.oms.order.application.IdempotentOrderCreator;
import com.retail.oms.order.application.OrderService;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.exception.OrderTotalsMismatchException;
import com.retail.oms.order.mapper.OrderApiMapperImpl;
import com.retail.oms.order.support.TestOrders;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * Base class of the tests generated from src/test/resources/contracts. The controller, validation, mapping and
 * error format are real; only order creation is replaced by a stand-in that applies the real domain rules
 * (Order.place computes the totals) without a database. Security is covered by OrderApiTest/KeycloakSecurityIT.
 */
public abstract class ContractVerifierBase {

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
        OrderApiMapperImpl mapper = new OrderApiMapperImpl();
        IdempotentOrderCreator creator = mock(IdempotentOrderCreator.class);
        when(creator.create(anyString(), anyString(), any(), anyString())).thenAnswer(inv -> {
            CreateOrderCommand command = inv.getArgument(2);
            Order order = TestOrders.withLineIds(Order.place(UUID.randomUUID(), "OMS-20261029-000001",
                    command.customerId(), command.channel(), command.currency(), command.lines(), command.addresses(),
                    command.shippingTotal(), inv.getArgument(0), inv.getArgument(3), TestOrders.NOW));
            if (command.expectedGrandTotal() != null
                    && command.expectedGrandTotal().compareTo(order.getGrandTotal()) != 0) {
                throw new OrderTotalsMismatchException(command.expectedGrandTotal(), order.getGrandTotal());
            }
            return new OrderService.CreateResult(order, false);
        });
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        JwtAuthenticationToken caller = new JwtAuthenticationToken(new Jwt("t", Instant.now(),
                Instant.now().plusSeconds(60), Map.of("alg", "none"), Map.of("sub", "svc", "azp", "cart-service")));

        RestAssuredMockMvc.standaloneSetup(MockMvcBuilders
                .standaloneSetup(new InternalOrderController(creator, mock(OrderService.class), mapper, objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setValidator(validator)
                .defaultRequest(post("/").principal(caller)));
    }
}
