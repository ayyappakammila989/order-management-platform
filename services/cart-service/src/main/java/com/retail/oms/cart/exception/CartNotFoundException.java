package com.retail.oms.cart.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** Also used for somebody else's cart, so cart ids cannot be probed. */
public class CartNotFoundException extends OmsException {

    public CartNotFoundException(String cartId) {
        super("CRT-404", HttpStatus.NOT_FOUND, "Cart " + cartId + " not found");
    }
}
