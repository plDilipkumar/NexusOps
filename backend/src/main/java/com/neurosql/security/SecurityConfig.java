package com.neurosql.security;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            @Value("${neurosql.auth.enabled:false}") boolean authEnabled,
            BasicAuthAttemptRateLimiter attemptRateLimiter,
            ObjectProvider<DurableSessionAuthenticationFilter> durableSessionFilter) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers("/api/health", "/api/health/database", "/actuator/health/**").permitAll();
                    if (authEnabled) {
                        auth.requestMatchers("/api/schema/**", "/api/sql/**").hasRole("OWNER");
                    } else {
                        // Sensitive metadata and SQL guardrails must fail closed if auth is off.
                        auth.requestMatchers("/api/schema/**", "/api/sql/**").denyAll();
                    }
                    auth.anyRequest().permitAll();
                });

        if (authEnabled) {
            http.addFilterBefore(
                    new BasicAuthRateLimitFilter(attemptRateLimiter),
                    BasicAuthenticationFilter.class);
            http.httpBasic(Customizer.withDefaults());
        }

        DurableSessionAuthenticationFilter sessionFilter = durableSessionFilter.getIfAvailable();
        if (sessionFilter != null) {
            if (authEnabled) {
                // Keep bearer-session parsing ahead of the Basic-auth rate limiter.
                http.addFilterBefore(sessionFilter, BasicAuthRateLimitFilter.class);
            } else {
                http.addFilterBefore(sessionFilter, BasicAuthenticationFilter.class);
            }
        }
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "neurosql.auth",
            name = "durable-sessions-enabled",
            havingValue = "true")
    DurableSessionAuthenticationFilter durableSessionAuthenticationFilter(
            SessionLifecycleService sessionLifecycleService) {
        return new DurableSessionAuthenticationFilter(sessionLifecycleService);
    }

    @Bean
    BasicAuthAttemptRateLimiter basicAuthAttemptRateLimiter() {
        return new BasicAuthAttemptRateLimiter();
    }

    @Bean
    UserDetailsService ownerUserDetailsService(
            @Value("${neurosql.auth.enabled:false}") boolean authEnabled,
            @Value("${neurosql.auth.username:}") String username,
            @Value("${neurosql.auth.password:}") String password,
            PasswordEncoder passwordEncoder) {
        if (!authEnabled) {
            return new InMemoryUserDetailsManager();
        }
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException(
                    "NEUROSQL_AUTH_USERNAME and NEUROSQL_AUTH_PASSWORD must be configured when API auth is enabled.");
        }
        PasswordPolicy.validate(password);
        return new InMemoryUserDetailsManager(
                User.withUsername(username)
                        .password(passwordEncoder.encode(password))
                        .roles("OWNER")
                        .build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration readOnlyCors = new CorsConfiguration();
        readOnlyCors.setAllowedOrigins(List.of(
                "https://neurosql.vercel.app",
                "http://localhost:5173"));
        readOnlyCors.setAllowedMethods(List.of("GET", "OPTIONS"));
        readOnlyCors.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type"));
        readOnlyCors.setMaxAge(3600L);

        CorsConfiguration validationCors = new CorsConfiguration(readOnlyCors);
        validationCors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Register the more-specific route first so POST is not allowed on unrelated API paths.
        source.registerCorsConfiguration("/api/sql/**", validationCors);
        source.registerCorsConfiguration("/api/**", readOnlyCors);
        return source;
    }
}
