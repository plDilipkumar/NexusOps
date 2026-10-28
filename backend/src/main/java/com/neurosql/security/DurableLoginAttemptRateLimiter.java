package com.neurosql.security;

/** Shared contract for durable-login throttling; Basic auth keeps its own limiter instance. */
interface DurableLoginAttemptRateLimiter {
    long retryAfterSeconds(String identity);

    void recordFailure(String identity);

    void recordSuccess(String identity);
}
