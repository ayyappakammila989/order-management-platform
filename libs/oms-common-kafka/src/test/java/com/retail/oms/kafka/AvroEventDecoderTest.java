package com.retail.oms.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retail.oms.events.order.OrderStatusChangedEvent;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.net.ServerSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

class AvroEventDecoderTest {

    static final String TOPIC = "oms.order.cancelled";
    final MockSchemaRegistryClient registry = new MockSchemaRegistryClient();
    final AvroEventDecoder decoder = new AvroEventDecoder(registry, "mock://test");

    static OrderStatusChangedEvent event() {
        return OrderStatusChangedEvent.newBuilder().setEventId("e-1").setOccurredAt(Instant.parse("2026-10-29T10:00:00Z"))
                .setOrderId("o-1").setOrderNumber("OMS-20261029-000001").setCustomerId("c-1").setFromStatus("PENDING")
                .setToStatus("CANCELLED").setReason("test").setChangedBy("tester").build();
    }

    byte[] serialized() {
        try (var serializer = new KafkaAvroSerializer(registry)) {
            serializer.configure(Map.of("schema.registry.url", "mock://test"), false);
            return serializer.serialize(TOPIC, event());
        }
    }

    static byte[] withSchemaId(int id, byte[] body) {
        return ByteBuffer.allocate(5 + body.length).put((byte) 0).putInt(id).put(body).array();
    }

    @Test
    void decodesAValidEventIntoTheSpecificClass() {
        Object decoded = decoder.decode(TOPIC, new RecordHeaders(), serialized());
        assertThat(decoded).isInstanceOf(OrderStatusChangedEvent.class).isEqualTo(event());
    }

    @Test
    void bytesThatAreNotConfluentAvroAreAPoisonPill() {
        assertThatThrownBy(() -> decoder.decode(TOPIC, new RecordHeaders(), "this is not avro".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PoisonPillException.class).hasMessageContaining("Unknown magic byte");
    }

    @Test
    void corruptAvroWithAKnownSchemaIsAPoisonPill() {
        byte[] good = serialized();
        byte[] truncated = Arrays.copyOf(good, 12);           // header + schema id are fine, the body is cut off
        assertThatThrownBy(() -> decoder.decode(TOPIC, new RecordHeaders(), truncated))
                .isInstanceOf(PoisonPillException.class);
    }

    @Test
    void schemaNotFoundInTheRegistryIsARegistryFailureNotAPoisonPill() {
        serialized();                                         // the registry knows schema 1 only
        assertThatThrownBy(() -> decoder.decode(TOPIC, new RecordHeaders(), withSchemaId(4711, new byte[] {2, 4})))
                .isInstanceOf(SchemaRegistryUnavailableException.class)
                .satisfies(e -> assertThat(e.getMessage()).containsIgnoringCase("not found"));   // the mock answers 40401
    }

    @Test
    void registryThatRefusesConnectionsIsARegistryFailure() throws Exception {
        int closedPort;
        try (var socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();                // free port, closed again: connection refused
        }
        var real = new AvroEventDecoder("http://localhost:" + closedPort, Duration.ofSeconds(2));
        long start = System.nanoTime();

        assertThatThrownBy(() -> real.decode(TOPIC, new RecordHeaders(), withSchemaId(1, new byte[] {2, 4})))
                .isInstanceOf(SchemaRegistryUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));   // no client-side retries
    }

    @Test
    void recordWithoutValueIsAPoisonPill() {
        assertThatThrownBy(() -> decoder.decode(TOPIC, new RecordHeaders(), null)).isInstanceOf(PoisonPillException.class);
    }

    @Test
    void errorHandlerSeesTheRegistryFailureThroughTheListenerWrapper() {
        var registryDown = new ListenerExecutionFailedException("listener failed",
                new SchemaRegistryUnavailableException("down", new java.net.ConnectException("refused")));
        var poison = new ListenerExecutionFailedException("listener failed", new PoisonPillException("garbage", null));

        assertThat(DeadLetterErrorHandlers.isRegistryError(registryDown)).isTrue();
        assertThat(DeadLetterErrorHandlers.isRegistryError(poison)).isFalse();
    }
}
