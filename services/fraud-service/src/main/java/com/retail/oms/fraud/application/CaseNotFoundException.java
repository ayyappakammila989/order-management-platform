package com.retail.oms.fraud.application;

import com.retail.oms.common.web.OmsException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class CaseNotFoundException extends OmsException {

    public CaseNotFoundException(UUID caseId) {
        super("FRD-404", HttpStatus.NOT_FOUND, "No fraud case " + caseId);
    }
}
