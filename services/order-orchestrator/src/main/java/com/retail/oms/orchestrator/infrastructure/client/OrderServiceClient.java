package com.retail.oms.orchestrator.infrastructure.client;

import java.util.UUID;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/** Found through Eureka as "order-service" unless oms.order-service.url is set. */
@FeignClient(name = "order-service", url = "${oms.order-service.url:}", configuration = OrderServiceFeignConfig.class)
public interface OrderServiceClient {

    record StatusUpdate(String status, String reason) {
    }

    @PatchMapping("/internal/v1/orders/{orderId}/status")
    void updateStatus(@PathVariable("orderId") UUID orderId, @RequestBody StatusUpdate body);
}
