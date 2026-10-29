package com.retail.oms.fraud.config;

import com.retail.oms.events.Topics;
import com.retail.oms.fraud.application.FraudProperties;
import com.retail.oms.fraud.application.InvalidMessageException;
import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.kafka.DeadLetterErrorHandlers;
import com.retail.oms.kafka.outbox.Outbox;
import com.retail.oms.kafka.outbox.OutboxRelay;
import com.retail.oms.kafka.outbox.ProcessedMessages;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Kafka in and out. In: raw bytes decoded in the listener, retry with backoff, dead-letter topic
 * (libs/oms-common-kafka). Out: only through the transactional outbox and its relay.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class MessagingConfig {

    /** Every topic this service reads or writes, created if missing (Compose has auto-create off). */
    @Bean
    KafkaAdmin.NewTopics fraudTopics(FraudProperties props) {
        int p = props.topicPartitions();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.FRAUD_CHECK_CMD).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.FRAUD_CANCEL_CMD).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.FRAUD_APPROVED).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.FRAUD_REVIEW).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.FRAUD_REJECTED).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.FRAUD_CANCELLED).partitions(p).replicas(1).build(),
                TopicBuilder.name(props.dlqTopic()).partitions(1).replicas(1).build());
    }

    @Bean
    AvroEventDecoder avroEventDecoder(MessagingProperties props) {
        return new AvroEventDecoder(props.schemaRegistryUrl(), props.schemaRegistryTimeout());
    }

    @Bean
    ConsumerFactory<String, byte[]> consumerFactory(KafkaProperties kafka) {
        Map<String, Object> cfg = new HashMap<>(kafka.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    /** Used by the outbox relay and the dead-letter publisher: bytes in, bytes out. */
    @Bean
    KafkaTemplate<String, byte[]> bytesTemplate(KafkaProperties kafka) {
        Map<String, Object> cfg = new HashMap<>(kafka.buildProducerProperties(null));
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, byte[]> bytesTemplate, FraudProperties fraud,
            MessagingProperties props, MeterRegistry meters) {
        return DeadLetterErrorHandlers.create(bytesTemplate, fraud.dlqTopic(), props.retry(), props.registryRetry(),
                meters, "oms.fraud.messages", InvalidMessageException.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory, DefaultErrorHandler errorHandler, MessagingProperties props) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        factory.setConcurrency(props.listenerConcurrency());
        return factory;
    }

    @Bean
    Outbox outbox(JdbcTemplate jdbc, MessagingProperties props, Clock clock) {
        return new Outbox(jdbc, props.schemaRegistryUrl(), props.schemaRegistryTimeout(), clock);
    }

    @Bean
    ProcessedMessages processedMessages(JdbcTemplate jdbc) {
        return new ProcessedMessages(jdbc);
    }

    @Bean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, KafkaTemplate<String, byte[]> bytesTemplate,
            OutboxProperties props, Clock clock, MeterRegistry meters) {
        return new OutboxRelay(jdbc, tx, bytesTemplate, props.batchSize(), props.sendTimeout(), clock, meters);
    }
}
