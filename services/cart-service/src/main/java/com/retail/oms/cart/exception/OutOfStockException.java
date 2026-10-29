package com.retail.oms.cart.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** The availability pre-check says a line cannot be delivered; the customer can change the cart and try again. */
public class OutOfStockException extends OmsException {

    public OutOfStockException(String message) {
        super("CRT-409", HttpStatus.CONFLICT, message);
    }
}
