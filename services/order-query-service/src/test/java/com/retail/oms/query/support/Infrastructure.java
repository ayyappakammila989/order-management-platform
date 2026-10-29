package com.retail.oms.query.support;

import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Real Kafka (KRaft), Schema Registry, Elasticsearch and Redis, started once per test JVM and shared by the ITs.
 * Same images and versions as docker-compose.yml.
 */
public final class Infrastructure {

    static final Network NETWORK = Network.newNetwork();

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

    public static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            "docker.elastic.co/elasticsearch/elasticsearch:8.19.19")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withStartupTimeout(Duration.ofMinutes(3));

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        Startables.deepStart(KAFKA, SCHEMA_REGISTRY, ELASTICSEARCH, REDIS).join();
    }

    private Infrastructure() {
    }

    public static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    /** Points the application at the containers. Short timeouts and short backoff keep the failure tests quick. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("oms.query.schema-registry-url", Infrastructure::schemaRegistryUrl);
        registry.add("spring.elasticsearch.uris", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("spring.elasticsearch.socket-timeout", () -> "1s");
        registry.add("spring.elasticsearch.connection-timeout", () -> "1s");
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("oms.query.retry.initial-interval", () -> "300ms");
        registry.add("oms.query.retry.multiplier", () -> "2.0");
        registry.add("oms.query.retry.max-retries", () -> "3");
        registry.add("oms.query.schema-registry-timeout", () -> "1s");
        registry.add("oms.query.registry-retry.initial-interval", () -> "300ms");
        registry.add("oms.query.registry-retry.multiplier", () -> "2.0");
        registry.add("oms.query.registry-retry.max-interval", () -> "5s");
        registry.add("oms.query.registry-retry.max-retries", () -> "5");     // more than the 3 normal retries
    }
}
