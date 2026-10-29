-- Starting rule set (mock data, tune with UPDATE fraud_rule ...). Thresholds: >= threshold hits.
-- Totals: 50+ = REVIEW, 80+ = REJECTED (oms.fraud.review-score / reject-score).
INSERT INTO fraud_rule (code, description, applies_to, score, threshold, window_minutes) VALUES
    ('BLOCKED_CUSTOMER',      'Customer is on the block list',                                   'ANY',    100, NULL,    NULL),
    ('BLOCKED_ZIP',           'Ship-to ZIP is on the block list',                                'ORDER',  100, NULL,    NULL),
    ('HIGH_ORDER_VALUE',      'Order total of 1000.00 or more',                                  'ORDER',   50, 1000.00, NULL),
    ('VERY_HIGH_ORDER_VALUE', 'Order total of 5000.00 or more',                                  'ORDER',   80, 5000.00, NULL),
    ('LARGE_QUANTITY',        'A line with 10 or more units of one SKU',                         'ORDER',   30, 10,      NULL),
    ('CARD_TESTING',          '4th different payment token from one customer within 60 minutes', 'ORDER',  50, 4,       60),
    ('ORDER_VELOCITY',        '30th order from one customer within 60 minutes',                  'ORDER',   30, 30,      60),
    ('HIGH_REFUND_VALUE',     'Refund of 500.00 or more',                                        'REFUND',  50, 500.00,  NULL),
    ('REFUND_VELOCITY',       '3rd refund for one customer within 30 days',                      'REFUND',  50, 3,       43200);

INSERT INTO fraud_blocklist (type, value, reason) VALUES
    ('ZIP',      '99666',            'Mock: freight forwarder address used in past chargebacks'),
    ('CUSTOMER', 'blocked-customer', 'Mock: confirmed chargeback abuse');
