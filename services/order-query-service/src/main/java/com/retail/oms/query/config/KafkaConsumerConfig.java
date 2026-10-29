package com.retail.oms.query.config;

import com.retail.oms.query.application.InvalidEventException;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import com.retail.oms.query.infrastructure.messaging.DlqValueSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;

/**
 * Consumer side (architecture 14.2): Avro values through the Schema Registry; a record that fails is retried with
 * exponential backoff and then sent to the dead-letter topic, unless the error is one that retrying cannot fix.
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class KafkaConsumerConfig {

    @Bean
    NewTopic deadLetterTopic(QueryProperties props) {
        return TopicBuilder.name(props.dlqTopic()).partitions(1).replicas(1).build();
    }

    @Bean
    ConsumerFactory<String, Object> consumerFactory(KafkaProperties kafka, QueryProperties props) {
        Map<String, Object> cfg = new HashMap<>(kafka.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // a record that cannot be deserialized (poison pill) must not make the consumer fail forever on the same offset
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ErrorHandlingDeserializer.class);
        cfg.put(ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS, KafkaAvroDeserializer.class);
        cfg.put("schema.registry.url", props.schemaRegistryUrl());
        cfg.put("specific.avro.reader", true);
        cfg.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 10_000);     // find new oms.order.* topics within 10 s
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    KafkaTemplate<String, Object> deadLetterTemplate(KafkaProperties kafka) {
        Map<String, Object> cfg = new HashMap<>(kafka.buildProducerProperties(null));
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, DlqValueSerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> deadLetterTemplate, QueryProperties props,
            MeterRegistry meters) {
        var publisher = new DeadLetterPublishingRecoverer(deadLetterTemplate,
                (record, ex) -> new TopicPartition(props.dlqTopic(), -1));      // -1: let the producer pick the partition
        var retry = props.retry();
        var backoff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backoff.setInitialInterval(retry.initialInterval().toMillis());
        backoff.setMultiplier(retry.multiplier());
        backoff.setMaxInterval(retry.maxInterval().toMillis());
        var handler = new DefaultErrorHandler((record, ex) -> {
            meters.counter("oms.query.events.dead_lettered").increment();
            publisher.accept(record, ex);
        }, backoff);
        handler.addNotRetryableExceptions(InvalidEventException.class);      // plus DeserializationException by default
        return handler;
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory, DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
