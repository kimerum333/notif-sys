package com.example.notifsys.api.notification;

import com.example.notifsys.api.common.ApiResponse;
import com.example.notifsys.domain.notification.Notification;
import com.example.notifsys.domain.notification.NotificationCreationService;
import com.example.notifsys.domain.notification.NotificationManualRetryService;
import com.example.notifsys.domain.notification.NotificationQueryService;
import com.example.notifsys.domain.notification.NotificationReadService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private final NotificationCreationService creationService;
    private final NotificationQueryService queryService;
    private final NotificationReadService readService;
    private final NotificationManualRetryService manualRetryService;

    public NotificationController(NotificationCreationService creationService,
                                  NotificationQueryService queryService,
                                  NotificationReadService readService,
                                  NotificationManualRetryService manualRetryService) {
        this.creationService = creationService;
        this.queryService = queryService;
        this.readService = readService;
        this.manualRetryService = manualRetryService;
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

    /**
     * 특정 알림 요청의 현재 상태 조회. 시스템(요청 발행자)이 상태를 추적하기 위한 엔드포인트로 가정,
     * X-User-Id 가드 미적용 (api-note 결정 3).
     */
    @GetMapping("/{id}")
    public ApiResponse<NotificationResponse> getById(@PathVariable Long id) {
        Notification n = queryService.findById(id);
        return ApiResponse.ok(NotificationResponse.from(n));
    }

    /**
     * 알림 읽음 처리. 본인 알림만 처리 가능 (api-note 결정 3).
     * 이미 읽은 알림에 대한 재호출은 no-op으로 처리되고 마지막 읽음 시각이 유지됨
     * (`Notification#markRead`의 first-write-wins 시도).
     */
    @PatchMapping("/{id}/read")
    public ApiResponse<NotificationResponse> markRead(
            @PathVariable Long id,
            @RequestHeader("X-User-Id") Long requesterId) {
        Notification n = readService.markAsRead(id, requesterId);
        return ApiResponse.ok(NotificationResponse.from(n));
    }

    /**
     * DEAD_LETTER 알림 수동 재시도. 운영자 엔드포인트.
     * 카운터 미리셋 정책(policy-note 결정 1) — 1회 추가 시도 기회 부여.
     */
    @PostMapping("/{id}/retry")
    public ApiResponse<NotificationResponse> manualRetry(@PathVariable Long id) {
        Notification n = manualRetryService.retry(id);
        return ApiResponse.ok(NotificationResponse.from(n));
    }
}