package com.retail.oms.fraud.application;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

/** Already decided, or closed because the order was cancelled meanwhile. */
public class CaseNotOpenException extends OmsException {

    public CaseNotOpenException(String message) {
        super("FRD-409", HttpStatus.CONFLICT, message);
    }
}
