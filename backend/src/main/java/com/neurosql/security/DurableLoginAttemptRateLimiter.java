package com.neurosql.security;

/** Shared contract for login and account-request throttling. */
interface DurableLoginAttemptRateLimiter {
    long retryAfterSeconds(String identity);

    /** Existing implementations retain their five-attempt default. */
    void recordFailure(String identity);

    /**
     * Records a failure using a bucket-specific threshold. Implementations that
     * cannot support alternate thresholds remain safe by accepting only five.
     */
    default void recordFailure(String identity, int maxAttempts) {
        if (maxAttempts != 5) {
            throw new UnsupportedOperationException("This rate limiter does not support alternate thresholds.");
        }
        recordFailure(identity);
    }

    void recordSuccess(String identity);
}
