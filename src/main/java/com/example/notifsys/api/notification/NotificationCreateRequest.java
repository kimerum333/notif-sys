package com.example.notifsys.api.notification;

import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Map;

public record NotificationCreateRequest(

        @NotNull(message = "recipientId is required")
        Long recipientId,

        @NotNull(message = "type is required")
        NotificationType type,

        @NotNull(message = "channel is required")
        NotificationChannel channel,

        @NotBlank(message = "eventId is required")
        @Size(max = 100, message = "eventId must be at most 100 characters")
        String eventId,

        Map<String, Object> referenceData,

        // 선택 구현 — 미래 시각이면 그 시각 이후에만 폴러가 픽업 (api-note 결정 6).
        Instant scheduledAt
) {
}