-- Mock data for local runs (no WMS or store feed yet). SKU-1..5 match pricing-service's seed prices.
INSERT INTO location (location_id, type, name, zip) VALUES
    ('DC-EAST',   'DC',    'Edison NJ distribution center',  '08817'),
    ('DC-WEST',   'DC',    'Reno NV distribution center',    '89501'),
    ('STORE-NYC', 'STORE', 'New York 34th Street store',     '10001'),
    ('STORE-SF',  'STORE', 'San Francisco Market St store',  '94105');

INSERT INTO stock (sku, location_id, on_hand, reserved, safety_stock) VALUES
    ('SKU-1', 'DC-EAST',   100, 0, 0),
    ('SKU-1', 'DC-WEST',    80, 0, 0),
    ('SKU-1', 'STORE-NYC',  10, 0, 2),
    ('SKU-2', 'DC-EAST',    50, 0, 0),
    ('SKU-2', 'DC-WEST',    50, 0, 0),
    ('SKU-3', 'DC-WEST',    30, 0, 0),
    ('SKU-3', 'STORE-SF',    5, 0, 1),
    ('SKU-4', 'DC-EAST',    20, 0, 0),
    ('SKU-5', 'STORE-NYC',   2, 0, 0);    -- scarce on purpose: the out-of-stock path
