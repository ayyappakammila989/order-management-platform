package com.retail.oms.query.config;

import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.kafka.DeadLetterErrorHandlers;
import com.retail.oms.query.application.InvalidEventException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Consumer side (architecture 14.2). Values arrive as raw bytes and are decoded in the listener
 * ({@link AvroEventDecoder}), so a Schema Registry outage is retried while garbage bytes are dead-lettered at once,
 * and the dead-letter topic always holds the original bytes (replayable with scripts/replay-dlq.sh).
 */
@Configuration(proxyBeanMethods = false)
@EnableKafka
public class KafkaConsumerConfig {

    @Bean
    NewTopic deadLetterTopic(QueryProperties props) {
        return TopicBuilder.name(props.dlqTopic()).partitions(1).replicas(1).build();
    }

    @Bean
    AvroEventDecoder avroEventDecoder(QueryProperties props) {
        return new AvroEventDecoder(props.schemaRegistryUrl(), props.schemaRegistryTimeout());
    }

    @Bean
    ConsumerFactory<String, byte[]> consumerFactory(KafkaProperties kafka) {
        Map<String, Object> cfg = new HashMap<>(kafka.buildConsumerProperties(null));
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);   // cannot fail
        cfg.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, 10_000);     // find new oms.order.* topics within 10 s
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    KafkaTemplate<String, byte[]> deadLetterTemplate(KafkaProperties kafka) {
        Map<String, Object> cfg = new HashMap<>(kafka.buildProducerProperties(null));
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(cfg));
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, byte[]> deadLetterTemplate, QueryProperties props,
            MeterRegistry meters) {
        return DeadLetterErrorHandlers.create(deadLetterTemplate, props.dlqTopic(), props.retry(), props.registryRetry(),
                meters, "oms.query.events", InvalidEventException.class);
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(
            ConsumerFactory<String, byte[]> consumerFactory, DefaultErrorHandler errorHandler) {
        var factory = new ConcurrentKafkaListenerContainerFactory<String, byte[]>();
        factory.setConsumerFactory(consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
