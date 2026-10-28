package com.retail.oms.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscoveryServiceApplicationTests {

    @Autowired
    TestRestTemplate rest;

    @Test
    void registryIsEmptyButServedAsJson() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = rest.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("applications");
    }

    @Test
    void dashboardIsServed() {
        ResponseEntity<String> response = rest.getForEntity("/", String.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).contains("Eureka");
    }

    @Test
    void serverDoesNotRegisterItself() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> response = rest.exchange("/eureka/apps", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getBody()).doesNotContain("DISCOVERY-SERVICE");
    }
}
