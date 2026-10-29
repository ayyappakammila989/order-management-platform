package com.retail.oms.cart.api;

import com.retail.oms.cart.api.dto.CartDtos.CartResponse;
import com.retail.oms.cart.api.dto.CartDtos.SavedCartResponse;
import com.retail.oms.cart.application.SavedCartService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/saved-carts")
@RequiredArgsConstructor
@Tag(name = "Saved carts")
public class SavedCartController {

    private final SavedCartService savedCarts;

    @GetMapping
    public List<SavedCartResponse> list(Authentication auth) {
        return savedCarts.list(auth.getName()).stream().map(SavedCartResponse::of).toList();
    }

    @PostMapping("/{savedCartId}/restore")
    public ResponseEntity<CartResponse> restore(@PathVariable UUID savedCartId, Authentication auth) {
        CartResponse body = CartResponse.of(savedCarts.restore(savedCartId, auth.getName()));
        return ResponseEntity.created(URI.create("/api/v1/carts/" + body.cartId())).body(body);
    }

    @DeleteMapping("/{savedCartId}")
    public ResponseEntity<Void> delete(@PathVariable UUID savedCartId, Authentication auth) {
        savedCarts.delete(savedCartId, auth.getName());
        return ResponseEntity.noContent().build();
    }
}
