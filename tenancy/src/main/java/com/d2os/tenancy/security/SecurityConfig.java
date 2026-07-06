package com.d2os.tenancy.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Explicit v1 security posture: real authentication/authorization is deferred (see
 * {@link WorkspaceContextFilter}'s own javadoc — header-based tenant scoping stands in for it in
 * Phase 1). Without this bean, Spring Boot's default security auto-configuration would lock every
 * endpoint behind HTTP Basic with a randomly generated password (found the hard way: every request
 * in the integration test came back 401). {@link WorkspaceContextFilter} is what actually enforces
 * workspace scoping here, not this class — this class only turns off a default that doesn't fit v1.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
