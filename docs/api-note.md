# API 설계 노트

알림 시스템 외부 API의 설계 결정을 누적함. 도메인 / 워커 결정은 다른 노트(`analysis-note`, `schema-note`, `policy-note`, `impl-note`) 참조.

## 엔드포인트 목록

| 메서드 | 경로 | 책임 |
|---|---|---|
| `POST` | `/notifications` | 알림 발송 요청 등록 (즉시 발송 X, 접수만) |
| `GET` | `/notifications/{id}` | 특정 알림의 현재 상태 조회 |
| `GET` | `/users/{userId}/notifications` | 수신자 기준 알림 목록 (읽음/안읽음 필터, 페이지네이션) |
| `PATCH` | `/notifications/{id}/read` | 읽음 처리 |
| `POST` | `/notifications/{id}/retry` | DEAD_LETTER 알림 수동 재시도 (`policy-note 결정 1`) |

## 결정 1. 통합 응답 envelope (`ApiResponse<T>`)

**결정:** 모든 엔드포인트가 동일한 envelope 형태로 응답.

```json
// 성공
{ "success": true,  "data": { ... }, "error": null, "timestamp": "..." }
// 실패
{ "success": false, "data": null,
  "error": { "code": "...", "message": "...", "details": { ... } },
  "timestamp": "..." }
```

HTTP 상태 코드는 살리되(`201`, `202`, `404`, `400`, `409`, `5xx`) body 형태는 일관 유지. 페이징 응답은 `data`에 `Page<X>`(content + page metadata) 그대로 직렬화.

**사유:**
- 클라이언트가 매 엔드포인트마다 다른 응답 형태를 분기 처리할 필요 없음.
- 에러 응답 또한 같은 wrapper라 try/catch가 아닌 `if (!res.success) ...` 단일 분기로 처리 가능.
- 운영 시 디버깅용으로 응답에 `timestamp`가 항상 붙어 클라이언트 로그와 서버 로그 시간 매칭이 쉬움.

**대안:** REST 순수 — 상태 코드 + 원시 body. 더 가볍지만 클라이언트 측 분기 비용이 늘어남. 본 프로젝트는 envelope 채택.

## 결정 2. 페이지네이션 — Spring `Pageable`

**결정:** 목록 조회 엔드포인트는 Spring의 `Pageable` 자동 바인딩. 응답은 `Page<NotificationResponse>` 직렬화 → envelope `data`에 들어감.

쿼리 예: `GET /users/{userId}/notifications?page=0&size=20&sort=createdAt,desc&read=false`

**사유:**
- Spring Data가 page / size / sort를 자동 파싱, 별도 DTO 불필요.
- 응답에 totalElements / totalPages / hasNext 등 메타가 자동 포함되어 클라이언트가 별도 카운트 쿼리를 안 쳐도 됨.
- 무한 스크롤 / 페이지네이터 UI 둘 다 같은 응답으로 커버 가능.

**대안:** limit / offset 직접 받는 방식. 단순하지만 메타 정보가 없어 클라이언트가 추가 작업을 해야 함. 거부.

## 결정 3. 인증 / 인가 — `X-User-Id` 헤더

**결정:** 사용자 식별은 `X-User-Id: <recipientId>` 헤더로 받음. 본인 자원만 접근 가능한 엔드포인트(목록 조회, 읽음 처리)는 헤더 ID와 자원의 `recipient_id` 일치 여부로 가드. 불일치 시 `403 Forbidden`.

**사유:**
- 과제 명세에서 "userId를 헤더나 파라미터로 전달하는 방식도 허용" 명시. 인증 미들웨어 / JWT는 본 과제 범위 밖.
- 헤더는 도메인 데이터(URL path)와 분리되는 메타 정보 자리라 위치적으로 적절. 게이트웨이 / 프록시에서 표준화하기 쉬움.
- 추후 실제 인증을 도입할 때 인증 필터가 토큰을 검증한 결과를 이 헤더에 채우는 식으로 자연스럽게 확장 가능. 컨트롤러 시그니처 불변.

**대안:** 쿼리 파라미터 / Path Variable. 캐시 의도 없는 식별자라 위치적으로 어울리지 않음. 거부.

**제외 범위:** 발송 요청 등록(`POST /notifications`)은 외부 시스템(비즈니스 서비스)이 호출하는 서버-to-서버 시나리오를 가정하므로 X-User-Id 가드를 *적용하지 않음*. 내부 신뢰 경계 안에서 호출되는 것으로 본다 (`analysis-note` 트랜잭션 분리 섹션 참조).

## 결정 4. API 에러 처리 — SLF4J 파일 로깅 전용, 별도 테이블 없음

**결정:** API 레이어의 4xx / 5xx 에러는 `@RestControllerAdvice`가 envelope 에러 응답으로 변환하고, SLF4J로 파일에 누적. **별도 테이블(`api_error_log` 등) 만들지 않음.** `notification_error_log`는 *도메인 발송 실패* 책임 그대로 유지.

**Logback / `application.yaml` 설정:**
```yaml
logging:
  file:
    name: logs/notif-sys.log
  pattern:
    file: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n"
  logback:
    rollingpolicy:
      max-file-size: 10MB
      max-history: 14
```

**예외 → 응답 매핑:**
- `MethodArgumentNotValidException` (Bean Validation 실패) → `400 BAD_REQUEST`, 에러 코드 `VALIDATION_FAILED`, details에 필드별 오류
- `MissingRequestHeaderException` (`X-User-Id` 결손) → `400 BAD_REQUEST`, 코드 `MISSING_USER_ID`
- 본인 자원 아님 → `403 FORBIDDEN`, 코드 `FORBIDDEN`
- 자원 없음 → `404 NOT_FOUND`, 코드 `NOT_FOUND`
- DB 유니크 제약 위반(`DataIntegrityViolationException`) → `409 CONFLICT`, 코드 `DUPLICATE`
- 상태 전이 불가(`IllegalStateException`) → `409 CONFLICT`, 코드 `INVALID_STATE`
- 기타 → `500`, 코드 `INTERNAL_ERROR`, 스택트레이스는 로그에만 (응답엔 노출 X)

**사유:**
- API 에러는 *재시도 결정의 입력*이 아님. 클라이언트에 에러 응답을 보낸 시점에 시스템 책임 종료. DB 영속화 동기가 약함 (대조: `notification_error_log`는 자동 재시도 / DEAD_LETTER 판정에 직접 쓰임).
- 운영 환경에선 ELK / Loki / CloudWatch 같은 로그 어그리게이터로 흘려보내는 게 표준. DB는 적절한 도구 아님 (조회 / 보관 비용).
- 과제 마감 압박 하에서 추가 테이블 + 리포지토리 + 어드바이스 연동의 비용이 가치 대비 큼.

**대안:** `api_error_log` 테이블 + 5xx만 저장. 운영 중 forensic / audit 요구가 생기면 도입. 현 시점 미도입을 README "미구현 / 제약" 항목에 명시.

## 결정 5. CUSTOM 알림은 별도 엔드포인트 없음 — `POST /notifications` 흡수

**결정:** `POST /notifications` 요청 body의 `type=CUSTOM` + `referenceData={"message": "...", "title": "..."}`로 커스텀 메시지 처리. 별도 `POST /notifications/custom` 엔드포인트 만들지 않음.

**렌더링 분기:** `MessageRenderer`가 `type==CUSTOM`이면 템플릿 조회 건너뛰고 `reference_data.message`를 본문, `reference_data.title`을 제목으로 사용 (템플릿 관리 구현 참조).

**사유:**
- `schema-note 결정 5`(CUSTOM 타입)와 `결정 2`(템플릿을 JSON 파일로 외부화)의 자연스러운 귀결.
- 단일 엔드포인트로 표준 / 커스텀 알림을 통일 관리. 클라이언트는 type만 다르게 보내면 됨.
- 별도 엔드포인트는 `Notification` 도메인 모델을 두 번 다른 형태로 노출하게 되어 인터페이스 분산 + 응답 envelope 일관성 약화.

## 결정 6. 발송 스케줄링 — 요청 DTO에 `scheduledAt` 필드 노출

**결정:** `POST /notifications` 요청 body에 nullable `scheduledAt`(ISO-8601) 필드 추가. 별도 스케줄링 엔드포인트는 없음.

**동작:**
- `scheduledAt`이 null 또는 과거 시각 → 즉시 픽업 가능
- `scheduledAt`이 미래 시각 → 폴러가 `(scheduled_at IS NULL OR scheduled_at <= now())` 조건으로 그 시각 이후에만 픽업

**사유:**
- 인프라는 이미 준비됨: `Notification.scheduledAt` 컬럼 + `findDispatchTargetsForUpdate` 쿼리 조건. 노출만 하면 선택 구현 항목 충족.
- 별도 엔드포인트(`POST /notifications/schedule`)는 동일 도메인 모델 / 동일 응답을 두 번 노출하게 되어 불필요한 분산.
- "예약된 알림 목록 조회 / 취소"는 본 과제 범위 밖이라 단일 엔드포인트로 충분.

**검증:** 통합 테스트에 "scheduledAt이 미래인 row는 1회 폴 사이클에서 PENDING으로 남고, 시각 도래 후 픽업된다" 케이스 추가 예정.