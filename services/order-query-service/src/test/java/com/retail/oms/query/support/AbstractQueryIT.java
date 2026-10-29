package com.retail.oms.query.support;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base of the integration tests. They must share ONE Spring context: every context starts a Kafka consumer in the
 * same group, so with two contexts each would receive only some partitions and tests would randomly miss events.
 * (The context cache key includes the annotations and the @DynamicPropertySource methods, hence this one base class.)
 */
@SpringBootTest
@AutoConfigureObservability      // Boot tests switch metrics off otherwise, and the tests read counters
public abstract class AbstractQueryIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        Infrastructure.register(registry);
    }
}
