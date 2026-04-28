package com.example.notifsys.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.Map;

import static com.example.notifsys.domain.notification.NotificationStatus.DEAD_LETTER;
import static com.example.notifsys.domain.notification.NotificationStatus.FAILED;
import static com.example.notifsys.domain.notification.NotificationStatus.PENDING;
import static com.example.notifsys.domain.notification.NotificationStatus.PROCESSING;
import static com.example.notifsys.domain.notification.NotificationStatus.SENT;

@Entity
@Table(
        name = "notification",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_idempotency",
                columnNames = {"recipient_id", "event_id", "type", "channel"}
        ),
        indexes = {
                @Index(name = "idx_notification_recipient", columnList = "recipient_id"),
                @Index(name = "idx_notification_status", columnList = "status")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private NotificationChannel channel;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_data", columnDefinition = "jsonb")
    private Map<String, Object> referenceData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NotificationStatus status;

    @Column(name = "fail_count", nullable = false)
    private int failCount;

    @Column(name = "stuck_count", nullable = false)
    private int stuckCount;

    @Column(name = "retry_after")
    private Instant retryAfter;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    private Notification(Long recipientId, NotificationType type, NotificationChannel channel,
                         String eventId, Map<String, Object> referenceData, Instant scheduledAt) {
        this.recipientId = recipientId;
        this.type = type;
        this.channel = channel;
        this.eventId = eventId;
        this.referenceData = referenceData;
        this.scheduledAt = scheduledAt;
        this.status = PENDING;
        this.failCount = 0;
        this.stuckCount = 0;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public void markProcessing() {
        requireStatus(PENDING, FAILED);
        this.status = PROCESSING;
    }

    public void markSent() {
        requireStatus(PROCESSING);
        this.status = SENT;
    }

    public void markFailed(String reason, Instant nextRetryAfter) {
        requireStatus(PROCESSING);
        this.status = FAILED;
        this.failCount++;
        this.failureReason = reason;
        this.retryAfter = nextRetryAfter;
    }

    public void markDeadLetter(String reason) {
        requireStatus(PROCESSING);
        this.status = DEAD_LETTER;
        this.failCount++;
        this.failureReason = reason;
        this.retryAfter = null;
    }

    public void recoverFromStuck() {
        requireStatus(PROCESSING);
        this.status = PENDING;
        this.stuckCount++;
    }

    public void markStuckDeadLetter(String reason) {
        requireStatus(PROCESSING);
        this.status = DEAD_LETTER;
        this.stuckCount++;
        this.failureReason = reason;
        this.retryAfter = null;
    }

    public void markRead() {
        if (this.status != SENT) {
            throw new IllegalStateException("Cannot mark as read: status is " + this.status);
        }
        if (this.readAt == null) {
            this.readAt = Instant.now();
        }
    }

    public void resetForManualRetry() {
        requireStatus(DEAD_LETTER);
        this.status = PENDING;
        this.retryAfter = null;
    }

    public boolean isRead() {
        return this.readAt != null;
    }

    private void requireStatus(NotificationStatus... allowed) {
        for (NotificationStatus s : allowed) {
            if (this.status == s) {
                return;
            }
        }
        throw new IllegalStateException(
                "Invalid status transition from " + this.status + " (allowed: " + Arrays.toString(allowed) + ")"
        );
    }
}