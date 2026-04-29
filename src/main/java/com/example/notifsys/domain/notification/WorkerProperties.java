package com.example.notifsys.domain.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.worker")
public record WorkerProperties(
        int maxFailCount,
        int maxStuckCount,
        int stuckThresholdSeconds,
        int baseBackoffSeconds
) {
}
