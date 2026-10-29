-- order-service schema (architecture 4.2). Money is NUMERIC(12,2), never a float.

CREATE SEQUENCE order_number_seq;

CREATE TABLE orders (
    order_id          UUID           PRIMARY KEY,
    order_number      VARCHAR(30)    NOT NULL,
    customer_id       VARCHAR(64)    NOT NULL,
    channel           VARCHAR(20)    NOT NULL,
    status            VARCHAR(20)    NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    subtotal          NUMERIC(12, 2) NOT NULL,
    discount_total    NUMERIC(12, 2) NOT NULL,
    tax_total         NUMERIC(12, 2) NOT NULL,
    shipping_total    NUMERIC(12, 2) NOT NULL,
    grand_total       NUMERIC(12, 2) NOT NULL,
    sap_order_number  VARCHAR(20),
    -- second line of defence behind the Redis idempotency key: the same checkout can never create two orders
    idempotency_key   VARCHAR(100)   NOT NULL,
    created_at        TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    version           BIGINT         NOT NULL,
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT uq_orders_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_orders_channel CHECK (channel IN ('WEB', 'MOBILE', 'STORE', 'CALL_CENTER')),
    CONSTRAINT ck_orders_totals CHECK (grand_total = subtotal - discount_total + tax_total + shipping_total)
);

-- "my orders" and agent lookups by customer, newest first (architecture 13)
CREATE INDEX ix_orders_customer_created ON orders (customer_id, created_at DESC);
-- only open orders are queried by status; partial index stays small
CREATE INDEX ix_orders_open_status ON orders (status) WHERE status IN ('PENDING', 'CONFIRMED');

-- allocationSize 50 in JPA: Hibernate reserves 50 ids per round trip, so the sequence steps by 50
CREATE SEQUENCE order_lines_seq INCREMENT BY 50;

CREATE TABLE order_lines (
    line_id           BIGINT         PRIMARY KEY,
    order_id          UUID           NOT NULL REFERENCES orders (order_id),
    sku               VARCHAR(40)    NOT NULL,
    quantity          INTEGER        NOT NULL CHECK (quantity > 0),
    unit_price        NUMERIC(12, 2) NOT NULL CHECK (unit_price >= 0),
    discount          NUMERIC(12, 2) NOT NULL CHECK (discount >= 0),
    tax               NUMERIC(12, 2) NOT NULL CHECK (tax >= 0),
    line_status       VARCHAR(20)    NOT NULL,
    fulfillment_type  VARCHAR(10)    NOT NULL CHECK (fulfillment_type IN ('SHIP', 'BOPIS', 'SFS'))
);
CREATE INDEX ix_order_lines_order ON order_lines (order_id);

CREATE SEQUENCE order_addresses_seq INCREMENT BY 50;

CREATE TABLE order_addresses (
    id        BIGINT        PRIMARY KEY,
    order_id  UUID          NOT NULL REFERENCES orders (order_id),
    type      VARCHAR(4)    NOT NULL CHECK (type IN ('SHIP', 'BILL')),
    name      VARCHAR(100)  NOT NULL,
    line1     VARCHAR(200)  NOT NULL,
    city      VARCHAR(100)  NOT NULL,
    state     VARCHAR(50)   NOT NULL,
    zip       VARCHAR(20)   NOT NULL,
    CONSTRAINT uq_order_addresses_order_type UNIQUE (order_id, type)
);

CREATE SEQUENCE order_status_history_seq INCREMENT BY 50;

CREATE TABLE order_status_history (
    id           BIGINT        PRIMARY KEY,
    order_id     UUID          NOT NULL REFERENCES orders (order_id),
    from_status  VARCHAR(20),                       -- NULL for the creation entry
    to_status    VARCHAR(20)   NOT NULL,
    reason       VARCHAR(200),
    changed_by   VARCHAR(100)  NOT NULL,
    changed_at   TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_order_status_history_order ON order_status_history (order_id, changed_at);

-- Transactional outbox. Written in the same transaction as the order; Debezium streams new rows to Kafka.
-- payload holds the Avro record already in Confluent wire format (magic byte + schema id + Avro binary),
-- so the connector copies the bytes to Kafka unchanged (architecture says JSONB; see STATUS "Decisions").
CREATE TABLE outbox_event (
    id              UUID          PRIMARY KEY,
    aggregate_type  VARCHAR(50)   NOT NULL,
    aggregate_id    VARCHAR(64)   NOT NULL,    -- becomes the Kafka record key: all events of one order stay in order
    event_type      VARCHAR(50)   NOT NULL,    -- e.g. order.created; Debezium routes it to topic oms.order.created
    payload         BYTEA         NOT NULL,
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMPTZ   NOT NULL
);
CREATE INDEX ix_outbox_event_created ON outbox_event (created_at);   -- for the cleanup job

-- Debezium only needs INSERTs; the cleanup job's DELETEs are not published.
CREATE PUBLICATION oms_order_outbox FOR TABLE outbox_event WITH (publish = 'insert');

-- The Debezium login exists in the Compose PostgreSQL (infra/postgres/debezium-user.sql), not in every test database.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'oms_debezium') THEN
        GRANT USAGE ON SCHEMA public TO oms_debezium;
        GRANT SELECT ON outbox_event TO oms_debezium;
    END IF;
END
$$;
