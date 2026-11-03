package com.neurosql.security;

/** Shared contract for durable-login and account-request throttling. */
interface DurableLoginAttemptRateLimiter {
    long retryAfterSeconds(String identity);

    /**
     * Records a failure and blocks the identity when the rolling-window count
     * reaches {@code maxAttempts}. The database schema must support that cap.
     */
    void recordFailure(String identity, int maxAttempts);

    /** Durable login retains its five-attempt policy. */
    default void recordFailure(String identity) {
        recordFailure(identity, 5);
    }

    void recordSuccess(String identity);
}
