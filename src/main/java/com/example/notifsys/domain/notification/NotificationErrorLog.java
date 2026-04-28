package com.example.notifsys.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "notification_error_log")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationErrorLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;

    @Column(name = "error_message", nullable = false, columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public static NotificationErrorLog of(Notification notification, String errorMessage) {
        NotificationErrorLog log = new NotificationErrorLog();
        log.notification = notification;
        log.errorMessage = errorMessage;
        return log;
    }

    @PrePersist
    void onCreate() {
        this.occurredAt = Instant.now();
    }
}