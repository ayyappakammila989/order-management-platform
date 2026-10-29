-- Step 3B: the payment method chosen at checkout, passed on to the saga in oms.order.created.
-- A gateway token only (Stripe pm_..., PayPal order id), never card data (architecture 7.5).
-- Nullable: orders from before step 3B, and channels that pay another way, have none.
ALTER TABLE orders ADD COLUMN payment_gateway      VARCHAR(10)  CHECK (payment_gateway IN ('STRIPE', 'PAYPAL'));
ALTER TABLE orders ADD COLUMN payment_method_token VARCHAR(100);
ALTER TABLE orders ADD CONSTRAINT ck_orders_payment_method
    CHECK ((payment_gateway IS NULL) = (payment_method_token IS NULL));
