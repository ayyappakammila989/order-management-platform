package com.retail.oms.inventory.application;

import com.retail.oms.common.web.OmsException;
import org.springframework.http.HttpStatus;

public class ReservationNotFoundException extends OmsException {

    public ReservationNotFoundException(String what) {
        super("INV-404", HttpStatus.NOT_FOUND, "No reservation " + what);
    }
}
