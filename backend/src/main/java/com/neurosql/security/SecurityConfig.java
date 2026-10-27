package com.neurosql.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            @Value("${neurosql.auth.enabled:false}") boolean authEnabled) throws Exception {
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
            http.httpBasic(Customizer.withDefaults());
        }
        return http.build();
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
        CorsConfiguration cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(
                "https://neurosql.vercel.app",
                "http://localhost:5173"));
        // POST is used only by the disabled-by-default, authenticated SQL validator.
        cors.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Accept", "Authorization", "Content-Type"));
        cors.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }
}
