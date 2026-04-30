package com.example.notifsys.api.notification;

import com.example.notifsys.TestcontainersConfiguration;
import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationErrorLogRepository;
import com.example.notifsys.domain.notification.NotificationRepository;
import com.example.notifsys.domain.notification.NotificationStatus;
import com.example.notifsys.domain.notification.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test for POST /notifications.
 *
 * Covers: happy path 202, validation 400, idempotency conflict 409, JSON serialization of envelope.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class NotificationControllerCreateTest {

    @Autowired MockMvc mvc;
    @Autowired NotificationRepository notificationRepo;
    @Autowired NotificationErrorLogRepository errorLogRepo;

    @BeforeEach
    void cleanDatabase() {
        errorLogRepo.deleteAllInBatch();
        notificationRepo.deleteAllInBatch();
    }

    @Test
    void createNotification_happyPath_returns202WithEnvelope() throws Exception {
        String body = """
                {
                  "recipientId": 1,
                  "type": "COURSE_REGISTRATION",
                  "channel": "EMAIL",
                  "eventId": "course-001",
                  "referenceData": { "lectureId": 42, "lectureName": "Spring Boot" }
                }
                """;

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").doesNotExist())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.recipientId").value(1))
                .andExpect(jsonPath("$.data.type").value("COURSE_REGISTRATION"))
                .andExpect(jsonPath("$.data.channel").value("EMAIL"))
                .andExpect(jsonPath("$.data.eventId").value("course-001"))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.failCount").value(0))
                .andExpect(jsonPath("$.data.referenceData.lectureId").value(42))
                .andExpect(jsonPath("$.timestamp").exists());

        assertThat(notificationRepo.count()).isEqualTo(1);
    }

    @Test
    void createNotification_withScheduledAt_persistsScheduledTime() throws Exception {
        String body = """
                {
                  "recipientId": 2,
                  "type": "LECTURE_REMINDER",
                  "channel": "IN_APP",
                  "eventId": "scheduled-001",
                  "referenceData": { "lectureName": "Test", "startAt": "2026-12-01T10:00:00Z" },
                  "scheduledAt": "2026-12-01T09:30:00Z"
                }
                """;

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.scheduledAt").value("2026-12-01T09:30:00Z"));
    }

    @Test
    void createNotification_missingRequiredFields_returns400WithFieldErrors() throws Exception {
        // recipientId, type, channel, eventId 모두 결손
        String body = """
                { "referenceData": {} }
                """;

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.recipientId").exists())
                .andExpect(jsonPath("$.error.details.type").exists())
                .andExpect(jsonPath("$.error.details.channel").exists())
                .andExpect(jsonPath("$.error.details.eventId").exists());
    }

    @Test
    void createNotification_blankEventId_returns400() throws Exception {
        String body = """
                {
                  "recipientId": 1,
                  "type": "PAYMENT_CONFIRMED",
                  "channel": "EMAIL",
                  "eventId": "   "
                }
                """;

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details.eventId").exists());
    }

    @Test
    void createNotification_invalidEnum_returns400() throws Exception {
        String body = """
                {
                  "recipientId": 1,
                  "type": "NOT_A_TYPE",
                  "channel": "EMAIL",
                  "eventId": "evt-001"
                }
                """;

        // Jackson 역직렬화 단계 실패 → MethodArgumentNotValidException 또는 HttpMessageNotReadableException.
        // 어느 쪽이든 4xx로 떨어져야 한다.
        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void createNotification_duplicateIdempotencyKey_returns409Conflict() throws Exception {
        String body = """
                {
                  "recipientId": 1,
                  "type": "PAYMENT_CONFIRMED",
                  "channel": "EMAIL",
                  "eventId": "dup-evt-001"
                }
                """;

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted());

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("DUPLICATE"));

        assertThat(notificationRepo.count()).isEqualTo(1);
    }

    @Test
    void createNotification_customTypeWithMessage_succeeds() throws Exception {
        String body = """
                {
                  "recipientId": 5,
                  "type": "CUSTOM",
                  "channel": "IN_APP",
                  "eventId": "custom-001",
                  "referenceData": { "title": "공지", "message": "5월 1일 임시 휴무." }
                }
                """;

        mvc.perform(post("/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.type").value("CUSTOM"))
                .andExpect(jsonPath("$.data.referenceData.message").value("5월 1일 임시 휴무."));
    }
}