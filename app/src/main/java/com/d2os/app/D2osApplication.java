package com.d2os.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * D2OS Phase 1 bootstrap — single deployable modular monolith (PD-1).
 *
 * <p>Three separate scanning mechanisms all need pointing at {@code com.d2os} explicitly — Spring
 * Boot does NOT infer this from one setting: {@code scanBasePackages} for component scanning,
 * {@code @EntityScan} for JPA entities, and {@code @EnableJpaRepositories} for Spring Data
 * repository interfaces. Omitting any one silently limits it to {@code com.d2os.app} only (found
 * the hard way: the integration test failed on a missing repository bean because only component
 * scanning had been widened).
 */
@SpringBootApplication(scanBasePackages = "com.d2os")
@EntityScan(basePackages = "com.d2os")
@EnableJpaRepositories(basePackages = "com.d2os")
public class D2osApplication {
    public static void main(String[] args) {
        SpringApplication.run(D2osApplication.class, args);
    }
}
