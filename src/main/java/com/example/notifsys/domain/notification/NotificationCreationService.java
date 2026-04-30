package com.example.notifsys.domain.notification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * 알림 발송 요청 등록. API 레이어가 호출.
 *
 * 도메인 mutator를 호출하지 않고 새 엔티티를 만들기만 하므로 visibility 결정(impl-note 결정 6)에는
 * 영향을 받지 않으나, 같은 패키지에 두는 편이 도메인 응집을 유지하기 좋다.
 */
@Service
public class NotificationCreationService {

    private final NotificationRepository repo;

    public NotificationCreationService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Notification create(Long recipientId,
                               NotificationType type,
                               NotificationChannel channel,
                               String eventId,
                               Map<String, Object> referenceData,
                               Instant scheduledAt) {
        Notification notification = Notification.builder()
                .recipientId(recipientId)
                .type(type)
                .channel(channel)
                .eventId(eventId)
                .referenceData(referenceData)
                .scheduledAt(scheduledAt)
                .build();
        return repo.save(notification);
    }
}