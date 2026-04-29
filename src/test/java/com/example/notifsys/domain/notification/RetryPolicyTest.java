package com.example.notifsys.domain.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final WorkerProperties props = new WorkerProperties(
            5, 3, 60, 5, 1000, 20
    );
    private final RetryPolicy policy = new RetryPolicy(props);

    @Test
    void isExhausted_falseWhileBelowMax() {
        assertThat(policy.isExhausted(0)).isFalse();
        assertThat(policy.isExhausted(3)).isFalse();
    }

    @Test
    void isExhausted_trueWhenNextAttemptHitsMax() {
        // currentFailCount=4, after this attempt fails it becomes 5 == maxFailCount
        assertThat(policy.isExhausted(4)).isTrue();
        assertThat(policy.isExhausted(10)).isTrue();
    }

    @Test
    void nextRetryAt_isAtLeastBaseSeconds() {
        Instant before = Instant.now();
        Instant retry = policy.nextRetryAt(1);
        long delay = retry.getEpochSecond() - before.getEpochSecond();
        // attempt 1: base=5, exponent=0, exponential=5, jitter [0..5] → 5..10
        assertThat(delay).isGreaterThanOrEqualTo(4L).isLessThanOrEqualTo(11L);
    }

    @Test
    void nextRetryAt_exponentialFloorMatchesAttempt() {
        // Floor (without jitter) at attempt N is base * 2^(N-1).
        // Asserting only the floor avoids flakiness from jitter ranges overlapping between attempts.
        Instant before = Instant.now();
        long d1 = policy.nextRetryAt(1).getEpochSecond() - before.getEpochSecond();
        long d2 = policy.nextRetryAt(2).getEpochSecond() - before.getEpochSecond();
        long d3 = policy.nextRetryAt(3).getEpochSecond() - before.getEpochSecond();
        assertThat(d1).isGreaterThanOrEqualTo(5L);   // 5 * 2^0
        assertThat(d2).isGreaterThanOrEqualTo(10L);  // 5 * 2^1
        assertThat(d3).isGreaterThanOrEqualTo(20L);  // 5 * 2^2
    }
}
