package com.retail.oms.inventory.application;

import java.util.List;
import java.util.UUID;

public record ReserveRequest(UUID orderId, String shipToZip, List<Line> lines) {

    public record Line(String sku, int quantity) {
    }
}
