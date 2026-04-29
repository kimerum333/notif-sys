package com.example.notifsys.domain.notification;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStateMachineTest {

    @Test
    void newNotification_isPending_withZeroCounters() {
        Notification n = newPending();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getFailCount()).isZero();
        assertThat(n.getStuckCount()).isZero();
        assertThat(n.isRead()).isFalse();
    }

    @Test
    void markProcessing_fromPending_succeeds() {
        Notification n = newPending();
        n.markProcessing();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }

    @Test
    void markProcessing_fromFailed_succeeds() {
        Notification n = newPending();
        n.markProcessing();
        n.markFailed("err", Instant.now().plusSeconds(10));
        n.markProcessing();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PROCESSING);
    }

    @Test
    void markProcessing_fromOtherStates_throws() {
        Notification n = newPending();
        n.markProcessing();
        n.markSent();
        assertThatThrownBy(n::markProcessing).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markSent_fromProcessing_succeeds() {
        Notification n = newPending();
        n.markProcessing();
        n.markSent();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void markSent_notFromProcessing_throws() {
        Notification n = newPending();
        assertThatThrownBy(n::markSent).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void markFailed_setsCounterAndRetryAfter() {
        Notification n = newPending();
        n.markProcessing();
        Instant retry = Instant.parse("2030-01-01T00:00:00Z");
        n.markFailed("network timeout", retry);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getFailCount()).isEqualTo(1);
        assertThat(n.getFailureReason()).isEqualTo("network timeout");
        assertThat(n.getRetryAfter()).isEqualTo(retry);
    }

    @Test
    void markDeadLetter_setsStatusAndIncrementsFailCount() {
        Notification n = newPending();
        n.markProcessing();
        n.markDeadLetter("permanent");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getFailCount()).isEqualTo(1);
        assertThat(n.getRetryAfter()).isNull();
    }

    @Test
    void recoverFromStuck_returnsToPendingAndIncrementsStuck() {
        Notification n = newPending();
        n.markProcessing();
        n.recoverFromStuck();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getStuckCount()).isEqualTo(1);
    }

    @Test
    void markStuckDeadLetter_setsDeadLetterAndIncrementsStuck() {
        Notification n = newPending();
        n.markProcessing();
        n.markStuckDeadLetter("stuck > threshold");
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getStuckCount()).isEqualTo(1);
    }

    @Test
    void markRead_fromSent_setsReadAt() {
        Notification n = newPending();
        n.markProcessing();
        n.markSent();
        n.markRead();
        assertThat(n.isRead()).isTrue();
        assertThat(n.getReadAt()).isNotNull();
    }

    @Test
    void markRead_isIdempotentInMemory() {
        Notification n = newPending();
        n.markProcessing();
        n.markSent();
        n.markRead();
        Instant first = n.getReadAt();
        n.markRead();
        assertThat(n.getReadAt()).isEqualTo(first);
    }

    @Test
    void markRead_notFromSent_throws() {
        Notification n = newPending();
        assertThatThrownBy(n::markRead).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void resetForManualRetry_fromDeadLetter_clearsRetryAndGoesPending() {
        Notification n = newPending();
        n.markProcessing();
        n.markDeadLetter("permanent");
        n.resetForManualRetry();
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getRetryAfter()).isNull();
    }

    @Test
    void resetForManualRetry_doesNotResetCounters() {
        Notification n = newPending();
        n.markProcessing();
        n.markFailed("e1", Instant.now());
        n.markProcessing();
        n.markFailed("e2", Instant.now());
        n.markProcessing();
        n.markDeadLetter("e3");
        int failCountBefore = n.getFailCount();
        n.resetForManualRetry();
        assertThat(n.getFailCount()).isEqualTo(failCountBefore);
    }

    private Notification newPending() {
        return Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt-1")
                .build();
    }
}
