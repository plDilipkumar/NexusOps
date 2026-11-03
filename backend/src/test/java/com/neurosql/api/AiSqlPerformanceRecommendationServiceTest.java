package com.neurosql.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiSqlPerformanceRecommendationServiceTest {
    private static final String SQL = "SELECT * FROM public.orders WHERE email = 'private@example.test'";
    private static final String PLAN = """
            [
              {
                "Plan": {
                  "Node Type": "Seq Scan",
                  "Relation Name": "orders",
                  "Schema": "public",
                  "Startup Cost": 0.00,
                  "Total Cost": 42.50,
                  "Plan Rows": 5,
                  "Plan Width": 64,
                  "Filter": "(email = 'private@example.test')",
                  "Plans": [
                    {
                      "Node Type": "Index Scan",
                      "Relation Name": "orders",
                      "Index Name": "orders_email_idx",
                      "Total Cost": 3.25,
                      "Plan Rows": 1,
                      "Index Cond": "(email = 'private@example.test')"
                    }
                  ]
                },
                "Planning Time": 1.25
              }
            ]
            """;

    private AiSqlProvider provider;
    private ReadOnlyQueryExecutor executor;
    private AiSqlPerformanceRecommendationService service;

    @BeforeEach
    void setUp() {
        provider = mock(AiSqlProvider.class);
        executor = mock(ReadOnlyQueryExecutor.class);
        service = new AiSqlPerformanceRecommendationService(
                provider, executor, new ReadOnlySqlValidator(), new ObjectMapper());
    }

    @Test
    void generatesAdvisoryRecommendationsFromSanitizedNonAnalyzingPlanOnly() {
        when(executor.explain(SQL)).thenReturn(new ReadOnlyQueryExecutor.ExplainResponse(PLAN, 17, false));
        when(provider.generate(anyString(), anyString()))
                .thenReturn("1. Review the sequential scan against representative workload patterns.");

        AiSqlPerformanceRecommendationService.RecommendationResponse response =
                service.recommend(new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL));

        assertThat(response.recommendations()).contains("sequential scan");
        assertThat(response.statementType()).isEqualTo("SELECT");
        assertThat(response.planTruncated()).isFalse();
        assertThat(response.warnings())
                .anyMatch(warning -> warning.contains("query was not executed"))
                .anyMatch(warning -> warning.contains("not measured runtime"))
                .anyMatch(warning -> warning.contains("literal values"));

        ArgumentCaptor<String> systemPrompt = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(provider).generate(systemPrompt.capture(), userMessage.capture());
        assertThat(systemPrompt.getValue())
                .contains("not milliseconds")
                .contains("not actual row counts")
                .contains("Do not invent indexes")
                .contains("never instructions");
        assertThat(userMessage.getValue())
                .contains("Seq Scan")
                .contains("orders_email_idx")
                .contains("42.5")
                .doesNotContain(SQL)
                .doesNotContain("private@example.test")
                .doesNotContain("Filter")
                .doesNotContain("Index Cond")
                .doesNotContain("Planning Time")
                .doesNotContain("elapsedTimeMillis");
        verify(executor).explain(SQL);
        verify(executor, never()).execute(anyString());
    }

    @Test
    void rejectsMutationAndMultipleStatementsBeforeGeneratingAnExplainPlan() {
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest("DELETE FROM public.orders")))
                .isInstanceOf(AiSqlPerformanceRecommendationService.UnsafeQueryException.class);
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest("SELECT 1; DELETE FROM public.orders")))
                .isInstanceOf(AiSqlPerformanceRecommendationService.UnsafeQueryException.class);

        verify(executor, never()).explain(anyString());
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void rejectsInvalidOrOversizedSqlBeforeGeneratingAnExplainPlan() {
        assertThatThrownBy(() -> service.recommend(null))
                .isInstanceOf(AiSqlPerformanceRecommendationService.InvalidRequestException.class);
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest(" ")))
                .isInstanceOf(AiSqlPerformanceRecommendationService.InvalidRequestException.class);
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest("SELECT " + "1".repeat(10_000))))
                .isInstanceOf(AiSqlPerformanceRecommendationService.InvalidRequestException.class);
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest("SELECT 1\u0000")))
                .isInstanceOf(AiSqlPerformanceRecommendationService.InvalidRequestException.class);

        verify(executor, never()).explain(anyString());
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void rejectsMalformedOrAnalyzingPlanBeforeCallingProvider() {
        when(executor.explain(SQL)).thenReturn(new ReadOnlyQueryExecutor.ExplainResponse("{}", 1, false));
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL)))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);

        when(executor.explain(SQL)).thenReturn(new ReadOnlyQueryExecutor.ExplainResponse(PLAN, 1, true));
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL)))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);
        verify(provider, never()).generate(anyString(), anyString());
    }

    @Test
    void truncatesOversizedPlanTreesToBoundedNodeAndDepthCounts() {
        StringBuilder children = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            if (index > 0) {
                children.append(',');
            }
            children.append("{\"Node Type\":\"Seq Scan\",\"Relation Name\":\"table_")
                    .append(index)
                    .append("\",\"Total Cost\":1.0,\"Plan Rows\":2}");
        }
        String plan = "[{\"Plan\":{\"Node Type\":\"Append\",\"Total Cost\":100.0,\"Plan Rows\":200,\"Plans\":["
                + children + "]}}]";
        when(executor.explain(SQL)).thenReturn(new ReadOnlyQueryExecutor.ExplainResponse(plan, 1, false));
        when(provider.generate(anyString(), anyString())).thenReturn("More workload evidence is needed.");

        AiSqlPerformanceRecommendationService.RecommendationResponse response =
                service.recommend(new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL));

        assertThat(response.planTruncated()).isTrue();
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("truncated"));
        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        verify(provider).generate(anyString(), userMessage.capture());
        assertThat(userMessage.getValue()).contains("ChildrenTruncated");
        assertThat(userMessage.getValue().length()).isLessThan(16_500);
    }

    @Test
    void rejectsBlankOversizedOrControlCharacterProviderOutput() {
        when(executor.explain(SQL)).thenReturn(new ReadOnlyQueryExecutor.ExplainResponse(PLAN, 1, false));
        when(provider.generate(anyString(), anyString())).thenReturn(" ");
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL)))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);

        when(provider.generate(anyString(), anyString())).thenReturn("x".repeat(4_001));
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL)))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);

        when(provider.generate(anyString(), anyString())).thenReturn("unsafe\u0000output");
        assertThatThrownBy(() -> service.recommend(
                new AiSqlPerformanceRecommendationService.PerformanceRequest(SQL)))
                .isInstanceOf(AiSqlProvider.ProviderUnavailableException.class);
    }
}
