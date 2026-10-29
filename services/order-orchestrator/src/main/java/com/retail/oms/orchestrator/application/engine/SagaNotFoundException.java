package com.retail.oms.orchestrator.application.engine;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

public class SagaNotFoundException extends OmsException {

    public SagaNotFoundException(String what) {
        super("SAG-404", HttpStatus.NOT_FOUND, "No saga " + what);
    }
}
