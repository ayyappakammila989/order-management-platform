-- cart-service database: only "saved carts" live here. Active carts are in Redis (cart:{cartId}).
CREATE TABLE saved_cart (
    saved_cart_id  UUID         PRIMARY KEY,
    customer_id    VARCHAR(64)  NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    items          JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX ix_saved_cart_customer ON saved_cart (customer_id, created_at DESC);
