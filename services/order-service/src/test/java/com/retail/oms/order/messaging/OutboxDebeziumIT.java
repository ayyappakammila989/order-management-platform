package com.retail.oms.order.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.retail.oms.events.order.OrderCreatedEvent;
import com.retail.oms.events.order.OrderStatusChangedEvent;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.MountableFile;

/**
 * Outbox -> Kafka with the real pieces (architecture 12.2): PostgreSQL with logical WAL, Kafka, Schema Registry,
 * Debezium Connect, and the connector config from infra/debezium. Checks that every order change is published
 * exactly once, keyed by order id, as Avro, with the event id and correlation id as headers.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OutboxDebeziumIT {

    static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    static final Network NETWORK = Network.newNetwork();

    /** Same image, WAL setting and init scripts as docker-compose.yml. */
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withNetwork(NETWORK).withNetworkAliases("postgres")
            .withUsername("postgres").withPassword("postgres").withDatabaseName("postgres")
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_replication_slots=4", "-c", "max_wal_senders=4")
            .withCopyFileToContainer(MountableFile.forHostPath(REPO.resolve("infra/postgres/init-databases.sh"), 0755),
                    "/docker-entrypoint-initdb.d/init-databases.sh")
            .withCopyFileToContainer(MountableFile.forHostPath(REPO.resolve("infra/postgres/debezium-user.sql")),
                    "/docker-entrypoint-initdb.d/debezium-user.sql.inc");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withNetwork(NETWORK).withNetworkAliases("kafka")
            .withListener("kafka:19092")                                   // reachable from the other containers
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");         // as in Compose: Debezium must create topics

    @Container
    static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>("confluentinc/cp-schema-registry:8.2.4")
            .withNetwork(NETWORK).withNetworkAliases("schema-registry")
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
            .withEnv("SCHEMA_REGISTRY_HEAP_OPTS", "-Xmx256m")
            .withExposedPorts(8081)
            .dependsOn(KAFKA)
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    @Container
    static final GenericContainer<?> CONNECT = new GenericContainer<>("quay.io/debezium/connect:3.6.2.Final")
            .withNetwork(NETWORK).withNetworkAliases("kafka-connect")
            .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
            .withEnv("GROUP_ID", "oms-connect-it")
            .withEnv("CONFIG_STORAGE_TOPIC", "_connect-configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "_connect-offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "_connect-status")
            .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("HEAP_OPTS", "-Xms256m -Xmx512m")
            .withExposedPorts(8083)
            .dependsOn(KAFKA, POSTGRES)
            .waitingFor(Wait.forHttp("/connectors").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static boolean connectorRegistered;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Startables.deepStart(POSTGRES, KAFKA, SCHEMA_REGISTRY, CONNECT, REDIS).join();
        // the application logs in as its own user to its own database, exactly like in Compose
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/oms_order");
        registry.add("spring.datasource.username", () -> "oms_order");
        registry.add("spring.datasource.password", () -> "oms_order");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("oms.order.schema-registry-url", OutboxDebeziumIT::schemaRegistryUrl);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    /** After the Spring context exists, so Flyway has already created the outbox table and the publication. */
    @BeforeEach
    void registerConnectorOnce() throws Exception {
        if (!connectorRegistered) {
            JsonNode file = objectMapper.readTree(REPO.resolve("infra/debezium/order-outbox-connector.json").toFile());
            String connectUrl = "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083);
            HttpClient http = HttpClient.newHttpClient();
            HttpResponse<String> put = http.send(HttpRequest.newBuilder(
                            URI.create(connectUrl + "/connectors/" + file.get("name").asText() + "/config"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(file.get("config").toString())).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(put.statusCode()).as(put.body()).isIn(200, 201);
            awaitRunning(http, connectUrl + "/connectors/order-outbox/status");
            connectorRegistered = true;
        }
    }

    /** A connector task that died (e.g. a converter error) would otherwise only show up as a missing event. */
    @org.junit.jupiter.api.AfterEach
    void connectorStillRunning() throws Exception {
        String status = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                        "http://" + CONNECT.getHost() + ":" + CONNECT.getMappedPort(8083) + "/connectors/order-outbox/status"))
                .build(), HttpResponse.BodyHandlers.ofString()).body();
        assertThat(status).as("connector status").contains("\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"");
    }

    /** Debezium's own log, for diagnosing connector problems: target/debezium-connect-it.log */
    @org.junit.jupiter.api.AfterAll
    static void saveConnectLog() throws Exception {
        Files.writeString(Path.of("target/debezium-connect-it.log"), CONNECT.getLogs());
    }

    @Test
    void createdOrderIsPublishedOnceEvenWhenTheRequestIsRetried() throws Exception {
        String key = "it-" + UUID.randomUUID();
        String correlationId = "corr-" + UUID.randomUUID();

        String orderId = createOrder(key, correlationId);
        assertThat(createOrder(key, correlationId)).as("retry returns the same order").isEqualTo(orderId);
        assertThat(createOrder(key, correlationId)).isEqualTo(orderId);

        List<ConsumerRecord<String, Object>> records = readFor("oms.order.created", orderId, 1);

        assertThat(records).as("exactly one event for three identical requests").hasSize(1);
        ConsumerRecord<String, Object> record = records.get(0);
        assertThat(record.key()).isEqualTo(orderId);
        OrderCreatedEvent event = (OrderCreatedEvent) record.value();
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getGrandTotal()).isEqualByComparingTo("26.59");
        assertThat(event.getLines()).singleElement().satisfies(l -> assertThat(l.getLineId()).isPositive());
        assertThat(header(record, "id")).isEqualTo(event.getEventId());
        assertThat(header(record, "eventType")).isEqualTo("order.created");
        assertThat(header(record, "aggregateType")).isEqualTo("order");
        assertThat(header(record, "correlationId")).isEqualTo(correlationId);
    }

    @Test
    void cancellationIsPublishedToItsOwnTopicWithSameKey() throws Exception {
        String orderId = createOrder("it-" + UUID.randomUUID(), "corr-cancel");

        mvc.perform(post("/api/v1/orders/{id}/cancel", orderId).with(agent())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"customer called\"}"))
                .andExpect(status().isOk());

        List<ConsumerRecord<String, Object>> records = readFor("oms.order.cancelled", orderId, 1);
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo(orderId);
        OrderStatusChangedEvent event = (OrderStatusChangedEvent) records.get(0).value();
        assertThat(event.getFromStatus()).isEqualTo("PENDING");
        assertThat(event.getToStatus()).isEqualTo("CANCELLED");
        assertThat(event.getChangedBy()).isEqualTo("agent1");
    }

    @Test
    void schemasAreRegisteredUnderTopicSubjectsAndTopicsHaveThreePartitions() throws Exception {
        String orderId = createOrder("it-" + UUID.randomUUID(), "corr-schema");
        assertThat(readFor("oms.order.created", orderId, 1)).hasSize(1);   // topic exists once the event arrived
        HttpResponse<String> subjects = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(schemaRegistryUrl() + "/subjects")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(subjects.body()).contains("\"oms.order.created-value\"");
        try (KafkaConsumer<String, Object> consumer = consumer()) {
            assertThat(consumer.partitionsFor("oms.order.created")).hasSize(3);
        }
    }

    private String createOrder(String idempotencyKey, String correlationId) throws Exception {
        String body = """
                {"customerId":"cust-it","channel":"WEB","currency":"USD",
                 "lines":[{"sku":"SKU-IT","quantity":2,"unitPrice":10.80,"tax":1.80,"fulfillmentType":"SHIP"}],
                 "shippingAddress":{"name":"It Test","line1":"1 Test St","city":"Austin","state":"TX","zip":"73301"},
                 "shippingTotal":3.19}""";
        String json = mvc.perform(post("/internal/v1/orders").with(cartService())
                        .header("Idempotency-Key", idempotencyKey).header("X-Correlation-Id", correlationId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("orderId").asText();
    }

    /**
     * Reads the topic from the beginning until {@code expected} records for the order arrived, then keeps reading
     * a few more seconds so a duplicate would be caught too.
     */
    private List<ConsumerRecord<String, Object>> readFor(String topic, String orderId, int expected) {
        List<ConsumerRecord<String, Object>> found = new ArrayList<>();
        try (KafkaConsumer<String, Object> consumer = consumer()) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            long quietUntil = Long.MAX_VALUE;
            while (System.nanoTime() < Math.min(deadline, quietUntil)) {
                for (ConsumerRecord<String, Object> r : consumer.poll(Duration.ofMillis(500))) {
                    if (orderId.equals(r.key())) {
                        found.add(r);
                    }
                }
                if (found.size() >= expected && quietUntil == Long.MAX_VALUE) {
                    quietUntil = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                }
            }
        }
        return found;
    }

    private static KafkaConsumer<String, Object> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // topics appear only when Debezium writes the first event; default metadata refresh is 5 minutes
        props.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 1000);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.putAll(Map.of("schema.registry.url", schemaRegistryUrl(), "specific.avro.reader", true));
        return new KafkaConsumer<>(props);
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    private static void awaitRunning(HttpClient http, String statusUrl) throws Exception {
        String last = "";
        for (int i = 0; i < 60; i++) {
            last = http.send(HttpRequest.newBuilder(URI.create(statusUrl)).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            if (last.contains("\"tasks\":[{\"id\":0,\"state\":\"RUNNING\"")) {
                return;
            }
            assertThat(last).doesNotContain("FAILED");
            Thread.sleep(1000);
        }
        throw new AssertionError("connector not RUNNING: " + last);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor cartService() {
        return jwt().jwt(j -> j.subject("svc").claim("azp", "cart-service")
                        .claim("preferred_username", "service-account-cart-service"))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor agent() {
        return jwt().jwt(j -> j.subject("agent-sub").claim("preferred_username", "agent1"))
                .authorities(new SimpleGrantedAuthority("ROLE_SUPPORT_AGENT"));
    }
}
