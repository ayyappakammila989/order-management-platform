package com.retail.oms.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigServerApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(String path) {
        ResponseEntity<Map> response = rest.getForEntity(path, Map.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        return response.getBody();
    }

    @Test
    void servesSharedApplicationYmlToAnyService() {
        Map<String, Object> env = fetch("/order-service/default");
        assertThat(env.get("name")).isEqualTo("order-service");
        assertThat(env.toString())
                .contains("eureka.client.service-url.defaultZone")
                .contains("http://localhost:8761/eureka/");
    }

    @Test
    void profileSpecificFileOverridesShared() {
        Map<String, Object> env = fetch("/order-service/qa");
        assertThat(env.toString()).contains("application-qa.yml");
    }

    @Test
    void unknownServiceStillGetsSharedConfigAsPlain200() {
        Map<String, Object> env = fetch("/does-not-exist/default");
        assertThat(env.toString()).contains("oms.environment");
    }
}
