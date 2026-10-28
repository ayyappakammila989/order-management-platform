package com.retail.oms.common.web;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
class TestApp {

    record CancelRequest(@NotBlank String reason) {
    }

    @Slf4j
    @RestController
    static class DemoController {

        @GetMapping("/demo/oms")
        String oms() {
            throw new OmsException("ORD-409", HttpStatus.CONFLICT, "Order cannot be cancelled after shipment");
        }

        @PostMapping("/demo/body")
        String body(@Valid @RequestBody CancelRequest request) {
            return "ok";
        }

        @GetMapping("/demo/param")
        String param(@RequestParam @NotBlank String name) {
            return name;
        }

        @GetMapping("/demo/boom")
        String boom() {
            throw new IllegalStateException("jdbc:postgresql://secret-host password=hunter2");
        }

        @GetMapping("/demo/denied")
        String denied() {
            throw new AccessDeniedException("needs SUPPORT_SUPERVISOR");
        }

        @GetMapping("/demo/circuit")
        String circuit() {
            throw CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("inventory"));
        }

        @GetMapping("/demo/mdc")
        String mdc() {
            log.info("customer jane.doe@example.com phone +1 415 555 0134 asked");
            return String.valueOf(MDC.get(CorrelationId.MDC_KEY));
        }
    }
}
