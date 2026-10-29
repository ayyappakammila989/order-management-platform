package com.retail.oms.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.retail.oms.common.web.OmsCommonWebAutoConfiguration;
import com.retail.oms.inventory.application.Availability;
import com.retail.oms.inventory.application.AvailabilityService;
import com.retail.oms.inventory.application.ReservationNotFoundException;
import com.retail.oms.inventory.application.ReservationService;
import com.retail.oms.inventory.application.ReservationService.ReserveOutcome;
import com.retail.oms.inventory.application.Shortage;
import com.retail.oms.inventory.config.SecurityConfig;
import com.retail.oms.inventory.domain.ReleaseReason;
import com.retail.oms.inventory.domain.Reservation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(controllers = {AvailabilityController.class, ReservationController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration(OmsCommonWebAutoConfiguration.class)
class InventoryApiTest {

    static final UUID ORDER = UUID.fromString("7a1f1c1e-0000-4000-8000-000000000001");
    static final String RESERVE = """
            {"orderId":"%s","shipToZip":"10001","lines":[{"sku":"SKU-1","quantity":2}]}""".formatted(ORDER);

    @Autowired MockMvc mvc;
    @MockitoBean AvailabilityService availability;
    @MockitoBean ReservationService reservations;

    static RequestPostProcessor service() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    static RequestPostProcessor customer() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    static Reservation reservation() {
        var r = new Reservation(ORDER, null, Instant.parse("2026-10-29T10:30:00Z"), Instant.parse("2026-10-29T10:00:00Z"));
        r.addLine("SKU-1", "STORE-NYC", 2);
        return r;
    }

    @Test
    void availabilityNeedsAToken() throws Exception {
        mvc.perform(get("/api/v1/inventory/SKU-1/availability")).andExpect(status().isUnauthorized());
        verifyNoInteractions(availability);
    }

    @Test
    void customerSeesAvailability() throws Exception {
        when(availability.availability("SKU-1", "10001")).thenReturn(new Availability("SKU-1", "10001", 8,
                List.of(new Availability.LocationAvailability("STORE-NYC", "STORE", 8))));

        mvc.perform(get("/api/v1/inventory/SKU-1/availability").param("zip", "10001").with(customer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(8))
                .andExpect(jsonPath("$.locations[0].locationId").value("STORE-NYC"));
    }

    @Test
    void badZipIs400() throws Exception {
        mvc.perform(get("/api/v1/inventory/SKU-1/availability").param("zip", "1000x").with(customer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCannotReserve() throws Exception {
        mvc.perform(post("/internal/v1/reservations").with(customer()).contentType(MediaType.APPLICATION_JSON).content(RESERVE))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SEC-403"));
        verifyNoInteractions(reservations);
    }

    @Test
    void serviceReservesAndGets201() throws Exception {
        when(reservations.reserve(any(), any())).thenReturn(new ReserveOutcome.Reserved(reservation(), false));

        mvc.perform(post("/internal/v1/reservations").with(service()).contentType(MediaType.APPLICATION_JSON).content(RESERVE))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.lines[0].locationId").value("STORE-NYC"));
    }

    @Test
    void notEnoughStockIs409WithTheShortage() throws Exception {
        when(reservations.reserve(any(), any())).thenReturn(new ReserveOutcome.Rejected(List.of(new Shortage("SKU-1", 2, 1))));

        mvc.perform(post("/internal/v1/reservations").with(service()).contentType(MediaType.APPLICATION_JSON).content(RESERVE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INV-409"))
                .andExpect(jsonPath("$.message").value("Not enough stock: [SKU-1 requested 2, available 1]"));
    }

    @Test
    void invalidQuantityIs400() throws Exception {
        String body = RESERVE.replace("\"quantity\":2", "\"quantity\":0");
        mvc.perform(post("/internal/v1/reservations").with(service()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reservations);
    }

    @Test
    void releasingAnUnknownReservationIs404() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservations.releaseById(eq(id), eq(ReleaseReason.MANUAL), any())).thenThrow(new ReservationNotFoundException(id.toString()));

        mvc.perform(delete("/internal/v1/reservations/" + id).with(service()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INV-404"));
    }
}
