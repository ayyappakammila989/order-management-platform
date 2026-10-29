package com.retail.oms.gateway.config;

import com.retail.oms.events.Topics;
import com.retail.oms.kafka.AvroEventDecoder;
import com.retail.oms.gateway.application.AvroBytes;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/** Kafka itself is used through Camel; Spring Kafka's admin only creates the topics (Compose has auto-create off). */
@Configuration(proxyBeanMethods = false)
public class MessagingConfig {

    @Bean
    KafkaAdmin.NewTopics gatewayTopics(GatewayProperties props) {
        int p = props.topicPartitions();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(Topics.PAYMENT_GATEWAY_CMD).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.PAYMENT_GATEWAY_RESULT).partitions(p).replicas(1).build(),
                TopicBuilder.name(Topics.PAYMENT_GATEWAY_EVENT).partitions(p).replicas(1).build(),
                TopicBuilder.name(props.dlqTopic()).partitions(1).replicas(1).build());
    }

    @Bean
    AvroEventDecoder avroEventDecoder(@Value("${oms.kafka.schema-registry-url}") String url,
            @Value("${oms.kafka.schema-registry-timeout}") Duration timeout) {
        return new AvroEventDecoder(url, timeout);
    }

    @Bean
    AvroBytes avroBytes(@Value("${oms.kafka.schema-registry-url}") String url,
            @Value("${oms.kafka.schema-registry-timeout}") Duration timeout) {
        return new AvroBytes(url, timeout);
    }
}
