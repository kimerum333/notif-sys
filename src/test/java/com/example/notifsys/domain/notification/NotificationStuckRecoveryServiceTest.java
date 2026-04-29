package com.example.notifsys.domain.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationStuckRecoveryServiceTest {

    private NotificationRepository repo;
    private NotificationErrorLogRepository errorLogRepo;
    private NotificationStuckRecoveryService service;

    @BeforeEach
    void setup() {
        repo = mock(NotificationRepository.class);
        errorLogRepo = mock(NotificationErrorLogRepository.class);
        WorkerProperties props = new WorkerProperties(5, 3, 60, 5, 1000, 20);
        service = new NotificationStuckRecoveryService(repo, errorLogRepo, props);
    }

    @Test
    void underThreshold_recoversToPendingAndIncrementsStuck() {
        Notification n = makeStuck(1L, 0);
        when(repo.findStuckProcessingForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(n));

        service.recover(20);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getStuckCount()).isEqualTo(1);
        verify(errorLogRepo, never()).save(any());
    }

    @Test
    void atThreshold_marksStuckDeadLetterAndLogsError() {
        // maxStuckCount=3, current stuckCount=2, next will be 3 → dead letter
        Notification n = makeStuck(2L, 2);
        when(repo.findStuckProcessingForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of(n));

        service.recover(20);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getStuckCount()).isEqualTo(3);
        verify(errorLogRepo).save(any(NotificationErrorLog.class));
    }

    @Test
    void emptyResult_returnsZero() {
        when(repo.findStuckProcessingForUpdate(any(Instant.class), anyInt()))
                .thenReturn(List.of());

        int recovered = service.recover(20);

        assertThat(recovered).isZero();
    }

    private Notification makeStuck(Long id, int initialStuckCount) {
        Notification n = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt")
                .build();
        ReflectionTestUtils.setField(n, "id", id);
        ReflectionTestUtils.setField(n, "stuckCount", initialStuckCount);
        n.markProcessing();
        return n;
    }
}
