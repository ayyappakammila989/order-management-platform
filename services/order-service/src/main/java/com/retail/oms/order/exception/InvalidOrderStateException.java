package com.retail.oms.order.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

public class InvalidOrderStateException extends OmsException {

    public InvalidOrderStateException(String message) {
        super("ORD-409", HttpStatus.CONFLICT, message);
    }
}
