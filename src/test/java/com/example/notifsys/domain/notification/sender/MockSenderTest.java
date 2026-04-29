package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationChannel;
import com.example.notifsys.domain.notification.NotificationType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockSenderTest {

    private final MockEmailSender emailSender = new MockEmailSender();
    private final MockInAppSender inAppSender = new MockInAppSender();

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
        SendResult result = emailSender.send(notification("evt-001"));
        assertThat(result).isInstanceOf(SendResult.Success.class);
    }

    @Test
    void successPrefixedEventId_returnsSuccess() {
        SendResult result = emailSender.send(notification("success-001"));
        assertThat(result).isInstanceOf(SendResult.Success.class);
    }

    @Test
    void transientPrefixedEventId_returnsTransientFailure() {
        SendResult result = emailSender.send(notification("fail-transient-001"));
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        SendResult.Failure failure = (SendResult.Failure) result;
        assertThat(failure.kind()).isEqualTo(FailureKind.TRANSIENT);
        assertThat(failure.reason()).isNotBlank();
    }

    @Test
    void permanentPrefixedEventId_returnsPermanentFailure() {
        SendResult result = inAppSender.send(notification("fail-permanent-001"));
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        assertThat(((SendResult.Failure) result).kind()).isEqualTo(FailureKind.PERMANENT);
    }

    private Notification notification(String eventId) {
        return Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId(eventId)
                .build();
    }
}
