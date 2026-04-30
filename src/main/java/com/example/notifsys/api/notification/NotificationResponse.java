package com.example.notifsys.api.notification;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationStatus;
import com.example.notifsys.domain.notification.NotificationType;

import java.time.Instant;
import java.util.Map;

public record NotificationResponse(
        Long id,
        Long recipientId,
        NotificationType type,
        NotificationChannel channel,
        String eventId,
        Map<String, Object> referenceData,
        NotificationStatus status,
        int failCount,
        int stuckCount,
        Instant retryAfter,
        String failureReason,
        Instant scheduledAt,
        Instant readAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getRecipientId(),
                n.getType(),
                n.getChannel(),
                n.getEventId(),
                n.getReferenceData(),
                n.getStatus(),
                n.getFailCount(),
                n.getStuckCount(),
                n.getRetryAfter(),
                n.getFailureReason(),
                n.getScheduledAt(),
                n.getReadAt(),
                n.getCreatedAt(),
                n.getUpdatedAt()
        );
    }
}