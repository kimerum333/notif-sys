package com.example.notifsys.api.common;

import java.time.Instant;

/**
 * 모든 API 응답의 통합 envelope.
 *
 * - 성공: success=true, data=<payload>, error=null
 * - 실패: success=false, data=null, error=<ApiError>
 *
 * HTTP 상태 코드는 그대로 살리되 body 형태만 통일 (api-note 결정 1).
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        ApiError error,
        Instant timestamp
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static ApiResponse<Void> error(ApiError error) {
        return new ApiResponse<>(false, null, error, Instant.now());
    }
}