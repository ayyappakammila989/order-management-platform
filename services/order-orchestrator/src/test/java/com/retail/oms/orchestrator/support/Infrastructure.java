package com.retail.oms.orchestrator.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;

/**
 * Real PostgreSQL, Kafka and Schema Registry; WireMock plays order-service and Keycloak's token endpoint
 * (both are HTTP neighbours of the orchestrator, their own behaviour is tested in their own modules).
 */
public final class Infrastructure {

    static final Network NETWORK = Network.newNetwork();

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("oms_saga").withUsername("oms_saga").withPassword("oms_saga");

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

    public static final WireMockServer HTTP = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        Startables.deepStart(POSTGRES, KAFKA, SCHEMA_REGISTRY).join();
        HTTP.start();
        stubToken();
    }

    private Infrastructure() {
    }

    public static void stubToken() {
        HTTP.stubFor(post(urlPathEqualTo("/realms/oms/protocol/openid-connect/token")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"test-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    }

    public static String schemaRegistryUrl() {
        return "http://" + SCHEMA_REGISTRY.getHost() + ":" + SCHEMA_REGISTRY.getMappedPort(8081);
    }

    /** Short timeouts and backoff so the failure tests run in seconds. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&socketTimeout=3");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("oms.kafka.schema-registry-url", Infrastructure::schemaRegistryUrl);
        registry.add("oms.kafka.schema-registry-timeout", () -> "2s");
        registry.add("oms.kafka.retry.initial-interval", () -> "300ms");
        registry.add("oms.kafka.retry.max-interval", () -> "2s");
        registry.add("oms.kafka.retry.max-retries", () -> "6");
        registry.add("oms.order-service.url", HTTP::baseUrl);
        registry.add("spring.security.oauth2.client.provider.keycloak.token-uri",
                () -> HTTP.baseUrl() + "/realms/oms/protocol/openid-connect/token");
        // longer than the outage test (5 s pause + 3 s socket timeout + backoff), shorter than the awaits
        registry.add("oms.saga.step-timeout", () -> "10s");
        registry.add("oms.saga.timeout-check-interval", () -> "500ms");
        registry.add("oms.outbox.poll-interval", () -> "100ms");
    }
}
