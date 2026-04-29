package com.example.notifsys.domain.notification;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {

    private final WorkerProperties properties;

    public RetryPolicy(WorkerProperties properties) {
        this.properties = properties;
    }

    public boolean isExhausted(int currentFailCount) {
        return currentFailCount + 1 >= properties.maxFailCount();
    }

    public Instant nextRetryAt(int newFailCount) {
        long base = properties.baseBackoffSeconds();
        int exponent = Math.min(newFailCount - 1, 30);
        long exponential = base * (1L << exponent);
        long jitter = ThreadLocalRandom.current().nextLong(base + 1);
        return Instant.now().plusSeconds(exponential + jitter);
    }
}