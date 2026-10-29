package com.retail.oms.order.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.order.domain.AddressType;
import com.retail.oms.order.domain.Order;
import com.retail.oms.order.domain.OrderStatus;
import com.retail.oms.order.infrastructure.messaging.OutboxEvent;
import com.retail.oms.order.infrastructure.messaging.OutboxEventRepository;
import com.retail.oms.order.support.PostgresTestConfig;
import com.retail.oms.order.support.TestOrders;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Repositories against a real PostgreSQL with the real Flyway migrations (architecture 12.2):
 * queries, indexes used, optimistic locking. Runs without the test-managed transaction so each step commits,
 * like production code does.
 */
@DataJpaTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OrderRepositoryIT {

    @Autowired OrderRepository orders;
    @Autowired OutboxEventRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx;
    Statistics stats;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        stats = emf.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    void orderWithLinesAddressesAndHistoryIsSavedAndTotalsMatchTheCheckConstraint() {
        Order saved = orders.save(TestOrders.order("cust-1"));

        Integer historyRows = jdbc.queryForObject(
                "select count(*) from order_status_history where order_id = ? and from_status is null and to_status = 'PENDING'",
                Integer.class, saved.getOrderId());
        assertThat(historyRows).isEqualTo(1);
        assertThat(jdbc.queryForObject("select grand_total::text from orders where order_id = ?", String.class,
                saved.getOrderId())).isEqualTo("51.37");
    }

    @Test
    void detailsAreLoadedWithOneSelectNoNPlusOne() {
        UUID id = orders.save(TestOrders.order("cust-2")).getOrderId();
        stats.clear();

        Order loaded = tx.execute(s -> {
            Order o = orders.findDetailsById(id).orElseThrow();
            o.getLines().forEach(l -> l.getSku());          // would trigger lazy loads without the entity graph
            o.address(AddressType.SHIP).orElseThrow();
            return o;
        });

        assertThat(loaded.getLines()).extracting(l -> l.getSku()).containsExactly("SKU-1", "SKU-2");
        assertThat(loaded.getAddresses()).hasSize(2);
        assertThat(stats.getPrepareStatementCount()).as("SQL statements for order details").isEqualTo(1);
    }

    @Test
    void idempotencyKeyIsUnique() {
        String key = "checkout-" + UUID.randomUUID();
        Order first = orders.save(TestOrders.order("cust-3", key));

        assertThat(orders.findByIdempotencyKey(key)).map(Order::getOrderId).contains(first.getOrderId());
        assertThatThrownBy(() -> orders.save(TestOrders.order("cust-3", key)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentStatusChangesLoseWithOptimisticLock() {
        UUID id = orders.save(TestOrders.order("cust-4")).getOrderId();
        TransactionTemplate other = new TransactionTemplate(txManager);
        other.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            Order mine = orders.findById(id).orElseThrow();                 // reads version 0
            other.executeWithoutResult(s2 -> orders.findById(id).orElseThrow()
                    .changeStatus(OrderStatus.CONFIRMED, "saga ok", "orchestrator", TestOrders.NOW));  // commits version 1
            mine.changeStatus(OrderStatus.CANCELLED, "customer", "cust-4", TestOrders.NOW);
            orders.flush();                                                  // UPDATE ... WHERE version = 0 -> 0 rows
        })).isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void customerHistoryQueryUsesCompositeIndex() {
        String plan = explain("select * from orders where customer_id = 'cust-5' order by created_at desc limit 20");
        assertThat(plan).contains("ix_orders_customer_created").doesNotContain("Sort");
    }

    @Test
    void partialStatusIndexIsUsedOnlyForOpenStatuses() {
        assertThat(explain("select order_id from orders where status = 'PENDING'")).contains("ix_orders_open_status");
        assertThat(explain("select order_id from orders where status = 'SHIPPED'")).doesNotContain("ix_orders_open_status");
    }

    @Test
    void customerIdLookupAndOrderNumberSequence() {
        Order saved = orders.save(TestOrders.order("cust-6"));

        assertThat(orders.findCustomerIdById(saved.getOrderId())).contains("cust-6");
        assertThat(orders.findCustomerIdById(UUID.randomUUID())).isEmpty();
        long first = orders.nextOrderNumber();
        assertThat(orders.nextOrderNumber()).isEqualTo(first + 1);
    }

    @Test
    void outboxRowsAreStoredAndOldOnesCleanedUp() {
        String aggregateId = UUID.randomUUID().toString();
        outbox.save(new OutboxEvent(UUID.randomUUID(), "order", aggregateId, "order.created", new byte[] {0, 1, 2},
                "corr-1", TestOrders.NOW.minus(Duration.ofDays(8))));
        outbox.save(new OutboxEvent(UUID.randomUUID(), "order", aggregateId, "order.cancelled", new byte[] {0, 3},
                "corr-2", TestOrders.NOW));

        Integer deleted = tx.execute(s -> outbox.deleteOlderThan(TestOrders.NOW.minus(Duration.ofDays(7))));

        assertThat(deleted).isEqualTo(1);
        List<OutboxEvent> left = outbox.findByAggregateIdOrderByCreatedAt(aggregateId);
        assertThat(left).extracting(OutboxEvent::getEventType).containsExactly("order.cancelled");
    }

    @Test
    void publicationForDebeziumCoversOnlyOutboxInserts() {
        assertThat(jdbc.queryForList(
                "select tablename from pg_publication_tables where pubname = 'oms_order_outbox'", String.class))
                .containsExactly("outbox_event");
        assertThat(jdbc.queryForObject(
                "select pubinsert and not pubupdate and not pubdelete from pg_publication where pubname = 'oms_order_outbox'",
                Boolean.class)).isTrue();
    }

    /** With seq scans disabled the planner must use an index if one can serve the query (tables are tiny here). */
    private String explain(String sql) {
        return tx.execute(s -> {
            jdbc.execute("set local enable_seqscan = off");
            return String.join("\n", jdbc.queryForList("explain " + sql, String.class));
        });
    }
}
