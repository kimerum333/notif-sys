package com.example.notifsys.domain.notification;

import com.example.notifsys.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for the worker dispatch cycle against a real PostgreSQL container.
 *
 * Coverage:
 * - DB-level idempotency: unique constraint blocks duplicate (recipient, event, type, channel).
 * - Concurrent pickup: FOR UPDATE SKIP LOCKED ensures multiple workers process disjoint rowsets,
 *   no row is dispatched twice and none is lost.
 * - Full poll cycle: PENDING success-prefixed event ends as SENT.
 * - Full poll cycle: PENDING fail-transient-prefixed event ends as FAILED with retry_after set.
 * - Full poll cycle: PENDING fail-permanent-prefixed event ends as DEAD_LETTER.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WorkerIntegrationTest {

    @Autowired NotificationRepository notificationRepo;
    @Autowired NotificationErrorLogRepository errorLogRepo;
    @Autowired NotificationPickupService pickupService;
    @Autowired NotificationPoller poller;
    @Autowired WorkerProperties workerProperties;

    @BeforeEach
    void cleanDatabase() {
        errorLogRepo.deleteAllInBatch();
        notificationRepo.deleteAllInBatch();
    }

    @Test
    void uniqueConstraint_blocksDuplicateRecipientEventTypeChannel() {
        Notification first = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("event-dup-1")
                .build();
        notificationRepo.saveAndFlush(first);

        Notification duplicate = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("event-dup-1")
                .build();

        assertThatThrownBy(() -> notificationRepo.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void uniqueConstraint_allowsSameRecipientEventChannelWithDifferentType() {
        notificationRepo.saveAndFlush(Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("shared-event-1")
                .build());

        Notification differentType = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.CANCELLATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("shared-event-1")
                .build();

        // schema-note 결정 6 검증: type 포함 유니크라 동일 event_id에 대해 다른 타입 알림 가능
        notificationRepo.saveAndFlush(differentType);

        assertThat(notificationRepo.count()).isEqualTo(2);
    }

    @Test
    void concurrentPickup_skipLocked_allRowsHandledExactlyOnce() throws Exception {
        int totalRows = 50;
        int workerCount = 5;

        for (int i = 0; i < totalRows; i++) {
            notificationRepo.save(Notification.builder()
                    .recipientId(100L + i)
                    .type(NotificationType.COURSE_REGISTRATION)
                    .channel(NotificationChannel.EMAIL)
                    .eventId("success-concurrent-" + i)
                    .build());
        }
        notificationRepo.flush();

        // batchSize=totalRows: 모든 워커가 모든 row를 노린다. SKIP LOCKED가 없거나 락 가드가 없으면
        // 두 워커가 같은 row를 select → markProcessing의 status guard에서 IllegalStateException.
        ExecutorService exec = Executors.newFixedThreadPool(workerCount);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<List<Long>>> futures = IntStream.range(0, workerCount)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                    return pickupService.pickAndMarkProcessing(totalRows).stream()
                            .map(Notification::getId)
                            .toList();
                }, exec))
                .toList();

        start.countDown();

        Set<Long> allPickedIds = new HashSet<>();
        int totalPicked = 0;
        for (CompletableFuture<List<Long>> f : futures) {
            List<Long> ids = f.get(15, TimeUnit.SECONDS);
            totalPicked += ids.size();
            allPickedIds.addAll(ids);
        }
        exec.shutdown();

        assertThat(totalPicked).isEqualTo(totalRows);
        assertThat(allPickedIds).hasSize(totalRows);

        long processingCount = notificationRepo.findAll().stream()
                .filter(n -> n.getStatus() == NotificationStatus.PROCESSING)
                .count();
        assertThat(processingCount).isEqualTo(totalRows);
    }

    @Test
    void pollCycle_pendingWithSuccessEvent_endsAsSent() {
        Notification saved = notificationRepo.saveAndFlush(Notification.builder()
                .recipientId(1L)
                .type(NotificationType.PAYMENT_CONFIRMED)
                .channel(NotificationChannel.EMAIL)
                .eventId("success-cycle-1")
                .build());

        poller.poll();

        Notification reloaded = notificationRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(reloaded.getFailCount()).isZero();
        assertThat(reloaded.getFailureReason()).isNull();
    }

    @Test
    void pollCycle_pendingWithTransientFailure_endsAsFailedWithRetryAfter() {
        Instant before = Instant.now();

        Notification saved = notificationRepo.saveAndFlush(Notification.builder()
                .recipientId(2L)
                .type(NotificationType.LECTURE_REMINDER)
                .channel(NotificationChannel.EMAIL)
                .eventId("fail-transient-cycle-1")
                .build());

        poller.poll();

        Notification reloaded = notificationRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(reloaded.getFailCount()).isEqualTo(1);
        assertThat(reloaded.getFailureReason()).isNotBlank();
        assertThat(reloaded.getRetryAfter())
                .isNotNull()
                .isAfter(before.plus(workerProperties.baseBackoffSeconds() - 1, ChronoUnit.SECONDS));

        List<NotificationErrorLog> logs = errorLogRepo.findAll();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getNotification().getId()).isEqualTo(saved.getId());
    }

    @Test
    void pollCycle_pendingWithPermanentFailure_endsAsDeadLetter() {
        Notification saved = notificationRepo.saveAndFlush(Notification.builder()
                .recipientId(3L)
                .type(NotificationType.CANCELLATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("fail-permanent-cycle-1")
                .build());

        poller.poll();

        Notification reloaded = notificationRepo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(reloaded.getRetryAfter()).isNull();
        assertThat(reloaded.getFailureReason()).isNotBlank();
    }
}