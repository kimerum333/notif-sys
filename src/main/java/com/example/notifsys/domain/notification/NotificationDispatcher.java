package com.example.notifsys.domain.notification;

import com.example.notifsys.domain.notification.sender.FailureKind;
import com.example.notifsys.domain.notification.sender.NotificationSender;
import com.example.notifsys.domain.notification.sender.SendResult;
import org.springframework.stereotype.Service;

@Service
public class NotificationDispatcher {

    private final NotificationSenderRegistry senderRegistry;
    private final NotificationOutcomeRecorder outcomeRecorder;

    public NotificationDispatcher(NotificationSenderRegistry senderRegistry,
                                  NotificationOutcomeRecorder outcomeRecorder) {
        this.senderRegistry = senderRegistry;
        this.outcomeRecorder = outcomeRecorder;
    }

    public void dispatch(Notification notification) {
        NotificationSender sender = senderRegistry.forChannel(notification.getChannel());
        SendResult result;
        try {
            result = sender.send(notification);
        } catch (RuntimeException e) {
            result = new SendResult.Failure(FailureKind.TRANSIENT, "Unexpected sender exception: " + e.getMessage());
        }
        outcomeRecorder.record(notification.getId(), result);
    }
}