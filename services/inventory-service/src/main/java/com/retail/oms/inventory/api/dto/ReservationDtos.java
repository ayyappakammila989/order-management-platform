package com.retail.oms.inventory.api.dto;

import com.retail.oms.inventory.domain.Reservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReservationDtos {

    private ReservationDtos() {
    }

    public record ReserveLineRequest(@NotBlank @Size(max = 40) String sku, @Min(1) @Max(999) int quantity) {
    }

    public record ReserveRequestDto(@NotNull UUID orderId, @Pattern(regexp = "\\d{5}") String shipToZip,
                                    @NotEmpty @Size(max = 50) List<@Valid ReserveLineRequest> lines) {
    }

    public record LineResponse(String sku, String locationId, int quantity) {
    }

    public record ReservationResponse(UUID reservationId, UUID orderId, String status, Instant expiresAt,
                                      String releaseReason, Instant createdAt, List<LineResponse> lines) {

        public static ReservationResponse of(Reservation r) {
            return new ReservationResponse(r.getReservationId(), r.getOrderId(), r.getStatus().name(), r.getExpiresAt(),
                    r.getReleaseReason() == null ? null : r.getReleaseReason().name(), r.getCreatedAt(),
                    r.getLines().stream().map(l -> new LineResponse(l.getSku(), l.getLocationId(), l.getQuantity())).toList());
        }
    }
}
