package com.example.notifsys.domain.notification.template;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageRendererTest {

    private final MessageRenderer renderer = new MessageRenderer(new StubRepo(Map.of(
            NotificationType.COURSE_REGISTRATION,
            new MessageTemplate("수강 신청 완료", "강의 '{lectureName}'(ID: {lectureId})에 수강 신청이 완료되었습니다."),
            NotificationType.PAYMENT_CONFIRMED,
            new MessageTemplate("결제 완료", "주문 #{orderId}, 금액 {amount}원.")
    )));

    @Test
    void render_substitutesPlaceholdersFromReferenceData() {
        Notification n = build(NotificationType.COURSE_REGISTRATION,
                Map.of("lectureId", 42, "lectureName", "Spring Boot 마스터"));

        MessageRenderer.RenderedMessage rendered = renderer.render(n);

        assertThat(rendered.title()).isEqualTo("수강 신청 완료");
        assertThat(rendered.body()).isEqualTo("강의 'Spring Boot 마스터'(ID: 42)에 수강 신청이 완료되었습니다.");
    }

    @Test
    void render_missingPlaceholder_keepsLiteralAndDoesNotThrow() {
        // reference_data에 lectureName 없을 때, {lectureName} 리터럴이 남고 다른 키는 정상 치환.
        Notification n = build(NotificationType.COURSE_REGISTRATION, Map.of("lectureId", 42));

        MessageRenderer.RenderedMessage rendered = renderer.render(n);

        assertThat(rendered.body()).contains("{lectureName}");
        assertThat(rendered.body()).contains("42");
    }

    @Test
    void render_referenceDataNull_keepsAllPlaceholdersLiteral() {
        Notification n = build(NotificationType.COURSE_REGISTRATION, null);

        MessageRenderer.RenderedMessage rendered = renderer.render(n);

        assertThat(rendered.body()).contains("{lectureName}");
        assertThat(rendered.body()).contains("{lectureId}");
    }

    @Test
    void render_unknownType_throws() {
        Notification n = build(NotificationType.LECTURE_REMINDER, Map.of());

        assertThatThrownBy(() -> renderer.render(n))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LECTURE_REMINDER");
    }

    @Test
    void render_customWithMessage_usesReferenceDataDirectly() {
        Notification n = build(NotificationType.CUSTOM,
                Map.of("title", "공지사항", "message", "5월 1일 임시 휴무 안내."));

        MessageRenderer.RenderedMessage rendered = renderer.render(n);

        assertThat(rendered.title()).isEqualTo("공지사항");
        assertThat(rendered.body()).isEqualTo("5월 1일 임시 휴무 안내.");
    }

    @Test
    void render_customWithoutTitle_titleIsEmpty() {
        Notification n = build(NotificationType.CUSTOM, Map.of("message", "본문만 있는 공지."));

        MessageRenderer.RenderedMessage rendered = renderer.render(n);

        assertThat(rendered.title()).isEmpty();
        assertThat(rendered.body()).isEqualTo("본문만 있는 공지.");
    }

    @Test
    void render_customWithoutMessage_throws() {
        Notification n = build(NotificationType.CUSTOM, Map.of("title", "제목만"));

        assertThatThrownBy(() -> renderer.render(n))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires reference_data.message");
    }

    @Test
    void render_customNullReferenceData_throws() {
        Notification n = build(NotificationType.CUSTOM, null);

        assertThatThrownBy(() -> renderer.render(n))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires reference_data.message");
    }

    private Notification build(NotificationType type, Map<String, Object> referenceData) {
        return Notification.builder()
                .recipientId(1L)
                .type(type)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt-renderer-test")
                .referenceData(referenceData)
                .build();
    }

    private record StubRepo(Map<NotificationType, MessageTemplate> templates) implements MessageTemplateRepository {
        @Override
        public Optional<MessageTemplate> findByType(NotificationType type) {
            return Optional.ofNullable(templates.get(type));
        }
    }
}