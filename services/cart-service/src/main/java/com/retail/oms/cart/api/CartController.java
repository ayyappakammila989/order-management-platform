package com.retail.oms.cart.api;

import com.retail.oms.cart.api.dto.CartDtos.AddItemRequest;
import com.retail.oms.cart.api.dto.CartDtos.CartResponse;
import com.retail.oms.cart.api.dto.CartDtos.ChangeQuantityRequest;
import com.retail.oms.cart.api.dto.CartDtos.CheckoutRequest;
import com.retail.oms.cart.api.dto.CartDtos.CheckoutResponse;
import com.retail.oms.cart.api.dto.CartDtos.CreateCartRequest;
import com.retail.oms.cart.api.dto.CartDtos.SaveCartRequest;
import com.retail.oms.cart.api.dto.CartDtos.SavedCartResponse;
import com.retail.oms.cart.application.CartService;
import com.retail.oms.cart.application.CheckoutService;
import com.retail.oms.cart.application.SavedCartService;
import com.retail.oms.cart.infrastructure.client.OrderClient;
import com.retail.oms.common.web.OmsException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's carts. The customer id always comes from the token, never from the request. */
@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
@Tag(name = "Carts")
public class CartController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final CartService carts;
    private final CheckoutService checkout;
    private final SavedCartService savedCarts;

    @PostMapping
    @Operation(summary = "Create an empty cart")
    public ResponseEntity<CartResponse> create(@RequestBody(required = false) @Valid CreateCartRequest request,
            Authentication auth) {
        CartResponse body = CartResponse.of(carts.create(auth.getName(), request == null ? null : request.currency()));
        return ResponseEntity.created(URI.create("/api/v1/carts/" + body.cartId())).body(body);
    }

    @GetMapping("/{cartId}")
    public CartResponse get(@PathVariable String cartId, Authentication auth) {
        return CartResponse.of(carts.get(cartId, auth.getName()));
    }

    @PostMapping("/{cartId}/items")
    @Operation(summary = "Add an item; adding a SKU that is already there increases its quantity")
    public CartResponse addItem(@PathVariable String cartId, @Valid @RequestBody AddItemRequest request,
            Authentication auth) {
        return CartResponse.of(carts.addItem(cartId, auth.getName(), request.sku(), request.quantity()));
    }

    @PutMapping("/{cartId}/items/{itemId}")
    @Operation(summary = "Set the quantity of one item")
    public CartResponse changeQuantity(@PathVariable String cartId, @PathVariable String itemId,
            @Valid @RequestBody ChangeQuantityRequest request, Authentication auth) {
        return CartResponse.of(carts.changeQuantity(cartId, auth.getName(), itemId, request.quantity()));
    }

    @DeleteMapping("/{cartId}/items/{itemId}")
    public CartResponse removeItem(@PathVariable String cartId, @PathVariable String itemId, Authentication auth) {
        return CartResponse.of(carts.removeItem(cartId, auth.getName(), itemId));
    }

    @PostMapping("/{cartId}/checkout")
    @Operation(summary = "Price the cart and place the order. Retrying with the same Idempotency-Key returns the same order.")
    public ResponseEntity<CheckoutResponse> checkout(@PathVariable String cartId,
            @Parameter(description = "Unique per checkout attempt, e.g. a UUID; 8-100 characters")
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request, Authentication auth) {
        validateKey(idempotencyKey);
        var address = new OrderClient.OrderRequest.Address(request.shippingAddress().name(),
                request.shippingAddress().line1(), request.shippingAddress().city(), request.shippingAddress().state(),
                request.shippingAddress().zip());
        CheckoutService.Result result = checkout.checkout(new CheckoutService.Command(cartId, auth.getName(),
                idempotencyKey, request.channel() == null ? "WEB" : request.channel(), request.couponCode(), address,
                new OrderClient.OrderRequest.PaymentMethod(request.paymentMethod().gateway(),
                        request.paymentMethod().methodToken())));
        var order = result.order();
        return ResponseEntity.created(URI.create("/api/v1/orders/" + order.orderId()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(new CheckoutResponse(order.orderId(), order.orderNumber(), order.status(), order.currency(),
                        order.grandTotal(), result.replayed()));
    }

    @PostMapping("/{cartId}/save")
    @Operation(summary = "Save the cart for later (kept in PostgreSQL, unlike active carts which expire)")
    public ResponseEntity<SavedCartResponse> save(@PathVariable String cartId,
            @Valid @RequestBody SaveCartRequest request, Authentication auth) {
        SavedCartResponse body = SavedCartResponse.of(savedCarts.save(cartId, auth.getName(), request.name()));
        return ResponseEntity.created(URI.create("/api/v1/saved-carts/" + body.savedCartId())).body(body);
    }

    static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new OmsException("IDM-400", HttpStatus.BAD_REQUEST, IDEMPOTENCY_KEY + " header is required");
        }
        if (key.length() < 8 || key.length() > 100 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw new OmsException("IDM-400", HttpStatus.BAD_REQUEST,
                    IDEMPOTENCY_KEY + " must be 8-100 characters of letters, digits, '.', '_', ':' or '-'");
        }
    }
}
