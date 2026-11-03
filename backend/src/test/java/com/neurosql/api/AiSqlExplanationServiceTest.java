package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiSqlExplanationServiceTest {
    private AiSqlProvider provider;
    private AiSqlExplanationService service;

    @BeforeEach
    void setUp() {
        provider = mock(AiSqlProvider.class);
        service = new AiSqlExplanationService(provider, new ReadOnlySqlValidator());
    }

    @Test
    void explainsOneReadOnlyStatementWithoutExecutingIt() {
        String sql = "SELECT status, count(*) FROM public.orders GROUP BY status";
        when(provider.generate(anyString(), anyString()))
                .thenReturn("The query counts orders for each status.");

        AiSqlExplanationService.ExplainResponse response =
                service.explain(new AiSqlExplanationService.ExplainRequest(sql));

        assertThat(response.explanation()).isEqualTo("The query counts orders for each status.");
        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("not a verified database result"));
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("SQL text is sent"));
        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(provider).generate(systemPrompt.capture(), userMessage.capture());
        assertThat(systemPrompt.getValue()).contains("untrusted data").contains("Do not generate")
                .contains("Never claim the query was executed");
        assertThat(userMessage.getValue()).contains(sql).contains("untrusted SQL text");
    }

    @Test
    void rejectsMutationsAndMultipleStatementsBeforeCallingProvider() {
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("DELETE FROM public.orders")))
                .isInstanceOf(AiSqlExplanationService.UnsafeQueryException.class);
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("SELECT 1; DELETE FROM public.orders")))
                .isInstanceOf(AiSqlExplanationService.UnsafeQueryException.class);
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void rejectsInvalidOrOversizedInputBeforeCallingProvider() {
        assertThatThrownBy(() -> service.explain(null))
                .isInstanceOf(AiSqlExplanationService.InvalidRequestException.class);
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("  ")))
                .isInstanceOf(AiSqlExplanationService.InvalidRequestException.class);
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("SELECT " + "1".repeat(10_000))))
                .isInstanceOf(AiSqlExplanationService.InvalidRequestException.class);
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("SELECT 1\u0000")))
                .isInstanceOf(AiSqlExplanationService.InvalidRequestException.class);
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void rejectsBlankOversizedOrControlCharacterProviderOutput() {
        when(provider.generate(anyString(), anyString())).thenReturn(" ");
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("SELECT 1")))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);

        when(provider.generate(anyString(), anyString())).thenReturn("x".repeat(4_001));
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("SELECT 1")))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);

        when(provider.generate(anyString(), anyString())).thenReturn("invalid\u0000output");
        assertThatThrownBy(() -> service.explain(new AiSqlExplanationService.ExplainRequest("SELECT 1")))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);
    }
}
