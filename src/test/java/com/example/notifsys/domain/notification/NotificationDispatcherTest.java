package com.example.notifsys.domain.notification;

import com.example.notifsys.domain.notification.sender.FailureKind;
import com.example.notifsys.domain.notification.sender.NotificationSender;
import com.example.notifsys.domain.notification.sender.SendResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationDispatcherTest {

    private NotificationSenderRegistry registry;
    private NotificationOutcomeRecorder recorder;
    private NotificationSender sender;
    private NotificationDispatcher dispatcher;

    @BeforeEach
    void setup() {
        registry = mock(NotificationSenderRegistry.class);
        recorder = mock(NotificationOutcomeRecorder.class);
        sender = mock(NotificationSender.class);
        dispatcher = new NotificationDispatcher(registry, recorder);
    }

    @Test
    void dispatch_passesSuccessOutcomeToRecorder() {
        Notification n = makeProcessing(1L);
        when(registry.forChannel(NotificationChannel.EMAIL)).thenReturn(sender);
        when(sender.send(n)).thenReturn(new SendResult.Success());

        dispatcher.dispatch(n);

        ArgumentCaptor<SendResult> captor = ArgumentCaptor.forClass(SendResult.class);
        verify(recorder).record(eq(1L), captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SendResult.Success.class);
    }

    @Test
    void dispatch_passesFailureOutcomeToRecorder() {
        Notification n = makeProcessing(2L);
        when(registry.forChannel(NotificationChannel.EMAIL)).thenReturn(sender);
        when(sender.send(n)).thenReturn(new SendResult.Failure(FailureKind.PERMANENT, "bad-input"));

        dispatcher.dispatch(n);

        ArgumentCaptor<SendResult> captor = ArgumentCaptor.forClass(SendResult.class);
        verify(recorder).record(eq(2L), captor.capture());
        SendResult result = captor.getValue();
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        assertThat(((SendResult.Failure) result).kind()).isEqualTo(FailureKind.PERMANENT);
    }

    @Test
    void dispatch_translatesUnexpectedSenderExceptionIntoTransientFailure() {
        Notification n = makeProcessing(3L);
        when(registry.forChannel(NotificationChannel.EMAIL)).thenReturn(sender);
        when(sender.send(n)).thenThrow(new RuntimeException("boom"));

        dispatcher.dispatch(n);

        ArgumentCaptor<SendResult> captor = ArgumentCaptor.forClass(SendResult.class);
        verify(recorder).record(eq(3L), captor.capture());
        SendResult result = captor.getValue();
        assertThat(result).isInstanceOf(SendResult.Failure.class);
        assertThat(((SendResult.Failure) result).kind()).isEqualTo(FailureKind.TRANSIENT);
    }

    private Notification makeProcessing(Long id) {
        Notification n = Notification.builder()
                .recipientId(1L)
                .type(NotificationType.COURSE_REGISTRATION)
                .channel(NotificationChannel.EMAIL)
                .eventId("evt-" + id)
                .build();
        ReflectionTestUtils.setField(n, "id", id);
        n.markProcessing();
        return n;
    }
}
