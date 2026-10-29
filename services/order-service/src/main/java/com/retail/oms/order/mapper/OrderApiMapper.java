package com.retail.oms.order.mapper;

import com.retail.oms.order.api.dto.AddressRequest;
import com.retail.oms.order.api.dto.CreateOrderRequest;
import com.retail.oms.order.api.dto.OrderLineRequest;
import com.retail.oms.order.api.dto.OrderResponse;
import com.retail.oms.order.application.CreateOrderCommand;
import com.retail.oms.order.domain.Address;
import com.retail.oms.order.domain.AddressType;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderAddress;
import com.retail.oms.order.domain.OrderLine;
import java.util.ArrayList;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/** API DTOs <-> domain. Generated at compile time; unmapped target fields fail the build. */
@Mapper(imports = AddressType.class)
public interface OrderApiMapper {

    @Mapping(target = "shippingAddress", expression = "java(address(order, AddressType.SHIP))")
    @Mapping(target = "billingAddress", expression = "java(address(order, AddressType.BILL))")
    OrderResponse toResponse(Order order);

    OrderResponse.Line toLine(OrderLine line);

    OrderResponse.Address toAddress(OrderAddress address);

    @Mapping(target = "discount", source = "discount", defaultValue = "0.00")
    @Mapping(target = "tax", source = "tax", defaultValue = "0.00")
    Order.NewLine toNewLine(OrderLineRequest line);

    List<Order.NewLine> toNewLines(List<OrderLineRequest> lines);

    default CreateOrderCommand toCommand(CreateOrderRequest request) {
        List<Address> addresses = new ArrayList<>();
        addresses.add(toDomain(request.shippingAddress(), AddressType.SHIP));
        if (request.billingAddress() != null) {
            addresses.add(toDomain(request.billingAddress(), AddressType.BILL));
        }
        return new CreateOrderCommand(request.customerId(), request.channel(), request.currency(),
                toNewLines(request.lines()), addresses, request.shippingTotal(), request.expectedGrandTotal());
    }

    default Address toDomain(AddressRequest a, AddressType type) {
        return new Address(type, a.name(), a.line1(), a.city(), a.state(), a.zip());
    }

    default OrderResponse.Address address(Order order, AddressType type) {
        return order.address(type).map(this::toAddress).orElse(null);
    }
}
