package com.retail.oms.inventory.application;

import com.retail.oms.common.web.OmsException;
import java.util.List;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class InsufficientStockException extends OmsException {

    private final List<Shortage> shortages;

    public InsufficientStockException(List<Shortage> shortages) {
        super("INV-409", HttpStatus.CONFLICT, "Not enough stock: " + shortages);
        this.shortages = shortages;
    }
}
