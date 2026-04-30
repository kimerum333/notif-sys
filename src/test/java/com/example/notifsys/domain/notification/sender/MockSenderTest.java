package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationType;
import com.example.notifsys.domain.notification.template.MessageRenderer;
import com.example.notifsys.domain.notification.template.MessageTemplate;
import com.example.notifsys.domain.notification.template.MessageTemplateRepository;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MockSenderTest {

    private final MessageTemplateRepository stubTemplates =
            type -> Optional.of(new MessageTemplate("stub-title", "stub-body"));
    private final MessageRenderer renderer = new MessageRenderer(stubTemplates);
    private final MockEmailSender emailSender = new MockEmailSender(renderer);
    private final MockInAppSender inAppSender = new MockInAppSender(renderer);

    @Test
    void emailSender_channelIsEmail() {
        assertThat(emailSender.channel()).isEqualTo(NotificationChannel.EMAIL);
    }

    @Test
    void inAppSender_channelIsInApp() {
        assertThat(inAppSender.channel()).isEqualTo(NotificationChannel.IN_APP);
    }

    @Test
    void defaultEventId_returnsSuccess() {
        SendResult result = emailSender.send(notification("evt-001", NotificationType.COURSE_REGISTRATION));
        assertThat(result).isInstanceOf(SendResult.Success.class);
    }

    @Test
    void successPrefixedEventId_returnsSuccess() {
        SendResult result = emailSender.send(notification("success-001", NotificationType.COURSE_REGISTRATION));
        assertThat(result).isInstanceOf(SendResult.Success.class);
    }

    @Test
    void transientPrefixedEventId_returnsTransientFailure() {
        SendResult result = emailSender.send(notification("fail-transient-001", NotificationType.COURSE_REGISTRATION));
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        SendResult.Failure failure = (SendResult.Failure) result;
        assertThat(failure.kind()).isEqualTo(FailureKind.TRANSIENT);
        assertThat(failure.reason()).isNotBlank();
    }

    @Test
    void permanentPrefixedEventId_returnsPermanentFailure() {
        SendResult result = inAppSender.send(notification("fail-permanent-001", NotificationType.COURSE_REGISTRATION));
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        assertThat(((SendResult.Failure) result).kind()).isEqualTo(FailureKind.PERMANENT);
    }

    @Test
    void customWithoutMessageInReferenceData_isPermanentFailure() {
        // CUSTOM 타입은 reference_data.message 필수. 결손 시 렌더 실패 → PERMANENT.
        Notification n = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.CUSTOM)
                .channel(NotificationChannel.EMAIL)
                .eventId("custom-no-message-001")
                .referenceData(Map.of("title", "공지"))
                .build();

        SendResult result = emailSender.send(n);
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        SendResult.Failure failure = (SendResult.Failure) result;
        assertThat(failure.kind()).isEqualTo(FailureKind.PERMANENT);
        assertThat(failure.reason()).contains("render failed");
    }

    private Notification notification(String eventId, NotificationType type) {
        return Notification.builder()
                .recipientId(1L)
                .type(type)
                .channel(NotificationChannel.EMAIL)
                .eventId(eventId)
                .referenceData(Map.of("lectureId", 100, "lectureName", "Spring Boot 마스터"))
                .build();
    }
}