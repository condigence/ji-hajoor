package com.jihajoor.customer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.jihajoor.customer.config.CustomerCryptoProperties;
import com.jihajoor.customer.config.CustomerProperties;

/**
 * Entry point for the Ji-Hajoor Customer Service.
 *
 * <p>Manages customer profiles, saved addresses (with GPS reverse-geocoding), field-level
 * PII encryption, and data-deletion requests. The shared security, observability, and
 * outbox libraries are wired automatically via their Spring Boot auto-configurations
 * simply by being on the classpath (Tasks 4-6).
 *
 * <p>{@code @EnableScheduling} powers the background sweep that anonymizes acknowledged
 * deletion requests once their 30-day window elapses (Requirement 26.9).
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({CustomerProperties.class, CustomerCryptoProperties.class})
public class CustomerServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(CustomerServiceApplication.class, args);
    }
}
