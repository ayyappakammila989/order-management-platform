-- inventory-service (architecture 4.2, inventory_db). Personal learning project.

CREATE TABLE location (
    location_id  VARCHAR(30)  PRIMARY KEY,
    type         VARCHAR(10)  NOT NULL CHECK (type IN ('DC', 'STORE')),
    name         VARCHAR(100) NOT NULL,
    zip          VARCHAR(10)  NOT NULL
);

-- One row per SKU and location. available = on_hand - reserved - safety_stock (can go negative after a snapshot).
-- version: optimistic locking for writes that do not lock the row (stock snapshots); reservations lock the row.
CREATE TABLE stock (
    sku          VARCHAR(40)  NOT NULL,
    location_id  VARCHAR(30)  NOT NULL REFERENCES location (location_id),
    on_hand      INT          NOT NULL CHECK (on_hand >= 0),
    reserved     INT          NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    safety_stock INT          NOT NULL DEFAULT 0 CHECK (safety_stock >= 0),
    version      BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (sku, location_id)
);

CREATE TABLE reservation (
    reservation_id UUID         PRIMARY KEY,
    order_id       UUID         NOT NULL,
    saga_id        VARCHAR(64),
    status         VARCHAR(10)  NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED', 'CONSUMED')),
    expires_at     TIMESTAMPTZ,                -- NULL once the order is confirmed: kept until shipment or cancel
    release_reason VARCHAR(30),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);
-- at most one ACTIVE reservation per order: a repeated reserve for the same order returns the existing one
CREATE UNIQUE INDEX reservation_one_active_per_order ON reservation (order_id) WHERE status = 'ACTIVE';
CREATE INDEX reservation_expiry ON reservation (expires_at) WHERE status = 'ACTIVE';

CREATE TABLE reservation_line (
    id             BIGSERIAL    PRIMARY KEY,
    reservation_id UUID         NOT NULL REFERENCES reservation (reservation_id),
    sku            VARCHAR(40)  NOT NULL,
    location_id    VARCHAR(30)  NOT NULL,
    quantity       INT          NOT NULL CHECK (quantity > 0)
);
CREATE INDEX reservation_line_reservation ON reservation_line (reservation_id);

-- newest stock snapshot applied per location; an older one arriving late is ignored
CREATE TABLE location_snapshot (
    location_id  VARCHAR(30)  PRIMARY KEY REFERENCES location (location_id),
    snapshot_id  VARCHAR(40)  NOT NULL,
    taken_at     TIMESTAMPTZ  NOT NULL,
    applied_at   TIMESTAMPTZ  NOT NULL
);

-- transactional outbox (libs/oms-common-kafka Outbox / OutboxRelay)
CREATE TABLE outbox_event (
    id             UUID         PRIMARY KEY,
    topic          VARCHAR(200) NOT NULL,
    message_key    VARCHAR(200) NOT NULL,
    event_type     VARCHAR(100) NOT NULL,
    payload        BYTEA        NOT NULL,
    correlation_id VARCHAR(100),
    created_at     TIMESTAMPTZ  NOT NULL,
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);
CREATE INDEX outbox_event_unpublished ON outbox_event (created_at) WHERE published_at IS NULL;

-- idempotent consumer (libs/oms-common-kafka ProcessedMessages)
CREATE TABLE processed_message (
    consumer     VARCHAR(100) NOT NULL,
    message_id   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (consumer, message_id)
);
