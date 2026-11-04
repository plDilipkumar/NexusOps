package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleSqlProviderTest {
    private static final String ENDPOINT = "https://provider.example.test/v1/chat/completions";
    private static final String API_KEY = "test-provider-secret";

    @Test
    void sendsBoundedNonStreamingChatCompletionRequest() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleSqlProvider provider =
                new OpenAiCompatibleSqlProvider(builder.build(), new ObjectMapper(), ENDPOINT, "  " + API_KEY + "  ", "test-model");

        server.expect(requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(content().json("""
                        {
                          "model": "test-model",
                          "temperature": 0,
                          "max_tokens": 1200,
                          "stream": false,
                          "messages": [
                            {"role":"system","content":"system rules"},
                            {"role":"user","content":"user request and schema"}
                          ]
                        }
                        """))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"stop","message":{"content":"SELECT 1"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThat(provider.generate("system rules", "user request and schema")).isEqualTo("SELECT 1");
        server.verify();
    }

    @Test
    void providerErrorsAreRedactedAndNotRetried() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleSqlProvider provider =
                new OpenAiCompatibleSqlProvider(builder.build(), ENDPOINT, API_KEY, "test-model");

        server.expect(requestTo(ENDPOINT))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"private-provider-detail\",\"request\":\"" + API_KEY + "\"}"));

        assertThatThrownBy(() -> provider.generate("system", "user"))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class)
                .hasMessage("AI SQL generation is temporarily unavailable.")
                .hasMessageNotContaining("private-provider-detail")
                .hasMessageNotContaining(API_KEY)
                .hasNoCause();
        server.verify();
    }

    @Test
    void providerEndpointMustUseHttpsAndCannotEmbedCredentialsOrQueryParameters() {
        RestClient client = RestClient.builder().build();
        assertThatThrownBy(() -> new OpenAiCompatibleSqlProvider(
                client, new ObjectMapper(), "http://provider.example.test/chat", API_KEY, "test-model"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OpenAiCompatibleSqlProvider(
                client, "https://user:password@provider.example.test/chat", API_KEY, "test-model"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new OpenAiCompatibleSqlProvider(
                client, "https://provider.example.test/chat?key=secret", API_KEY, "test-model"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsTruncatedProviderResponses() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleSqlProvider provider =
                new OpenAiCompatibleSqlProvider(builder.build(), ENDPOINT, API_KEY, "test-model");

        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("""
                        {"choices":[{"finish_reason":"length","message":{"content":"SELECT"}}]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate("system", "user"))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);
        server.verify();
    }
    @Test
    void rejectsMissingFinishReasonAndOversizedProviderContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleSqlProvider provider =
                new OpenAiCompatibleSqlProvider(builder.build(), ENDPOINT, API_KEY, "test-model");
        String oversized = "x".repeat(OpenAiCompatibleSqlProvider.MAX_PROVIDER_CONTENT_CHARACTERS + 1);

        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"SELECT 1"}}]}
                        """, MediaType.APPLICATION_JSON));
        String oversizedResponse = """
                {"choices":[{"finish_reason":"stop","message":{"content":"%s"}}]}
                """.formatted(oversized);
        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(oversizedResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate("system", "user"))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);
        assertThatThrownBy(() -> provider.generate("system", "user"))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);
        server.verify();
    }

 
    @Test
    void rejectsOversizedProviderResponseBodyBeforeJsonParsing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleSqlProvider provider =
                new OpenAiCompatibleSqlProvider(builder.build(), new ObjectMapper(), ENDPOINT, API_KEY, "test-model");
        String oversizedResponse = "{\"providerMetadata\":\"" + "x".repeat(
                OpenAiCompatibleSqlProvider.MAX_PROVIDER_RESPONSE_BYTES)
                + "\",\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"SELECT 1\"}}]}";

        server.expect(requestTo(ENDPOINT))
                .andRespond(withSuccess(oversizedResponse, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.generate("system", "user"))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class)
                .hasMessageNotContaining(API_KEY)
                .hasNoCause();
        server.verify();
    }

    @Test
    void providerConnectAndReadTimeoutsHaveStrictUpperBounds() {
        AiSqlGenerationConfiguration configuration = new AiSqlGenerationConfiguration();

        assertThat(configuration.aiSqlProviderRestClient(
                Duration.ofSeconds(3), Duration.ofSeconds(20))).isNotNull();
        assertThatThrownBy(() -> configuration.aiSqlProviderRestClient(
                Duration.ofSeconds(11), Duration.ofSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> configuration.aiSqlProviderRestClient(
                Duration.ofSeconds(3), Duration.ofSeconds(31)))
                .isInstanceOf(IllegalStateException.class);
    }

}
