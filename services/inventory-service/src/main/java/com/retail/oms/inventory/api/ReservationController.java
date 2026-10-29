package com.retail.oms.inventory.api;

import com.retail.oms.common.web.CorrelationId;
import com.retail.oms.inventory.api.dto.ReservationDtos.ReservationResponse;
import com.retail.oms.inventory.api.dto.ReservationDtos.ReserveRequestDto;
import com.retail.oms.inventory.application.InsufficientStockException;
import com.retail.oms.inventory.application.ReservationNotFoundException;
import com.retail.oms.inventory.application.ReservationService;
import com.retail.oms.inventory.application.ReservationService.ReserveOutcome;
import com.retail.oms.inventory.application.ReserveRequest;
import com.retail.oms.inventory.application.SagaContext;
import com.retail.oms.inventory.domain.ReleaseReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service (role SERVICE). The saga reserves over Kafka; this synchronous API is for support tools and
 * other services (architecture 2, API table). It sends no saga replies, only inventory.changed.
 */
@RestController
@RequestMapping("/internal/v1/reservations")
@Tag(name = "Reservations (internal)")
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    @PostMapping
    @Operation(summary = "Reserve all lines or nothing (409 INV-409 lists the shortages). Again for the same order: the same reservation.")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequestDto body) {
        var request = new ReserveRequest(body.orderId(), body.shipToZip(),
                body.lines().stream().map(l -> new ReserveRequest.Line(l.sku(), l.quantity())).toList());
        ReserveOutcome outcome = reservations.reserve(request, SagaContext.none(MDC.get(CorrelationId.MDC_KEY)));
        return switch (outcome) {
            case ReserveOutcome.Reserved r -> {
                var response = ReservationResponse.of(r.reservation());
                yield r.existing() ? ResponseEntity.ok(response)
                        : ResponseEntity.created(URI.create("/internal/v1/reservations/" + response.reservationId())).body(response);
            }
            case ReserveOutcome.Rejected r -> throw new InsufficientStockException(r.shortages());
            case ReserveOutcome.Duplicate d -> throw new IllegalStateException("REST calls carry no command id");
        };
    }

    @GetMapping("/{reservationId}")
    public ReservationResponse get(@PathVariable UUID reservationId) {
        return reservations.find(reservationId).map(ReservationResponse::of)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId.toString()));
    }

    @GetMapping
    @Operation(summary = "All reservations of an order, newest first")
    public List<ReservationResponse> byOrder(@RequestParam UUID orderId) {
        return reservations.findByOrder(orderId).stream().map(ReservationResponse::of).toList();
    }

    @DeleteMapping("/{reservationId}")
    @Operation(summary = "Release (idempotent: an already released reservation is returned unchanged)")
    public ReservationResponse release(@PathVariable UUID reservationId) {
        return ReservationResponse.of(reservations.releaseById(reservationId, ReleaseReason.MANUAL,
                MDC.get(CorrelationId.MDC_KEY)));
    }
}
