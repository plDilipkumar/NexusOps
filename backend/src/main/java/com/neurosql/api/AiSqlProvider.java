package com.neurosql.api;

/** Provider boundary for one-shot, non-streaming SQL-draft generation. */
public interface AiSqlProvider {
    String generate(String systemPrompt, String userMessage);

    final class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException() {
            super("AI SQL generation is temporarily unavailable.");
        }
    }
}
