package com.neurosql.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Small OpenAI-compatible chat-completions adapter. The endpoint, model, and API
 * key are operator-supplied environment values; provider response bodies are
 * deliberately never copied into exceptions or logs.
 */
@Component
@ConditionalOnExpression(
        "'${neurosql.ai.sql-generation.enabled:false}' == 'true' and "
                + "'${neurosql.auth.durable-sessions-enabled:false}' == 'true' and "
                + "'${neurosql.auth.shared-rate-limit-enabled:false}' == 'true'")
public class OpenAiCompatibleSqlProvider implements AiSqlProvider {
    private final RestClient restClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleSqlProvider(
            RestClient aiSqlProviderRestClient,
            @Value("${neurosql.ai.sql-generation.endpoint:}") String endpoint,
            @Value("${neurosql.ai.sql-generation.api-key:}") String apiKey,
            @Value("${neurosql.ai.sql-generation.model:}") String model) {
        this.restClient = aiSqlProviderRestClient;
        this.endpoint = validateEndpoint(endpoint);
        this.apiKey = validateApiKey(apiKey);
        this.model = validateModel(model);
    }

    @Override
    public String generate(String systemPrompt, String userMessage) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "max_tokens", 1200,
                "stream", false,
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)));

        final JsonNode response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException failure) {
            throw new ProviderUnavailableException();
        }

        JsonNode choices = response == null ? null : response.path("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ProviderUnavailableException();
        }
        JsonNode choice = choices.get(0);
        String finishReason = choice.path("finish_reason").asText("");
        if (!finishReason.isEmpty()
                && !"stop".equals(finishReason)
                && !"end_turn".equals(finishReason)) {
            throw new ProviderUnavailableException();
        }
        JsonNode content = choice.path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new ProviderUnavailableException();
        }
        return content.asText();
    }

    private static URI validateEndpoint(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("AI SQL provider HTTPS endpoint must be configured.");
        }
        final URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("AI SQL provider endpoint is invalid.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null) {
            throw new IllegalStateException("AI SQL provider endpoint must be an absolute HTTPS URL without credentials or query parameters.");
        }
        return uri;
    }

    private static String validateApiKey(String value) {
        if (value == null || value.isBlank() || value.length() > 4096
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("AI SQL provider API key must be configured securely.");
        }
        return value;
    }

    private static String validateModel(String value) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("AI SQL provider model must be configured.");
        }
        return value.trim();
    }
}
