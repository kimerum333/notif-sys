package com.example.notifsys.domain.notification;

import com.example.notifsys.api.common.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEAD_LETTER 알림의 수동 재시도. 운영자 엔드포인트로 사용.
 *
 * 정책(`policy-note 결정 1`): fail_count / stuck_count는 초기화하지 않음. 상태만 PENDING으로
 * 되돌리고 retry_after를 비움. 즉, 한 번의 추가 시도 기회 부여.
 */
@Service
public class NotificationManualRetryService {

    private final NotificationRepository repo;

    public NotificationManualRetryService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public Notification retry(Long notificationId) {
        Notification n = repo.findById(notificationId)
                .orElseThrow(() -> new NotFoundException("Notification not found: id=" + notificationId));
        n.resetForManualRetry();
        return n;
    }
}