package com.retail.oms.cart.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** A request that is well formed but not allowed on this cart (empty cart, too many lines, ...). */
public class CartRuleException extends OmsException {

    public CartRuleException(String message) {
        super("CRT-422", HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
