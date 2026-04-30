package com.example.notifsys.domain.notification;

import com.example.notifsys.api.common.ForbiddenException;
import com.example.notifsys.api.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 알림 읽음 처리.
 *
 * `Notification#markRead`는 first-write-wins 시도 (in-memory readAt null 체크). JPA managed entity
 * 위 in-memory 체크라 두 트랜잭션이 동시에 readAt=null을 보고 둘 다 update하는 경우 last-write-wins
 * (두 번째 timestamp가 덮어씀). 동작상 문제는 없으나 엄밀한 first-write-wins가 필요하면 conditional
 * UPDATE로 변경 (temp-note의 미처리 항목 참조).
 */
@Service
public class NotificationReadService {

    private final NotificationRepository repo;

    public NotificationReadService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Notification markAsRead(Long notificationId, Long requesterId) {
        Notification n = repo.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found: id=" + notificationId));
        if (!n.getRecipientId().equals(requesterId)) {
            throw new ForbiddenException("Notification " + notificationId + " is not owned by user " + requesterId);
        }
        n.markRead();
        return n;
    }
}