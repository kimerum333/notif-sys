package com.example.notifsys.api.notification;

import com.example.notifsys.api.common.ApiResponse;
import com.example.notifsys.api.common.ForbiddenException;
import com.example.notifsys.domain.notification.NotificationQueryService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class UserNotificationController {

    private final NotificationQueryService queryService;

    public UserNotificationController(NotificationQueryService queryService) {
        this.queryService = queryService;
    }

    /**
     * 수신자 기준 알림 목록. 본인 알림만 조회 가능 (api-note 결정 3).
     * read=true → 읽음, read=false → 안읽음, 미지정 → 전체.
     */
    @GetMapping("/users/{userId}/notifications")
    public ApiResponse<Page<NotificationResponse>> list(
            @PathVariable Long userId,
            @RequestHeader("X-User-Id") Long requesterId,
            @RequestParam(required = false) Boolean read,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        if (!userId.equals(requesterId)) {
            throw new ForbiddenException("Cannot list notifications for user " + userId);
        }
        Page<NotificationResponse> page = queryService
                .findByRecipient(userId, read, pageable)
                .map(NotificationResponse::from);
        return ApiResponse.ok(page);
    }
}