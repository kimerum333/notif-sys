package com.example.notifsys.api.notification;

import com.example.notifsys.api.common.ApiResponse;
import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationCreationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationCreationService creationService;

    public NotificationController(NotificationCreationService creationService) {
        this.creationService = creationService;
    }

    /**
     * 알림 발송 요청 등록. 즉시 발송이 아닌 *접수만* 수행 — 워커가 비동기 처리.
     * X-User-Id 가드 미적용 (api-note 결정 3 제외 범위: 서버-to-서버 시나리오).
     */
    @PostMapping
    public ResponseEntity<ApiResponse<NotificationResponse>> create(
            @Valid @RequestBody NotificationCreateRequest request) {
        Notification created = creationService.create(
                request.recipientId(),
                request.type(),
                request.channel(),
                request.eventId(),
                request.referenceData(),
                request.scheduledAt()
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.ok(NotificationResponse.from(created)));
    }
}