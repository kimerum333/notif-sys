package com.example.notifsys.api.notification;

import com.example.notifsys.TestcontainersConfiguration;
import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationErrorLogRepository;
import com.example.notifsys.domain.notification.NotificationOutcomeRecorder;
import com.example.notifsys.domain.notification.NotificationPickupService;
import com.example.notifsys.domain.notification.NotificationRepository;
import com.example.notifsys.domain.notification.NotificationStatus;
import com.example.notifsys.domain.notification.NotificationType;
import com.example.notifsys.domain.notification.sender.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 통합 테스트: GET /{id}, GET /users/{id}/notifications, PATCH /{id}/read, POST /{id}/retry.
 *
 * 데이터는 NotificationRepository에 직접 영속화한 뒤 OutcomeRecorder를 통해 상태 전이시킴
 * (markSent / markFailed 등 도메인 mutator는 package-private이라 테스트가 직접 호출하지 않음).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationControllerQueryAndActionsTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository repo;
    @Autowired NotificationErrorLogRepository errorLogRepo;
    @Autowired NotificationPickupService pickupService;
    @Autowired NotificationOutcomeRecorder outcomeRecorder;

    @BeforeEach
    void cleanDatabase() {
        errorLogRepo.deleteAllInBatch();
        repo.deleteAllInBatch();
    }

    // ===== GET /{id} =====

    @Test
    void getById_existingId_returns200WithEnvelope() throws Exception {
        Notification saved = repo.saveAndFlush(buildPending(1L, "evt-get-1"));

        mvc.perform(get("/notifications/{id}", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value(saved.getId()))
                .andExpect(jsonPath("$.data.recipientId").value(1))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void getById_nonexistent_returns404() throws Exception {
        mvc.perform(get("/notifications/{id}", 999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    // ===== GET /users/{userId}/notifications =====

    @Test
    void listByUser_returnsPagedEnvelope() throws Exception {
        for (int i = 0; i < 5; i++) {
            repo.save(buildPending(7L, "evt-list-" + i));
        }
        repo.saveAndFlush(buildPending(99L, "evt-other-user"));

        mvc.perform(get("/users/{userId}/notifications", 7L)
                        .header("X-User-Id", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.totalElements").value(5))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void listByUser_unreadFilter_returnsOnlyUnread() throws Exception {
        Notification a = repo.saveAndFlush(buildPending(8L, "evt-r-1"));
        Notification b = repo.saveAndFlush(buildPending(8L, "evt-r-2"));
        markSent(a.getId());
        markSent(b.getId());
        markRead(a.getId(), 8L);

        mvc.perform(get("/users/{userId}/notifications", 8L)
                        .header("X-User-Id", "8")
                        .param("read", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(b.getId()));
    }

    @Test
    void listByUser_readFilter_returnsOnlyRead() throws Exception {
        Notification a = repo.saveAndFlush(buildPending(9L, "evt-r-1"));
        Notification b = repo.saveAndFlush(buildPending(9L, "evt-r-2"));
        markSent(a.getId());
        markSent(b.getId());
        markRead(a.getId(), 9L);

        mvc.perform(get("/users/{userId}/notifications", 9L)
                        .header("X-User-Id", "9")
                        .param("read", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(a.getId()));
    }

    @Test
    void listByUser_userIdMismatch_returns403() throws Exception {
        repo.saveAndFlush(buildPending(10L, "evt-1"));

        mvc.perform(get("/users/{userId}/notifications", 10L)
                        .header("X-User-Id", "11"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void listByUser_missingHeader_returns400() throws Exception {
        mvc.perform(get("/users/{userId}/notifications", 10L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MISSING_USER_ID"));
    }

    // ===== PATCH /{id}/read =====

    @Test
    void markRead_sentNotificationByOwner_returns200WithReadAt() throws Exception {
        Notification saved = repo.saveAndFlush(buildPending(20L, "evt-read-1"));
        markSent(saved.getId());

        mvc.perform(patch("/notifications/{id}/read", saved.getId())
                        .header("X-User-Id", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(saved.getId()))
                .andExpect(jsonPath("$.data.readAt").exists());

        Notification reloaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getReadAt()).isNotNull();
    }

    @Test
    void markRead_notOwner_returns403() throws Exception {
        Notification saved = repo.saveAndFlush(buildPending(21L, "evt-read-2"));
        markSent(saved.getId());

        mvc.perform(patch("/notifications/{id}/read", saved.getId())
                        .header("X-User-Id", "22"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void markRead_pendingNotification_returns409InvalidState() throws Exception {
        Notification saved = repo.saveAndFlush(buildPending(23L, "evt-read-3"));

        mvc.perform(patch("/notifications/{id}/read", saved.getId())
                        .header("X-User-Id", "23"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void markRead_nonexistent_returns404() throws Exception {
        mvc.perform(patch("/notifications/{id}/read", 999_999L)
                        .header("X-User-Id", "1"))
                .andExpect(status().isNotFound());
    }

    // ===== POST /{id}/retry =====

    @Test
    void manualRetry_deadLetter_resetsToPendingPreservingFailCount() throws Exception {
        // DEAD_LETTER 상태로 만들기: PROCESSING으로 픽업한 뒤 PERMANENT 실패로 기록.
        Notification saved = repo.saveAndFlush(buildPending(30L, "evt-retry-1"));
        pickupService.pickAndMarkProcessing(10);
        outcomeRecorder.record(saved.getId(),
                new SendResult.Failure(com.example.notifsys.domain.notification.sender.FailureKind.PERMANENT,
                        "permanent for retry test"));

        Notification beforeRetry = repo.findById(saved.getId()).orElseThrow();
        assertThat(beforeRetry.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        int failCountBefore = beforeRetry.getFailCount();

        mvc.perform(post("/notifications/{id}/retry", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.retryAfter").doesNotExist());

        Notification reloaded = repo.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(reloaded.getRetryAfter()).isNull();
        // policy-note 결정 1: 카운터 미리셋
        assertThat(reloaded.getFailCount()).isEqualTo(failCountBefore);
    }

    @Test
    void manualRetry_pendingNotification_returns409InvalidState() throws Exception {
        Notification saved = repo.saveAndFlush(buildPending(31L, "evt-retry-2"));

        mvc.perform(post("/notifications/{id}/retry", saved.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_STATE"));
    }

    @Test
    void manualRetry_nonexistent_returns404() throws Exception {
        mvc.perform(post("/notifications/{id}/retry", 999_999L))
                .andExpect(status().isNotFound());
    }

    // ===== Helpers =====

    private Notification buildPending(long recipient, String eventId) {
        return Notification.builder()
                .recipientId(recipient)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId(eventId)
                .build();
    }

    /** PROCESSING 거쳐 SENT까지 옮겨주는 헬퍼. */
    @Transactional
    void markSent(Long id) {
        pickupService.pickAndMarkProcessing(50);
        outcomeRecorder.record(id, new SendResult.Success());
    }

    /** PATCH /read를 거치지 않고 readAt 채우는 헬퍼 (테스트 setup용). */
    private void markRead(Long id, Long userId) throws Exception {
        mvc.perform(patch("/notifications/{id}/read", id)
                        .header("X-User-Id", String.valueOf(userId)))
                .andExpect(status().isOk());
    }
}