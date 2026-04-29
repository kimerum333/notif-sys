package com.example.notifsys.domain.notification;

import com.example.notifsys.domain.notification.sender.FailureKind;
import com.example.notifsys.domain.notification.sender.SendResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationOutcomeRecorder {

    private final NotificationRepository notificationRepo;
    private final NotificationErrorLogRepository errorLogRepo;
    private final RetryPolicy retryPolicy;

    public NotificationOutcomeRecorder(NotificationRepository notificationRepo,
                                       NotificationErrorLogRepository errorLogRepo,
                                       RetryPolicy retryPolicy) {
        this.notificationRepo = notificationRepo;
        this.errorLogRepo = errorLogRepo;
        this.retryPolicy = retryPolicy;
    }

    @Transactional
    public void record(Long notificationId, SendResult result) {
        Notification n = notificationRepo.findById(notificationId)
                .orElseThrow(() -> new IllegalStateException("Notification not found: " + notificationId));
        if (result instanceof SendResult.Success) {
            n.markSent();
        } else if (result instanceof SendResult.Failure failure) {
            handleFailure(n, failure);
        }
    }

    private void handleFailure(Notification n, SendResult.Failure failure) {
        if (failure.kind() == FailureKind.PERMANENT || retryPolicy.isExhausted(n.getFailCount())) {
            n.markDeadLetter(failure.reason());
        } else {
            n.markFailed(failure.reason(), retryPolicy.nextRetryAt(n.getFailCount() + 1));
        }
        errorLogRepo.save(NotificationErrorLog.of(n, failure.reason()));
    }
}