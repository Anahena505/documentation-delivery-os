package com.d2os.tenancy.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Default (workspace-scoping) security posture. {@link WorkspaceContextFilter} enforces tenant
 * scoping from a verified JWT workspace claim; this class only turns off Spring Boot's default HTTP
 * Basic lockdown that doesn't fit that model.
 *
 * <p>008 US5: this chain is active only when OIDC is OFF ({@code d2os.security.oidc.enabled=false},
 * the default). When it is turned on, {@link OidcSecurityConfig} takes over with per-user identity +
 * role enforcement. Gating the two on one boolean keeps exactly one {@link SecurityFilterChain}
 * active and leaves every existing deployment/test on the unchanged default path.
 */
@Configuration
@ConditionalOnProperty(name = "d2os.security.oidc.enabled", havingValue = "false", matchIfMissing = true)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
