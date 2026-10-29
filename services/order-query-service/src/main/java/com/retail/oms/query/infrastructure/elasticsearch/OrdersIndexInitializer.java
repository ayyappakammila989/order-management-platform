package com.retail.oms.query.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.retail.oms.query.config.QueryProperties;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Creates orders-v1 (strict mapping, alias "orders") when it does not exist. It runs while the beans are created
 * (ElasticsearchOrderIndex depends on it), so it is done before any Kafka listener starts. Without it the first
 * scripted upsert would make Elasticsearch invent an index called "orders" with a guessed mapping.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrdersIndexInitializer implements InitializingBean {

    private final ElasticsearchClient client;
    private final QueryProperties props;

    @Override
    public void afterPropertiesSet() throws Exception {
        ensureIndex();
    }

    public void ensureIndex() throws Exception {
        if (client.indices().exists(e -> e.index(props.index())).value()) {
            return;
        }
        try (InputStream mappings = new ClassPathResource("elasticsearch/orders-v1-mappings.json").getInputStream()) {
            client.indices().create(c -> c.index(props.index())
                    .settings(s -> s.numberOfShards("1").numberOfReplicas("0"))
                    .mappings(m -> m.withJson(mappings))
                    .aliases(props.alias(), a -> a));
            log.info("Created Elasticsearch index {} with alias {}", props.index(), props.alias());
        } catch (ElasticsearchException e) {
            if (!"resource_already_exists_exception".equals(e.error().type())) {      // another instance was faster
                throw e;
            }
        }
    }
}
