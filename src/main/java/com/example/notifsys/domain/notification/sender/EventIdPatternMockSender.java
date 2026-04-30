package com.example.notifsys.domain.notification.sender;

import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.template.MessageRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract class EventIdPatternMockSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(EventIdPatternMockSender.class);

    static final String PREFIX_FAIL_TRANSIENT = "fail-transient-";
    static final String PREFIX_FAIL_PERMANENT = "fail-permanent-";

    private final MessageRenderer messageRenderer;

    protected EventIdPatternMockSender(MessageRenderer messageRenderer) {
        this.messageRenderer = messageRenderer;
    }

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

        // 렌더링 실패는 재시도 무의미한 데이터 결함이므로 PERMANENT로 매핑.
        MessageRenderer.RenderedMessage rendered;
        try {
            rendered = messageRenderer.render(notification);
        } catch (RuntimeException e) {
            log.warn("[mock {}] render failed id={} eventId={}: {}",
                    channel(), notification.getId(), eventId, e.getMessage());
            return new SendResult.Failure(FailureKind.PERMANENT, "render failed: " + e.getMessage());
        }

        log.info("[mock {}] success id={} eventId={} title='{}' body='{}'",
                channel(), notification.getId(), eventId, rendered.title(), rendered.body());
        return new SendResult.Success();
    }
}