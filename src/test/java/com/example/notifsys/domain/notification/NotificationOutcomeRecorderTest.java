package com.example.notifsys.domain.notification;

import com.example.notifsys.domain.notification.sender.FailureKind;
import com.example.notifsys.domain.notification.sender.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationOutcomeRecorderTest {

    private NotificationRepository notificationRepo;
    private NotificationErrorLogRepository errorLogRepo;
    private RetryPolicy retryPolicy;
    private NotificationOutcomeRecorder recorder;

    @BeforeEach
    void setup() {
        notificationRepo = mock(NotificationRepository.class);
        errorLogRepo = mock(NotificationErrorLogRepository.class);
        retryPolicy = mock(RetryPolicy.class);
        recorder = new NotificationOutcomeRecorder(notificationRepo, errorLogRepo, retryPolicy);
    }

    @Test
    void successResult_marksSentAndDoesNotLogError() {
        Notification n = makeProcessing(10L, 0);
        when(notificationRepo.findById(10L)).thenReturn(Optional.of(n));

        recorder.record(10L, new SendResult.Success());

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        verify(errorLogRepo, never()).save(any());
    }

    @Test
    void transientFailure_underLimit_marksFailedAndLogsError() {
        Notification n = makeProcessing(11L, 0);
        when(notificationRepo.findById(11L)).thenReturn(Optional.of(n));
        when(retryPolicy.isExhausted(0)).thenReturn(false);
        when(retryPolicy.nextRetryAt(1)).thenReturn(Instant.parse("2030-01-01T00:00:00Z"));

        recorder.record(11L, new SendResult.Failure(FailureKind.TRANSIENT, "timeout"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(n.getFailCount()).isEqualTo(1);
        assertThat(n.getRetryAfter()).isEqualTo(Instant.parse("2030-01-01T00:00:00Z"));
        verify(errorLogRepo).save(any(NotificationErrorLog.class));
    }

    @Test
    void permanentFailure_marksDeadLetterImmediatelyRegardlessOfRetryPolicy() {
        Notification n = makeProcessing(12L, 0);
        when(notificationRepo.findById(12L)).thenReturn(Optional.of(n));

        recorder.record(12L, new SendResult.Failure(FailureKind.PERMANENT, "invalid recipient"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        verify(errorLogRepo).save(any(NotificationErrorLog.class));
    }

    @Test
    void exhaustedTransientFailure_marksDeadLetter() {
        Notification n = makeProcessing(13L, 4);
        when(notificationRepo.findById(13L)).thenReturn(Optional.of(n));
        when(retryPolicy.isExhausted(4)).thenReturn(true);

        recorder.record(13L, new SendResult.Failure(FailureKind.TRANSIENT, "still failing"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        verify(errorLogRepo).save(any(NotificationErrorLog.class));
    }

    private Notification makeProcessing(Long id, int initialFailCount) {
        Notification n = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt")
                .build();
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "failCount", initialFailCount);
        n.markProcessing();
        return n;
    }
}
