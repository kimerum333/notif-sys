# 스키마 노트

최종 스키마는 `docs/schema.sql` 참조.

## 결정 1. `reference_data`를 JSONB 단일 컬럼으로

**결정:** 강의ID, 주문ID 등 부가 참조 데이터를 `lecture_id`, `order_id` 등 개별 컬럼으로 빼지 않고 JSONB 컬럼 하나로 통합.

**사유:** 알림 타입마다 참조 데이터의 구조가 다르고, 이 시스템에서 reference_data로 직접 조회하거나 필터링할 일이 없음. 개별 컬럼으로 빼면 타입이 늘어날 때마다 nullable 컬럼이 추가되는 문제가 있음. 알림 내용 렌더링에 필요한 부가정보 용도이므로 JSONB로 유연하게 수용.

## 결정 2. 알림 템플릿을 DB 테이블이 아닌 JSON 파일로 관리

**결정:** `notification_template` 테이블을 만들지 않고, 타입별 메시지 템플릿을 `src/main/resources` 하위의 JSON 파일로 관리.

**사유:** 런타임 수정 가능성보다 구현 단순성이 더 중요한 과제 환경. 실제 운영 전환 시 DB 테이블로 이관 가능한 구조(인터페이스 뒤에 구현체 교체)로 작성하면 충분함.

## 결정 3. 에러 이력을 별도 테이블(`notification_error_log`)로 분리

**결정:** `notification.failure_reason`(최신 에러 1건)과 `notification_error_log`(전체 이력) 두 곳에 모두 기록.

**사유:** `failure_reason` 단일 컬럼만 유지하면 재시도를 거듭할 때마다 이전 에러 메시지가 덮어써져 디버깅 정보가 유실됨. 에러 로그 테이블로 전체 이력을 누적하고, `failure_reason`은 빠른 단건 조회를 위한 최신 에러 캐시 역할로 병행 유지.

## 결정 4. 최종 실패 상태를 `FAILED`와 `DEAD_LETTER`로 분리

**결정:** 재시도 가능한 실패는 `FAILED`, 재시도 횟수를 소진한 최종 실패는 `DEAD_LETTER`로 구분.

**사유:** 단일 `FAILED` 상태로 관리하면 워커 재시도 쿼리에서 `fail_count < max` 조건을 매번 포함해야 하고, 운영자가 "최종 실패한 알림"만 골라내기도 불편함. `DEAD_LETTER`를 별도 상태로 두면 워커는 `status = 'FAILED'`만 보고 재시도하면 되고, 운영자는 `status = 'DEAD_LETTER'`로 간단히 조회 가능. 수동 재시도 API도 이 상태를 대상으로 동작.

## 결정 5. `CUSTOM` 알림 타입 추가

**결정:** 과제에서 제시한 4개 타입(COURSE_REGISTRATION, PAYMENT_CONFIRMED, LECTURE_REMINDER, CANCELLATION) 외에 `CUSTOM` 타입 추가.

**사유:** 선택 구현 항목인 예약/일괄 발송은 광고·공지 등 커스텀 메시지를 전제로 하는 경우가 많음. CUSTOM 타입일 때는 템플릿 파일 조회 대신 `reference_data` JSONB에서 직접 메시지를 추출하는 방식으로 처리하면 스키마 변경 없이 수용 가능.

## 결정 6. 멱등성 유니크 제약에 `type` 포함

**결정:** `UNIQUE (recipient_id, event_id, channel)` → `UNIQUE (recipient_id, event_id, type, channel)`

**사유:** `event_id`가 발생 건별 완전 고유 식별자라는 보장이 API 계약만으로는 약함. 호출 측이 도메인 오브젝트 ID(예: lecture_id)를 event_id로 그대로 전달하는 경우, 동일 event_id에서 타입이 다른 알림(예: LECTURE_REMINDER와 CANCELLATION)이 발송되어야 할 때 `type` 없이는 중복으로 차단됨. `type`을 제약에 포함해 "동일 수신자 + 동일 이벤트 + 동일 알림타입 + 동일 채널" 조합을 중복의 단위로 명확히 정의.

## 결정 7. 읽음 여부를 `status`에서 분리해 `read_at` 컬럼으로

**결정:** `status` enum에서 `READ` 제거. `read_at TIMESTAMPTZ NULL` 컬럼 추가. NULL이면 안 읽음, 값이 있으면 읽은 시각.

**사유:** `status`는 본래 "발송의 진행 상태"를 표현하는 컬럼인데, 사용자 행위인 "읽음"을 같은 컬럼에 합쳐두면 두 직교하는 개념이 한 차원으로 압축되어 표현력이 떨어짐. 구체적으로:
- EMAIL은 읽음 개념이 없으므로 SENT에서 영원히 멈춤. IN_APP만 READ로 갈 수 있어 채널별 의미가 비대칭.
- 읽음율 통계, 최초 읽은 시각 추적 등 부가 요구가 생기면 컬럼 추가가 불가피.
- 워커가 발송 상태만 보면 되는데 READ 상태까지 신경써야 하는 인지 비용 발생.

`read_at`으로 분리하면 사용자 알림 목록 필터가 `status = 'SENT' AND read_at IS NULL`(안읽음) / `read_at IS NOT NULL`(읽음)으로 자연스러워지고, 워커는 발송 상태만 다루게 됨.

## 결정 8. `stuck_count`에도 별도 임계치(`max_stuck`) 적용

**결정:** `fail_count >= max_fail` 또는 `stuck_count >= max_stuck` 중 하나라도 도달하면 DEAD_LETTER로 전이. 두 카운터는 의미가 달라 별도 임계치로 관리.

**사유:** `fail_count`는 "외부 시스템으로부터 실패 응답을 받은 횟수"이고 `stuck_count`는 "워커가 응답을 보지 못하고 처리가 끊긴 횟수"임. 두 카운터를 합치거나 하나로 일원화하면 서로 다른 종류의 장애가 구분 불가능해져 운영/디버깅에서 정보가 유실됨. 또한 `stuck_count`에 상한이 없으면 poison pill(특정 알림 처리 시마다 워커가 죽는 경우)이 무한 재시도 루프에 빠질 위험이 있어 임계치가 반드시 필요. 두 임계치는 `application.yaml`의 `notification.worker.max-fail-count`, `notification.worker.max-stuck-count`로 외부 주입.