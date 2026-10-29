package com.retail.oms.query.api;

import com.retail.oms.common.web.OmsException;
import com.retail.oms.query.application.OrderDocument;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.application.OrderSearch;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff can look up and search any order. A customer only ever sees their own: the customer filter comes from the
 * token (a customerId parameter is ignored), and someone else's order id answers 404 like an unknown one.
 */
@RestController
@RequestMapping("/api/v1/order-query/orders")
@RequiredArgsConstructor
@Validated
@Tag(name = "Order search")
public class OrderQueryController {

    static final Set<String> STAFF_ROLES = Set.of("ROLE_SUPPORT_AGENT", "ROLE_SUPPORT_SUPERVISOR", "ROLE_OPS_ADMIN",
            "ROLE_FINANCE", "ROLE_SERVICE");

    private final OrderIndex index;

    @GetMapping("/{orderId}")
    @Operation(summary = "One order as the read model sees it (it can lag the order database by about a second)")
    public OrderDocument get(@PathVariable String orderId, Authentication auth) {
        OrderDocument doc = index.find(orderId).orElseThrow(() -> notFound(orderId));
        if (!isStaff(auth) && (doc.customer() == null || !auth.getName().equals(doc.customer().id()))) {
            throw notFound(orderId);
        }
        return doc;
    }

    @GetMapping
    @Operation(summary = "Search orders, newest first. q = order number (or its start), order id or SKU.")
    public OrderSearch.Page search(@RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(500) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size, Authentication auth) {
        String customerFilter = isStaff(auth) ? customerId : auth.getName();
        return index.search(new OrderSearch(q, status, customerFilter, page, size));
    }

    private static boolean isStaff(Authentication auth) {
        return auth.getAuthorities().stream().anyMatch(a -> STAFF_ROLES.contains(a.getAuthority()));
    }

    private static OmsException notFound(String orderId) {
        return new OmsException("QRY-404", HttpStatus.NOT_FOUND, "Order " + orderId + " not found");
    }
}
