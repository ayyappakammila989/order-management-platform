package com.retail.oms.order.exception;

import com.retail.oms.common.web.OmsException;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;

/** The caller (cart-service) priced the cart to a different total than the lines add up to. */
public class OrderTotalsMismatchException extends OmsException {

    public OrderTotalsMismatchException(BigDecimal expected, BigDecimal calculated) {
        super("ORD-422", HttpStatus.UNPROCESSABLE_ENTITY,
                "Expected grand total " + expected + " but the lines add up to " + calculated);
    }
}
