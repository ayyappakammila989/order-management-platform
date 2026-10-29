package com.retail.oms.order.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** A request with the same key is still running; the client should retry shortly (same key). */
public class IdempotencyInProgressException extends OmsException {

    public IdempotencyInProgressException() {
        super("IDM-409", HttpStatus.CONFLICT, "A request with this Idempotency-Key is still being processed");
    }
}
