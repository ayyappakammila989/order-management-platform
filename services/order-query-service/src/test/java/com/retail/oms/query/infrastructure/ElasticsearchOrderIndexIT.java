package com.retail.oms.query.infrastructure;

import static com.retail.oms.query.support.Events.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.retail.oms.query.application.OrderDocument;
import com.retail.oms.query.application.OrderIndex;
import com.retail.oms.query.application.OrderSearch;
import com.retail.oms.query.support.Events;
import com.retail.oms.query.support.AbstractQueryIT;
import com.retail.oms.query.support.Infrastructure;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The orders-v1 index in a real Elasticsearch: strict mapping, scripted upserts, ordering, idempotency, search. */
class ElasticsearchOrderIndexIT extends AbstractQueryIT {

    @Autowired OrderIndex index;
    @Autowired ElasticsearchClient client;

    void refresh() throws Exception {
        client.indices().refresh(r -> r.index("orders"));
    }

    @Test
    void indexAndAliasAreCreatedAtStartupWithAStrictMapping() throws Exception {
        assertThat(client.indices().exists(e -> e.index("orders-v1")).value()).isTrue();
        assertThat(client.indices().existsAlias(a -> a.name("orders")).value()).isTrue();
        var mapping = client.indices().getMapping(g -> g.index("orders-v1")).get("orders-v1").mappings();
        assertThat(mapping.dynamic().jsonValue()).isEqualTo("strict");
        // a field nobody declared is refused instead of silently creating a guessed mapping
        assertThatThrownBy(() -> client.index(i -> i.index("orders").id("x").document(Map.of("surprise", "field"))))
                .hasMessageContaining("strict_dynamic_mapping_exception");
    }

    @Test
    void createdEventBecomesTheFullDocument() {
        String id = UUID.randomUUID().toString();
        index.applyCreated(Events.created(id, "cust-1", T0));

        OrderDocument doc = index.find(id).orElseThrow();
        assertThat(doc.status()).isEqualTo("PENDING");
        assertThat(doc.customer().id()).isEqualTo("cust-1");
        assertThat(doc.totals().grandTotal()).isEqualByComparingTo("45.97");
        assertThat(doc.lines()).singleElement().satisfies(l -> {
            assertThat(l.sku()).isEqualTo("SKU-1");
            assertThat(l.unitPrice()).isEqualByComparingTo("19.99");
        });
        assertThat(doc.shipToZip()).isEqualTo("10001");
        assertThat(doc.statusHistory()).hasSize(1);
        assertThat(doc.createdAt()).isEqualTo(T0.toString());
    }

    @Test
    void repeatingTheSameEventsChangesNothing() {
        String id = UUID.randomUUID().toString();
        var created = Events.created(id, "cust-1", T0);
        var cancelled = Events.status(UUID.randomUUID().toString(), id, "cust-1", "PENDING", "CANCELLED", T0.plusSeconds(60));

        index.applyCreated(created);
        index.applyStatusChanged(cancelled);
        OrderDocument once = index.find(id).orElseThrow();
        index.applyCreated(created);
        index.applyStatusChanged(cancelled);
        index.applyStatusChanged(cancelled);

        assertThat(index.find(id).orElseThrow()).isEqualTo(once);
        assertThat(once.statusHistory()).hasSize(2);
        assertThat(once.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancellationArrivingBeforeCreationStillEndsCancelledWithAllDetails() {
        String id = UUID.randomUUID().toString();
        index.applyStatusChanged(Events.status(UUID.randomUUID().toString(), id, "cust-1", "PENDING", "CANCELLED", T0.plusSeconds(60)));

        OrderDocument partial = index.find(id).orElseThrow();
        assertThat(partial.status()).isEqualTo("CANCELLED");
        assertThat(partial.lines()).isNull();            // the created event has not been seen yet

        index.applyCreated(Events.created(id, "cust-1", T0));

        OrderDocument full = index.find(id).orElseThrow();
        assertThat(full.status()).isEqualTo("CANCELLED");   // the older PENDING must not win
        assertThat(full.lines()).hasSize(1);
        assertThat(full.createdAt()).isEqualTo(T0.toString());
        assertThat(full.statusHistory()).extracting(OrderDocument.StatusEntry::to).containsExactlyInAnyOrder("PENDING", "CANCELLED");
    }

    @Test
    void anOlderStatusNeverOverwritesANewerOne() {
        String id = UUID.randomUUID().toString();
        index.applyCreated(Events.created(id, "cust-1", T0));
        index.applyStatusChanged(Events.status(UUID.randomUUID().toString(), id, "cust-1", "CONFIRMED", "SHIPPED", T0.plusSeconds(300)));
        index.applyStatusChanged(Events.status(UUID.randomUUID().toString(), id, "cust-1", "PENDING", "CONFIRMED", T0.plusSeconds(100)));

        OrderDocument doc = index.find(id).orElseThrow();
        assertThat(doc.status()).isEqualTo("SHIPPED");
        assertThat(doc.statusHistory()).hasSize(3);
    }

    @Test
    void searchFiltersByTextStatusAndCustomerNewestFirst() throws Exception {
        String customer = "cust-" + UUID.randomUUID();
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        var first = Events.created(a, customer, T0);
        var second = Events.created(b, customer, T0.plusSeconds(10));
        index.applyCreated(first);
        index.applyCreated(second);
        index.applyStatusChanged(Events.status(UUID.randomUUID().toString(), a, customer, "PENDING", "CANCELLED", T0.plusSeconds(20)));
        index.applyCreated(Events.created(UUID.randomUUID().toString(), "someone-else", T0));
        refresh();

        var mine = index.search(new OrderSearch(null, null, customer, 0, 10));
        assertThat(mine.total()).isEqualTo(2);
        assertThat(mine.items()).extracting(OrderDocument::orderId).containsExactly(b, a);      // newest first

        assertThat(index.search(new OrderSearch(null, "cancelled", customer, 0, 10)).items())
                .extracting(OrderDocument::orderId).containsExactly(a);
        assertThat(index.search(new OrderSearch(first.getOrderNumber().toLowerCase(), null, customer, 0, 10)).items())
                .extracting(OrderDocument::orderId).contains(a);
        assertThat(index.search(new OrderSearch("SKU-1", null, customer, 0, 1)).items()).hasSize(1);
        assertThat(index.search(new OrderSearch("SKU-NOPE", null, customer, 0, 10)).total()).isZero();
        assertThat(index.search(new OrderSearch(null, null, customer, 1, 1)).items()).extracting(OrderDocument::orderId).containsExactly(a);
    }
}
