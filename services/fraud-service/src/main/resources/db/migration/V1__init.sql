-- fraud-service (architecture 4.2: fraud_db = oms_fraud, "rules + cases"). Personal learning project.

-- Rule parameters live in the database so they can be tuned without a release; the logic of each code is in
-- RuleEngine. A rule that hits adds its score; the total decides (oms.fraud.review-score / reject-score).
CREATE TABLE fraud_rule (
    code           VARCHAR(40)    PRIMARY KEY,
    description    VARCHAR(200)   NOT NULL,
    applies_to     VARCHAR(10)    NOT NULL CHECK (applies_to IN ('ORDER', 'REFUND', 'ANY')),
    enabled        BOOLEAN        NOT NULL DEFAULT TRUE,
    score          INT            NOT NULL CHECK (score >= 0),
    threshold      NUMERIC(12, 2),             -- amount, count or quantity, depending on the rule
    window_minutes INT            CHECK (window_minutes > 0),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE TABLE fraud_blocklist (
    type       VARCHAR(10)  NOT NULL CHECK (type IN ('CUSTOMER', 'ZIP')),
    value      VARCHAR(64)  NOT NULL,
    reason     VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (type, value)
);

-- One row per screening (order from the saga or the API, refund from the API). Kept also when cancelled: the
-- velocity rules count attempts, not only orders that went through.
CREATE TABLE fraud_check (
    check_id       UUID           PRIMARY KEY,
    kind           VARCHAR(10)    NOT NULL CHECK (kind IN ('ORDER', 'REFUND')),
    order_id       UUID           NOT NULL,
    reference_id   VARCHAR(64),                 -- refund / return id for kind REFUND
    customer_id    VARCHAR(64)    NOT NULL,
    saga_id        UUID,
    command_id     VARCHAR(40)    UNIQUE,       -- the saga command it answers: a repeated command is answered once
    amount         NUMERIC(12, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    ship_to_zip    VARCHAR(10),
    payment_token  VARCHAR(100),
    score          INT            NOT NULL,
    decision       VARCHAR(10)    NOT NULL CHECK (decision IN ('APPROVED', 'REVIEW', 'REJECTED')),
    final_decision VARCHAR(10)    CHECK (final_decision IN ('APPROVED', 'REJECTED')),   -- after a review
    reasons        VARCHAR(500)   NOT NULL,     -- rule codes that hit, comma separated
    status         VARCHAR(10)    NOT NULL CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at     TIMESTAMPTZ    NOT NULL,
    updated_at     TIMESTAMPTZ    NOT NULL,
    version        BIGINT         NOT NULL DEFAULT 0
);
CREATE INDEX fraud_check_customer ON fraud_check (customer_id, kind, created_at DESC);
CREATE INDEX fraud_check_order ON fraud_check (order_id, created_at DESC);

-- A flagged check: OPEN for REVIEW (a supervisor decides), REJECTED right away for a rejection (kept for the record).
CREATE TABLE fraud_case (
    case_id     UUID          PRIMARY KEY,
    check_id    UUID          NOT NULL UNIQUE REFERENCES fraud_check (check_id),
    order_id    UUID          NOT NULL,
    status      VARCHAR(10)   NOT NULL CHECK (status IN ('OPEN', 'APPROVED', 'REJECTED', 'CLOSED')),
    opened_at   TIMESTAMPTZ   NOT NULL,
    decided_by  VARCHAR(64),
    decided_at  TIMESTAMPTZ,
    note        VARCHAR(500),
    version     BIGINT        NOT NULL DEFAULT 0
);
CREATE INDEX fraud_case_status ON fraud_case (status, opened_at);

-- transactional outbox (libs/oms-common-kafka Outbox / OutboxRelay): the saga replies
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
