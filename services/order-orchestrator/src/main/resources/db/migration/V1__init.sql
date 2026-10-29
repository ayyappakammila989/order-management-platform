-- order-orchestrator (architecture 4.2, saga_db = oms_saga). Personal learning project.

-- One saga per order. payload = what the steps need (order lines, ZIP, ...) and what they produced
-- (reservation id), plus which steps completed and which compensations are still to run.
CREATE TABLE saga_instance (
    saga_id            UUID         PRIMARY KEY,
    order_id           UUID         NOT NULL UNIQUE,      -- a second oms.order.created for the order starts nothing
    saga_type          VARCHAR(40)  NOT NULL,
    current_step       VARCHAR(40),
    status             VARCHAR(20)  NOT NULL
                       CHECK (status IN ('RUNNING', 'COMPENSATING', 'COMPLETED', 'COMPENSATED', 'FAILED')),
    payload            JSONB        NOT NULL,
    pending_command_id VARCHAR(40),                       -- the command whose reply the saga waits for
    deadline_at        TIMESTAMPTZ,                       -- no reply by then: the step timed out
    attempts           INT          NOT NULL DEFAULT 0,   -- compensation attempts of the current step
    failure_reason     VARCHAR(500),
    started_at         TIMESTAMPTZ  NOT NULL,
    updated_at         TIMESTAMPTZ  NOT NULL,
    version            BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX saga_instance_waiting ON saga_instance (deadline_at) WHERE status IN ('RUNNING', 'COMPENSATING');

-- Every step transition, for support ("why was my order cancelled?") and for learning.
CREATE TABLE saga_step_log (
    id          BIGSERIAL    PRIMARY KEY,
    saga_id     UUID         NOT NULL REFERENCES saga_instance (saga_id),
    step        VARCHAR(40)  NOT NULL,
    outcome     VARCHAR(20)  NOT NULL,
    message     VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX saga_step_log_saga ON saga_step_log (saga_id, id);

-- transactional outbox (libs/oms-common-kafka Outbox / OutboxRelay): the commands
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
