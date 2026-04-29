package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class EventIdPatternMockSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EventIdPatternMockSender.class);

    static final String PREFIX_FAIL_TRANSIENT = "fail-transient-";
    static final String PREFIX_FAIL_PERMANENT = "fail-permanent-";

    @Override
    public SendResult send(Notification notification) {
        String eventId = notification.getEventId();
        if (eventId.startsWith(PREFIX_FAIL_PERMANENT)) {
            log.info("[mock {}] permanent failure simulated id={} eventId={}",
                    channel(), notification.getId(), eventId);
            return new SendResult.Failure(FailureKind.PERMANENT, "simulated permanent failure");
        }
        if (eventId.startsWith(PREFIX_FAIL_TRANSIENT)) {
            log.info("[mock {}] transient failure simulated id={} eventId={}",
                    channel(), notification.getId(), eventId);
            return new SendResult.Failure(FailureKind.TRANSIENT, "simulated transient failure");
        }
        log.info("[mock {}] success id={} eventId={}",
                channel(), notification.getId(), eventId);
        return new SendResult.Success();
    }
}
