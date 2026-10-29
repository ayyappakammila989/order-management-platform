package com.retail.oms.gateway.support;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Real Kafka, Schema Registry and Redis (same images as docker-compose.yml) and one in-process WireMock that plays
 * both gateways (Stripe paths /v1/payment_intents..., PayPal paths /v2/... and /v1/oauth2, /v1/notifications), so
 * the tests can count how often each gateway was really called.
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

    public static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    public static final WireMockServer GATEWAYS = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        Startables.deepStart(KAFKA, SCHEMA_REGISTRY, REDIS).join();
        GATEWAYS.start();
    }

    private Infrastructure() {
    }

    public static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    /** A 2 s time limiter and a breaker that opens after 4 calls keep the failure tests short. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("oms.kafka.schema-registry-url", Infrastructure::schemaRegistryUrl);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("oms.gateway.stripe.base-url", GATEWAYS::baseUrl);
        registry.add("oms.gateway.stripe.mock-mode", () -> "false");
        registry.add("oms.gateway.stripe.webhook-secret", () -> "whsec_it_secret");
        registry.add("oms.gateway.paypal.base-url", GATEWAYS::baseUrl);
        registry.add("oms.gateway.call-timeout", () -> "2s");
        registry.add("oms.gateway.breaker.sliding-window-size", () -> "4");
        registry.add("oms.gateway.breaker.minimum-calls", () -> "4");
        registry.add("oms.gateway.breaker.open-wait", () -> "60s");
        registry.add("oms.gateway.consumers", () -> "1");
    }
}
