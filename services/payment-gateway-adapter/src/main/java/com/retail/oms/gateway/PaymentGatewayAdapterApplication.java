package com.retail.oms.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PaymentGatewayAdapterApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentGatewayAdapterApplication.class, args);
    }
}
