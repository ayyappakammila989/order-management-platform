package com.retail.oms.order.exception;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** Same Idempotency-Key, different request body: almost always a client bug, so refuse instead of guessing. */
public class IdempotencyKeyReusedException extends OmsException {

    public IdempotencyKeyReusedException() {
        super("IDM-422", HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency-Key was already used with a different request body");
    }
}
