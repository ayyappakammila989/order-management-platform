package com.retail.oms.inventory.api;

import com.retail.oms.inventory.application.Availability;
import com.retail.oms.inventory.application.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
@Validated
@Tag(name = "Availability")
public class AvailabilityController {

    private final AvailabilityService availability;

    public AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/{sku}/availability")
    @Operation(summary = "Available-to-promise for a SKU, nearest locations to the ZIP first. A hint, not a reservation.")
    public Availability get(@PathVariable @Size(max = 40) String sku,
            @RequestParam(required = false) @Pattern(regexp = "\\d{5}") String zip) {
        return availability.availability(sku, zip);
    }
}
