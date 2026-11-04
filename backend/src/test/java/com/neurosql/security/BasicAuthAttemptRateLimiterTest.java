package com.neurosql.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BasicAuthAttemptRateLimiterTest {
    private static final Instant START = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void blocksAfterFiveFailuresAndReportsRetryDelay() {
        MutableClock clock = new MutableClock(START);
        BasicAuthAttemptRateLimiter limiter = new BasicAuthAttemptRateLimiter(clock);

        for (int attempt = 0; attempt < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS; attempt++) {
            limiter.recordFailure("Owner");
        }

        assertThat(limiter.isBlocked("owner")).isTrue();
        assertThat(limiter.retryAfterSeconds("owner")).isEqualTo(900);
    }

    @Test
    void ignoresFailuresOlderThanTheRollingWindow() {
        MutableClock clock = new MutableClock(START);
        BasicAuthAttemptRateLimiter limiter = new BasicAuthAttemptRateLimiter(clock);

        for (int attempt = 0; attempt < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS - 1; attempt++) {
            limiter.recordFailure("owner");
        }
        clock.advance(BasicAuthAttemptRateLimiter.FAILURE_WINDOW.plusSeconds(1));
        limiter.recordFailure("owner");

        assertThat(limiter.isBlocked("owner")).isFalse();
    }

    @Test
    void successfulAuthenticationClearsPriorFailures() {
        MutableClock clock = new MutableClock(START);
        BasicAuthAttemptRateLimiter limiter = new BasicAuthAttemptRateLimiter(clock);

        for (int attempt = 0; attempt < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS - 1; attempt++) {
            limiter.recordFailure("owner");
        }
        limiter.recordSuccess("owner");
        for (int attempt = 0; attempt < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS - 1; attempt++) {
            limiter.recordFailure("owner");
        }

        assertThat(limiter.isBlocked("owner")).isFalse();
    }

    @Test
    void lockoutExpiresAfterFifteenMinutes() {
        MutableClock clock = new MutableClock(START);
        BasicAuthAttemptRateLimiter limiter = new BasicAuthAttemptRateLimiter(clock);
        for (int attempt = 0; attempt < BasicAuthAttemptRateLimiter.MAX_FAILED_ATTEMPTS; attempt++) {
            limiter.recordFailure("owner");
        }

        clock.advance(BasicAuthAttemptRateLimiter.BLOCK_DURATION);
        assertThat(limiter.isBlocked("owner")).isFalse();
        assertThat(limiter.retryAfterSeconds("owner")).isZero();
    }

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(current, zone);
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }
    }
}
