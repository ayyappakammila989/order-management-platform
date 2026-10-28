package com.retail.oms.common.web;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base class for business errors. Services subclass it, e.g.
 * {@code OrderNotFoundException extends OmsException} with ("ORD-404", NOT_FOUND).
 */
@Getter
public class OmsException extends RuntimeException {

    private final String errorCode;
    private final HttpStatus httpStatus;

    public OmsException(String errorCode, HttpStatus httpStatus, String message) {
        super(message);
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
    }
}
