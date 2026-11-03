package com.neurosql.api;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@ConditionalOnExpression(
        "'${neurosql.ai.sql-generation.enabled:false}' == 'true' and "
                + "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true'")
public class AiSqlGenerationConfiguration {
    @Bean
    RestClient aiSqlProviderRestClient(
            @Value("${neurosql.ai.sql-generation.connect-timeout:3s}") Duration connectTimeout,
            @Value("${neurosql.ai.sql-generation.read-timeout:20s}") Duration readTimeout) {
        validateTimeout(connectTimeout, Duration.ofSeconds(10), "connect");
        validateTimeout(readTimeout, Duration.ofSeconds(30), "read");

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    private static void validateTimeout(Duration value, Duration maximum, String kind) {
        if (value == null || value.isZero() || value.isNegative() || value.compareTo(maximum) > 0) {
            throw new IllegalStateException(
                    "AI SQL provider " + kind + " timeout must be positive and within the configured safety bound.");
        }
    }
}
