package com.retail.oms.cart.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

public class CartConflictException extends OmsException {

    public CartConflictException() {
        super("CRT-409", HttpStatus.CONFLICT, "The cart is being changed by another request, please try again");
    }
}
