package com.retail.oms.order.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.common.web.OmsException;
import com.retail.oms.common.web.security.OmsJwt;
import com.retail.oms.order.api.dto.CreateOrderRequest;
import com.retail.oms.order.api.dto.OrderResponse;
import com.retail.oms.order.api.dto.StatusUpdateRequest;
import com.retail.oms.order.application.IdempotentOrderCreator;
import com.retail.oms.order.application.OrderService;
import com.retail.oms.order.mapper.OrderApiMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service API (role SERVICE). api-gateway blocks /internal/** from outside. */
@RestController
@RequestMapping("/internal/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders (internal)")
public class InternalOrderController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";

    private final IdempotentOrderCreator creator;
    private final OrderService orderService;
    private final OrderApiMapper mapper;
    private final ObjectMapper objectMapper;

    @PostMapping
    @Operation(summary = "Create an order from checkout. Retrying with the same Idempotency-Key returns the same order.")
    public ResponseEntity<OrderResponse> create(
            @Parameter(description = "Unique per checkout attempt, e.g. a UUID; 8-100 characters")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request, Authentication authentication) {
        validateKey(idempotencyKey);
        OrderService.CreateResult result = creator.create(idempotencyKey, fingerprint(request),
                mapper.toCommand(request), OmsJwt.actor(authentication));
        OrderResponse body = mapper.toResponse(result.order());
        return ResponseEntity.created(URI.create("/api/v1/orders/" + body.orderId()))
                .header(REPLAYED, String.valueOf(result.replayed()))
                .body(body);
    }

    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Saga status update. Sending the current status again is a no-op (safe retries).")
    public OrderResponse updateStatus(@PathVariable UUID orderId, @Valid @RequestBody StatusUpdateRequest request,
            Authentication authentication) {
        return mapper.toResponse(orderService.changeStatus(orderId, request.status(), request.reason(),
                OmsJwt.actor(authentication)));
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new OmsException("IDM-400", HttpStatus.BAD_REQUEST, IDEMPOTENCY_KEY + " header is required");
        }
        if (key.length() < 8 || key.length() > 100 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new OmsException("IDM-400", HttpStatus.BAD_REQUEST,
                    IDEMPOTENCY_KEY + " must be 8-100 characters of letters, digits, '.', '_', ':' or '-'");
        }
    }

    /** SHA-256 of the request as JSON: the same key must always come with the same body. */
    private String fingerprint(CreateOrderRequest request) {
        try {
            byte[] json = objectMapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot fingerprint request", e);
        }
    }
}
