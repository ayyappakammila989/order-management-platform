package com.retail.oms.query.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonData;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import com.retail.oms.query.application.OrderDocument;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.application.OrderSearch;
import com.retail.oms.query.config.QueryProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Writes are scripted upserts: Elasticsearch runs a small Painless script inside the document update, so there is no
 * read-modify-write race, a repeated event changes nothing (history entries are keyed by event id) and an older
 * status can never overwrite a newer one (compared by the event time).
 */
@Component
@DependsOn("ordersIndexInitializer")
@RequiredArgsConstructor
public class ElasticsearchOrderIndex implements OrderIndex {

    private static final String HISTORY_AND_STATUS = """
            if (ctx._source.statusHistory == null) { ctx._source.statusHistory = []; }
            boolean seen = false;
            for (h in ctx._source.statusHistory) { if (h.eventId == params.eventId) { seen = true; } }
            if (!seen) { ctx._source.statusHistory.add(params.historyEntry); }
            if (ctx._source.statusUpdatedAt == null || ctx._source.statusUpdatedAt <= params.occurredAtMillis) {
              ctx._source.status = params.status;
              ctx._source.statusUpdatedAt = params.occurredAtMillis;
            }
            if (ctx._source.updatedAt == null || ctx._source.updatedAt.compareTo(params.occurredAt) < 0) {
              ctx._source.updatedAt = params.occurredAt;
            }
            """;

    static final String CREATED_SCRIPT = """
            ctx._source.orderId = params.orderId;
            ctx._source.orderNumber = params.orderNumber;
            if (ctx._source.customer == null) { ctx._source.customer = new HashMap(); }
            ctx._source.customer.id = params.customerId;
            ctx._source.channel = params.channel;
            ctx._source.currency = params.currency;
            ctx._source.shipToZip = params.shipToZip;
            ctx._source.totals = params.totals;
            ctx._source.lines = params.lines;
            ctx._source.createdAt = params.occurredAt;
            """ + HISTORY_AND_STATUS;

    static final String STATUS_SCRIPT = """
            if (ctx._source.orderId == null) { ctx._source.orderId = params.orderId; }
            if (ctx._source.orderNumber == null) { ctx._source.orderNumber = params.orderNumber; }
            if (ctx._source.customer == null) { ctx._source.customer = new HashMap(); }
            if (ctx._source.customer.id == null) { ctx._source.customer.id = params.customerId; }
            """ + HISTORY_AND_STATUS;

    private final ElasticsearchClient client;
    private final QueryProperties props;

    @Override
    public void applyCreated(OrderCreatedEvent e) {
        Map<String, Object> params = base(e.getEventId(), e.getOccurredAt(), e.getOrderId(), e.getOrderNumber(),
                e.getCustomerId(), e.getStatus());
        params.put("channel", e.getChannel());
        params.put("currency", e.getCurrency());
        params.put("shipToZip", e.getShipToZip());
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("subtotal", e.getSubtotal());
        totals.put("discountTotal", e.getDiscountTotal());
        totals.put("taxTotal", e.getTaxTotal());
        totals.put("shippingTotal", e.getShippingTotal());
        totals.put("grandTotal", e.getGrandTotal());
        params.put("totals", totals);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (var line : e.getLines()) {
            Map<String, Object> l = new LinkedHashMap<>();
            l.put("lineId", line.getLineId());
            l.put("sku", line.getSku());
            l.put("quantity", line.getQuantity());
            l.put("unitPrice", line.getUnitPrice());
            l.put("discount", line.getDiscount());
            l.put("tax", line.getTax());
            l.put("fulfillmentType", line.getFulfillmentType());
            lines.add(l);
        }
        params.put("lines", lines);
        params.put("historyEntry", history(e.getEventId(), null, e.getStatus(), e.getOccurredAt(), null, "order-service"));
        upsert(e.getOrderId(), CREATED_SCRIPT, params);
    }

    @Override
    public void applyStatusChanged(OrderStatusChangedEvent e) {
        Map<String, Object> params = base(e.getEventId(), e.getOccurredAt(), e.getOrderId(), e.getOrderNumber(),
                e.getCustomerId(), e.getToStatus());
        params.put("historyEntry", history(e.getEventId(), e.getFromStatus(), e.getToStatus(), e.getOccurredAt(),
                e.getReason(), e.getChangedBy()));
        upsert(e.getOrderId(), STATUS_SCRIPT, params);
    }

    @Override
    public Optional<OrderDocument> find(String orderId) {
        try {
            var response = client.get(g -> g.index(props.alias()).id(orderId), OrderDocument.class);
            return response.found() ? Optional.ofNullable(response.source()) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public OrderSearch.Page search(OrderSearch s) {
        Query query = Query.of(q -> q.bool(b -> {
            if (s.status() != null && !s.status().isBlank()) {
                b.filter(f -> f.term(t -> t.field("status").value(s.status().toUpperCase(Locale.ROOT))));
            }
            if (s.customerId() != null && !s.customerId().isBlank()) {
                b.filter(f -> f.term(t -> t.field("customer.id").value(s.customerId())));
            }
            if (s.q() != null && !s.q().isBlank()) {
                String text = s.q().trim();
                b.should(sh -> sh.term(t -> t.field("orderNumber").value(text.toUpperCase(Locale.ROOT))));
                b.should(sh -> sh.prefix(p -> p.field("orderNumber").value(text.toUpperCase(Locale.ROOT))));
                b.should(sh -> sh.term(t -> t.field("orderId").value(text.toLowerCase(Locale.ROOT))));
                b.should(sh -> sh.term(t -> t.field("lines.sku").value(text)));
                b.minimumShouldMatch("1");
            }
            return b;
        }));
        try {
            var response = client.search(r -> r.index(props.alias()).query(query)
                    .from(s.page() * s.size()).size(s.size())
                    .sort(so -> so.field(f -> f.field("createdAt").order(SortOrder.Desc).missing("_last")))
                    .trackTotalHits(t -> t.enabled(true)), OrderDocument.class);
            List<OrderDocument> items = response.hits().hits().stream().map(h -> h.source()).toList();
            long total = response.hits().total() == null ? items.size() : response.hits().total().value();
            return new OrderSearch.Page(items, total, s.page(), s.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void upsert(String orderId, String script, Map<String, Object> params) {
        Map<String, JsonData> jsonParams = new LinkedHashMap<>();
        params.forEach((k, v) -> jsonParams.put(k, JsonData.of(v)));
        try {
            client.update(u -> u.index(props.alias()).id(orderId).retryOnConflict(5).scriptedUpsert(true)
                    .upsert(Map.of()).script(sc -> sc.lang("painless").source(script).params(jsonParams)),
                    Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, Object> base(String eventId, Instant occurredAt, String orderId, String orderNumber,
            String customerId, String status) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("eventId", eventId);
        params.put("occurredAt", occurredAt.toString());
        params.put("occurredAtMillis", occurredAt.toEpochMilli());
        params.put("orderId", orderId);
        params.put("orderNumber", orderNumber);
        params.put("customerId", customerId);
        params.put("status", status);
        return params;
    }

    private static Map<String, Object> history(String eventId, String from, String to, Instant at, String reason,
            String changedBy) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("eventId", eventId);
        entry.put("from", from);
        entry.put("to", to);
        entry.put("at", at.toString());
        entry.put("reason", reason);
        entry.put("changedBy", changedBy);
        return entry;
    }
}
