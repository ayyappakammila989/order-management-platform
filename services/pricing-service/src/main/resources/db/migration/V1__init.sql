-- pricing-service schema (architecture 4.2: promotions are relational rules). Money is NUMERIC(12,2).

-- Own copy of base prices; in the real design catalog-sync keeps this current from the PIM.
CREATE TABLE product_price (
    sku         VARCHAR(40)    PRIMARY KEY,
    currency    VARCHAR(3)     NOT NULL,
    base_price  NUMERIC(12, 2) NOT NULL,
    tax_code    VARCHAR(20)    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT ck_product_price_nonneg CHECK (base_price >= 0)
);

CREATE TABLE promotion (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          VARCHAR(100)   NOT NULL,
    -- NULL = automatic promotion; otherwise the customer must enter the code
    coupon_code   VARCHAR(40),
    scope         VARCHAR(10)    NOT NULL,
    sku           VARCHAR(40),
    kind          VARCHAR(10)    NOT NULL,
    value         NUMERIC(12, 2) NOT NULL,
    min_subtotal  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    starts_at     TIMESTAMPTZ    NOT NULL,
    ends_at       TIMESTAMPTZ    NOT NULL,
    active        BOOLEAN        NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_promotion_coupon UNIQUE (coupon_code),
    CONSTRAINT ck_promotion_scope CHECK (scope IN ('SKU', 'ORDER')),
    CONSTRAINT ck_promotion_kind CHECK (kind IN ('PERCENT', 'FIXED')),
    CONSTRAINT ck_promotion_sku CHECK ((scope = 'SKU') = (sku IS NOT NULL)),
    CONSTRAINT ck_promotion_value CHECK (value > 0 AND (kind <> 'PERCENT' OR value <= 100)),
    CONSTRAINT ck_promotion_window CHECK (ends_at > starts_at),
    -- SKU promotions apply automatically; coupons are order-level
    CONSTRAINT ck_promotion_coupon_scope CHECK (coupon_code IS NULL OR scope = 'ORDER')
);
CREATE INDEX ix_promotion_active_window ON promotion (starts_at, ends_at) WHERE active;

-- Sample catalog and promotions so the platform can be tried end to end (mock data, not a real PIM feed)
INSERT INTO product_price (sku, currency, base_price, tax_code) VALUES
    ('SKU-1', 'USD', 19.99, 'GENERAL'),
    ('SKU-2', 'USD', 49.50, 'GENERAL'),
    ('SKU-3', 'USD', 5.00, 'GENERAL'),
    ('SKU-4', 'USD', 129.00, 'GENERAL'),
    ('SKU-5', 'USD', 0.99, 'GENERAL');

INSERT INTO promotion (name, coupon_code, scope, sku, kind, value, min_subtotal, starts_at, ends_at) VALUES
    ('SKU-2 15% off', NULL, 'SKU', 'SKU-2', 'PERCENT', 15, 0, '2026-01-01T00:00:00Z', '2030-01-01T00:00:00Z'),
    ('SKU-3 buy cheap: 1.00 off each', NULL, 'SKU', 'SKU-3', 'FIXED', 1.00, 0, '2026-01-01T00:00:00Z', '2030-01-01T00:00:00Z'),
    ('Spring 10% off orders from 50', 'SPRING10', 'ORDER', NULL, 'PERCENT', 10, 50.00, '2026-01-01T00:00:00Z', '2030-01-01T00:00:00Z'),
    ('Welcome 5 off orders from 20', 'WELCOME5', 'ORDER', NULL, 'FIXED', 5.00, 20.00, '2026-01-01T00:00:00Z', '2030-01-01T00:00:00Z'),
    ('Expired coupon', 'OLD20', 'ORDER', NULL, 'PERCENT', 20, 0, '2025-01-01T00:00:00Z', '2025-06-01T00:00:00Z');
