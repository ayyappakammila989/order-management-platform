package com.retail.oms.order.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddressRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 200) String line1,
        @NotBlank @Size(max = 100) String city,
        @NotBlank @Size(max = 50) String state,
        @NotBlank @Size(max = 20) String zip) {

    @Override
    public String toString() {
        return "AddressRequest[zip=" + zip + "]";
    }
}
