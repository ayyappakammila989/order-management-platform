-- Login used by Kafka Connect / Debezium to stream the order outbox. Dev-only password.
-- REPLICATION lets it open a logical replication slot; it gets no write rights anywhere.
-- SELECT on outbox_event and the publication are created by order-service's Flyway migration,
-- because only the table owner (oms_order) can do that.
CREATE USER oms_debezium WITH REPLICATION LOGIN PASSWORD 'oms_debezium';
GRANT CONNECT ON DATABASE oms_order TO oms_debezium;
