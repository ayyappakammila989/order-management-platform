package com.retail.oms.cart.infrastructure.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/** inventory-service, found in Eureka by name (set oms.inventory-service.url to bypass). */
@FeignClient(name = "inventory-service", url = "${oms.inventory-service.url:}")
public interface InventoryClient {

    @GetMapping("/api/v1/inventory/{sku}/availability")
    Availability availability(@PathVariable("sku") String sku, @RequestParam(name = "zip", required = false) String zip);

    record Availability(String sku, String zip, int available) {
    }
}
