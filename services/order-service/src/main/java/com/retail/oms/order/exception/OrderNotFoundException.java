package com.retail.oms.order.exception;

import com.retail.oms.common.web.OmsException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends OmsException {

    public OrderNotFoundException(UUID orderId) {
        super("ORD-404", HttpStatus.NOT_FOUND, "Order " + orderId + " not found");
    }
}
