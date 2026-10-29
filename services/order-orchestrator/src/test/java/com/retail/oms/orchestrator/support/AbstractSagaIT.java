package com.retail.oms.orchestrator.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** ONE Spring context for all ITs (one Kafka consumer in the group). */
@SpringBootTest
@AutoConfigureObservability
public abstract class AbstractSagaIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry);
    }
}
