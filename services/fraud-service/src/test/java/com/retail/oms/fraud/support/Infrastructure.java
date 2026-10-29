package com.retail.oms.fraud.support;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/** Real PostgreSQL, Kafka (KRaft) and Schema Registry, once per test JVM. Same images as docker-compose.yml. */
public final class Infrastructure {

    static final Network NETWORK = Network.newNetwork();

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("oms_fraud").withUsername("oms_fraud").withPassword("oms_fraud");

    public static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.2")
            .withNetwork(NETWORK).withNetworkAliases("kafka").withListener("kafka:19092");

    public static final GenericContainer<?> SCHEMA_REGISTRY = new GenericContainer<>("confluentinc/cp-schema-registry:8.2.4")
            .withNetwork(NETWORK).withNetworkAliases("schema-registry")
            .withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
            .withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
            .withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
            .withEnv("SCHEMA_REGISTRY_HEAP_OPTS", "-Xmx256m")
            .withExposedPorts(8081)
            .dependsOn(KAFKA)
            .waitingFor(Wait.forHttp("/subjects").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(2)));

    static {
        Startables.deepStart(POSTGRES, KAFKA, SCHEMA_REGISTRY).join();
    }

    private Infrastructure() {
    }

    public static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&socketTimeout=3");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("oms.kafka.schema-registry-url", Infrastructure::schemaRegistryUrl);
        registry.add("oms.kafka.schema-registry-timeout", () -> "2s");
        registry.add("oms.kafka.retry.initial-interval", () -> "500ms");
        registry.add("oms.kafka.retry.max-retries", () -> "3");
        registry.add("oms.kafka.registry-retry.initial-interval", () -> "300ms");
        registry.add("oms.kafka.registry-retry.max-retries", () -> "3");
        registry.add("oms.outbox.poll-interval", () -> "100ms");
    }
}
