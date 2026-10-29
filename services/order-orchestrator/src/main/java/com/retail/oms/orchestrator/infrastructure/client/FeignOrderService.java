package com.retail.oms.orchestrator.infrastructure.client;

import com.retail.oms.orchestrator.application.OrderRejectedException;
import com.retail.oms.orchestrator.application.OrderServicePort;
import com.retail.oms.orchestrator.application.OrderServiceUnavailableException;
import feign.FeignException;
import feign.RetryableException;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class FeignOrderService implements OrderServicePort {

    private final OrderServiceClient client;

    public FeignOrderService(OrderServiceClient client) {
        this.client = client;
    }

    @Override
    public void changeStatus(UUID orderId, String status, String reason) {
        try {
            client.updateStatus(orderId, new OrderServiceClient.StatusUpdate(status, reason));
        } catch (OrderRejectedException | OrderServiceUnavailableException e) {
            throw e;
        } catch (RetryableException e) {
            throw new OrderServiceUnavailableException("order-service not reachable: " + e.getMessage(), e);
        } catch (FeignException e) {
            throw new OrderServiceUnavailableException("order-service call failed: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            // e.g. "No instances available for order-service" from the load balancer
            throw new OrderServiceUnavailableException(e.getMessage(), e);
        }
    }
}
