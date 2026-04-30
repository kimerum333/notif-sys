# 알림 발송 시스템 (notif-sys)

채용 과제 BE-C — 이벤트 기반 비동기 알림 발송 시스템. 멱등성 보장, 재시도 정책, 다중 인스턴스 / 서버 재시작 / 처리 중 stuck 등 운영 시나리오 대응에 중점.

## 프로젝트 개요

수강 신청 / 결제 / 강의 D-1 / 취소 등 비즈니스 이벤트가 발생하면 사용자에게 이메일 또는 인앱 알림을 발송하는 백엔드 서비스다. 외부 시스템에서 알림 발송을 *요청*하면 즉시 발송이 아닌 접수만 하고, 별도 워커가 비동기로 실제 발송을 수행한다. 발송 실패 시 일시 / 영구 장애를 분류해 지수 백오프 재시도 또는 즉시 DEAD_LETTER 격리, 워커가 처리 중 사망하더라도 stuck 복구로 복구된다.

| 핵심 책임 | 구현 위치 |
|---|---|
| 알림 등록 / 조회 / 읽음 / 수동 재시도 API | `api.notification` 패키지 (Controller + DTO) |
| 도메인 상태 머신 + 멱등성 키 + 재시도 / stuck 카운터 | `domain.notification.Notification` + 정책/임계치 구성 |
| 비동기 워커 (FOR UPDATE SKIP LOCKED 폴링 → 디스패치 → 결과 기록) | `domain.notification.{Poller, PickupService, Dispatcher, OutcomeRecorder, StuckRecoveryService}` |
| 알림 타입별 메시지 템플릿 (선택 구현) | `domain.notification.template` (JSON 파일 외부화) |

## 기술 스택

| 카테고리 | 선택 |
|---|---|
| 언어 / 런타임 | Java 17 (Amazon Corretto 17) |
| 프레임워크 | Spring Boot 4.0.6 (webmvc, data-jpa, validation, actuator) |
| 빌드 | Gradle 9.4.1 + Spring Dependency Management |
| 영속화 | Spring Data JPA, Hibernate 7.2, PostgreSQL 18 |
| 테스트 | JUnit 5, AssertJ, Spring Boot Test, Testcontainers 1.20.4|
| 보조 | Lombok, Logback|

PostgreSQL을 선택한 이유 — `FOR UPDATE SKIP LOCKED`로 다중 워커 동시성 이슈를 DB 단에서 차단 가능, JSONB로 `reference_data`를 타입별 다른 구조로 유연하게 수용 가능. 두 기능이 본 설계의 핵심이라 H2 / MySQL이 아닌 PostgreSQL.

## 실행 방법

### 사전 요구
- **JDK 17** — `JAVA_HOME` 설정 + `java`가 PATH에 있을 것. (Amazon Corretto 17.0.19로 검증, 다른 17 배포판도 동작 예상.)
- **Docker** 데몬 실행 — Postgres 컨테이너용. 테스트는 Testcontainers가 자체 컨테이너를 띄우므로 `docker compose up` 불필요.

### Make 사용 (권장 — Mac / Linux / Windows 공통)

```bash
make up         # Postgres 기동
make run        # 애플리케이션 :8080 (다른 셸에서)
make test       # 전체 테스트 (Testcontainers 자체 기동)
make down       # Postgres 정리
make help       # 전체 타겟 목록
```

Mac / Linux는 GNU make가 기본 설치. Windows는 Git Bash + `choco install make` 또는 WSL 사용. Make가 없는 환경에선 아래 raw 명령 사용.

### Raw 명령 (Make 없을 때)

```bash
# 1) Postgres 기동 (compose.yaml: 5432, db=notif, user/pass=postgres/postgres)
docker compose up -d

# 2) 애플리케이션 기동 — 8080 포트
./gradlew bootRun        # Mac/Linux/Git Bash
gradlew.bat bootRun      # Windows cmd.exe
```

기동 시 Hibernate `ddl-auto: update`로 스키마가 자동 생성된다 (실제 DDL은 `docs/schema.sql`의 참조용 설계서와 일치 — 단 Hibernate가 enum CHECK 제약을 자동 추가해 약간 더 엄격함).

### 헬스 체크

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## 요구사항 해석 및 가정

과제 명세를 코드로 옮기는 과정에서 명시되지 않은 부분에 대한 해석을 명시한다. 구현이 명세와 어긋나 보일 경우 이 절을 먼저 보면 의도가 드러난다.

### 1. "참조 데이터(이벤트 ID, 강의 ID 등)" — `event_id`를 별도 컬럼으로 분리

명세 문구만 보면 `event_id`가 `reference_data`의 일부지만, 구현에서는 `event_id`를 `notification` 테이블의 별도 컬럼으로 분리하고 멱등성 유니크 제약과 재시도 폴링 쿼리의 키로 사용한다. `lecture_id` / `order_id` 등 *부가 참조 정보*만 `reference_data` JSONB로 둔다.

근거: `event_id`가 멱등성의 기본 단위이자 stuck 복구 / 재시도에서 자주 조회되는 키라, 인덱싱 가능한 별도 컬럼으로 분리하는 것이 운영상 유리하다고 판단. 자세한 결정 과정은 `docs/schema-note.md` 결정 1, 6 참조.

### 2. 멱등성 단위에 `type` 포함 — `(recipient_id, event_id, type, channel)` 4-tuple

명세는 "동일 이벤트 중복 발송 금지"만 언급한다. 구현은 멱등성 단위에 `type`을 포함시켜, 동일 `event_id`라도 *다른 종류의 알림*은 가능하도록 한다.

근거: 호출 측이 도메인 객체 ID(예: `lecture_id`)를 `event_id`로 그대로 전달하는 시나리오에서, 같은 강의에 대해 LECTURE_REMINDER와 CANCELLATION이 모두 발송되어야 할 수 있다. `type` 미포함 시 두 번째 알림이 유니크 제약에 막힌다. 자세한 결정은 `docs/schema-note.md` 결정 6.

### 3. "알림 처리 실패가 비즈니스 트랜잭션에 영향을 주지 않음" — API 접수가 본 시스템의 트랜잭션 단위

본 시스템에서 비즈니스 트랜잭션 자체는 *외부 시스템*의 책임이다. 외부 시스템이 비즈니스 트랜잭션 커밋 후 본 시스템의 `POST /notifications`를 호출하므로, 본 시스템 입장에서는 *API 접수 자체*가 트랜잭션 단위다. 따라서 본 시스템 내부에서 분리하는 트랜잭션은 "API 접수(저장)" ↔ "워커 발송 처리"이며, `Notification` 엔티티가 outbox 역할을 한다.

이 해석에 따라:
- 발송 실패는 비즈니스 트랜잭션을 *그 어떤 방식으로도* 롤백시키지 않는다 (역방향 호출 없음).
- 발송 실패는 `notification` 테이블의 상태 / `notification_error_log`에만 기록되며, 자동 재시도 또는 DEAD_LETTER 격리로 처리된다.
- "API 접수와 발송 실패의 트랜잭션 분리"가 본 명세의 요구사항이라 해석.

### 4. 발송 스케줄링(선택 구현) — 별도 엔드포인트 없이 `POST /notifications`로 통합

`POST /notifications`의 요청 body에 nullable `scheduledAt` 필드가 있으며, 미래 시각이면 폴러 쿼리(`scheduled_at IS NULL OR scheduled_at <= now()`)에 의해 그 시각 이후에만 픽업된다. 별도 `POST /notifications/schedule` 엔드포인트는 만들지 않는다.

근거: 동일 도메인 모델 / 동일 응답을 두 번 노출하면 인터페이스 분산만 발생. 또 "예약된 알림 목록 / 취소"가 명세 범위 밖이라 단일 엔드포인트로 충분.

### 5. 인증 / 인가 — `X-User-Id` 헤더 + 본인 자원만 접근

명세는 "userId를 헤더나 파라미터로 전달하는 방식도 허용"이라 명시. 본 구현은 헤더로 받는다.

- `GET /users/{userId}/notifications`, `PATCH /notifications/{id}/read`: 헤더 ID와 자원의 `recipient_id` 일치 검증 (불일치 시 403 FORBIDDEN, 미지정 시 400 MISSING_USER_ID).
- `POST /notifications`, `GET /notifications/{id}`, `POST /notifications/{id}/retry`: 가드 미적용. 각각 외부 비즈니스 시스템 / 발신자의 상태 추적 / 운영자 호출을 가정.

자세한 결정은 `docs/api-note.md` 결정 3.

### 개선 의견

명세 자체에 대한 개선 / 불명확한 부분에 대한 제안:

1. **`event_id`의 의미와 멱등성 단위 명시** — "참조 데이터"라는 묶음 안에 멱등성 키가 섞여 있어 첫 분석 단계에서 해석이 갈렸다. 명세에서 "이 시스템의 멱등성 키는 X" 정도의 한 줄 명시가 있다면 후보 설계가 좁혀진다.
2. **읽음 처리의 정합성 수준 명시** — "여러 기기 동시 읽음 처리"가 선택 구현에 있는데, 본 구현은 last-write-wins(in-memory 체크) 수준. 엄격한 first-write-wins이 필요하면 명세에 명시되어야 conditional UPDATE 등의 추가 비용을 정당화 가능.
3. **운영 환경 전환 시 브로커 종류** — Kafka / RabbitMQ / SQS 등 어떤 브로커를 가정하는지에 따라 outbox 패턴 / CDC / consumer 그룹 설계가 달라진다. 본 구현은 `Dispatcher / Poller` 분리로 *어떤 브로커든 교체 가능한* 추상화를 두었다.

## 설계 결정과 이유

핵심 결정만 추려 정리. 자세한 사유 / 대안은 `docs/` 노트 참조.

### 5.1 도메인 상태 머신

```
                                 [scheduledAt > now()]
                                         │
                                         ▼
   ┌──────────┐  pick(TX1)   ┌────────────┐  send ok      ┌─────┐
   │ PENDING  │─────────────▶│ PROCESSING │──────────────▶│SENT │
   └──────────┘              └────────────┘               └──┬──┘
        ▲                          │                         │ markRead
        │                          │                         ▼ (PATCH)
        │ recoverFromStuck         │ TRANSIENT     ┌──────────────┐
        │ (stuck_count++)          ▼ failure       │ SENT+read_at │
        │                    ┌──────────┐          └──────────────┘
        └────────────────────│  FAILED  │
                             └────┬─────┘  exhausted (fail_count >= max_fail)
                                  │        OR PERMANENT failure
                                  ▼
                          ┌─────────────┐  manualRetry (POST /retry)
                          │ DEAD_LETTER │──────────┐
                          └─────────────┘          │ status → PENDING
                                                   │ (fail_count 미리셋)
                                                   └─▶ PENDING
```

- `read_at`은 별도 컬럼으로 분리 — `status`는 *발송의 진행*을 표현, `read_at`은 *사용자 행위*. 직교 개념을 분리해 표현력 / 인지 비용 모두 개선 (`schema-note 결정 7`).
- `FAILED`(재시도 가능)와 `DEAD_LETTER`(최종 실패) 분리 — 워커는 `status='FAILED'` + `fail_count < max`만 보고 픽업, 운영자는 `DEAD_LETTER`만 보고 수동 처리 (`schema-note 결정 4`).

### 5.2 멱등성 — DB 유니크 제약

`UNIQUE (recipient_id, event_id, type, channel)` 제약을 DB에 두어, 동일 키로 두 번 INSERT 시 두 번째가 `DataIntegrityViolationException`으로 차단된다. FE / 호출자 측 멱등성 키 발급 부담이 없고, 동시 요청 race condition도 DB 단에서 차단.

### 5.3 다중 인스턴스 동시성 — `FOR UPDATE SKIP LOCKED`

워커 폴링 쿼리에 `FOR UPDATE SKIP LOCKED`를 적용. 다중 워커가 동시에 폴링해도 각자 다른 row 셋을 락 획득 → 동일 알림 중복 처리 차단. `markProcessing` 메서드의 status 가드와 결합되어, 락 경합 시점에 우연히 같은 row가 두 번 SELECT되더라도 두 번째는 `IllegalStateException`으로 차단된다.

통합 테스트 검증: 5개 워커가 batchSize=50으로 50 row를 동시에 노릴 때, 합쳐서 정확히 50건이 *겹침 없이* PROCESSING으로 처리됨 (`WorkerIntegrationTest#concurrentPickup_skipLocked_allRowsHandledExactlyOnce`).

### 5.4 비동기 처리 구조

```
        ┌──────────────────────┐
        │  POST /notifications │
        └──────────┬───────────┘
                   │ 트랜잭션 ①: INSERT INTO notification (status=PENDING)
                   ▼
            ┌──────────────┐         ┌────────────────────────┐
            │ notification │◀────────│  스케줄링 (@Scheduled)  │
            │   (PENDING)  │         │  fixedDelay=1s          │
            └──────┬───────┘         └────────────┬───────────┘
                   │                              │
                   │ 트랜잭션 ②(짧음):                │
                   │ SELECT ... FOR UPDATE         │
                   │ SKIP LOCKED                   │
                   │ + UPDATE status=PROCESSING    │
                   ▼                              │
            ┌──────────────┐                       │
            │ notification │                       │
            │ (PROCESSING) │◀── stuck recovery ────┤
            └──────┬───────┘                       │
                   │ 트랜잭션 ②' 종료, 락 해제          │
                   ▼                              │
            ┌──────────────────┐                  │
            │  외부 호출         │  (TX 밖)          │
            │  (mock sender)   │                  │
            └──────┬───────────┘                  │
                   │                              │
                   ▼                              │
            ┌──────────────┐                       │
            │ 트랜잭션 ③:    │                       │
            │ SENT/FAILED  │──────────────────────┘
            │ + error_log  │
            └──────────────┘
```

**핵심 포인트:**
- 외부 호출은 *트랜잭션 밖*에서 수행. RTT만큼 락 점유 시간이 늘어나는 문제 / 트랜잭션 로그 비대화 방지 (`impl-note 결정 1`).
- 워커가 외부 호출 도중 죽어도 row 상태는 `PROCESSING`으로 영속화되어 stuck 복구 워커가 `updated_at` 기준으로 식별 / 복구 가능.
- *발견 메커니즘* (현재: DB 폴링)과 *처리 책임* (Dispatcher)를 클래스 단위로 분리. 운영 환경에서 메시지 브로커 도입 시 Poller를 Consumer로 *교체할 수 있는 구조 의도*다 — Consumer 구현 자체는 본 과제 범위 밖이라 미실현 (`impl-note 결정 4`).

### 5.5 재시도 정책

| 항목 | 정책 |
|---|---|
| 백오프 공식 | `next_retry = now + base * 2^(fail_count - 1) + jitter(0~base)` |
| `base` 기본값 | 5초 (`application.yaml: notification.worker.base-backoff-seconds`) |
| jitter | 0 ~ base 사이 균등 분포 — 다중 워커 thundering herd 방지 |
| 최대 재시도 | `max_fail_count`(기본 5) 초과 시 DEAD_LETTER (`policy-note`) |
| 일시 / 영구 분류 | `FailureKind.{TRANSIENT, PERMANENT}`. PERMANENT는 즉시 DEAD_LETTER (재시도 무의미) |
| stuck 임계치 | `max_stuck_count`(기본 3) — `stuck_count`가 이 값에 도달하면 poison pill로 판단해 DEAD_LETTER (`schema-note 결정 8`) |
| 수동 재시도 | DEAD_LETTER → PENDING으로 되돌리되 카운터 미리셋 — 운영자 안티패턴 방지 (`policy-note 결정 1`) |

`max_fail_count`, `max_stuck_count`, `stuck_threshold_seconds`, `base_backoff_seconds`, `poll_interval_ms`, `batch_size`는 모두 `application.yaml`로 외부 주입 — 코드 재배포 없이 운영 중 조정 가능 (`impl-note 결정 3`).

### 5.6 알림 템플릿 관리 (선택 구현)

`src/main/resources/templates/{TYPE}.json` 4개 파일 (CUSTOM 제외) + `MessageTemplateRepository` 포트 + `JsonFileMessageTemplateRepository` 구현체 + `MessageRenderer` 서비스. CUSTOM 타입은 템플릿 조회 없이 `reference_data.message` / `reference_data.title`을 그대로 사용. Mock sender는 success 경로에서 렌더링된 title/body를 로그로 출력해 동작이 가시화됨.

`schema-note 결정 2`는 "운영 환경 전환 시 DB / CMS 구현체로 교체 가능"을 *설계 의도*로 명시. 실제 DB / CMS 구현체는 본 과제 범위에 포함되지 않는다.

### 5.7 API 응답 envelope

모든 엔드포인트가 `ApiResponse<T> { success, data, error, timestamp }` 형태로 응답. HTTP 상태 코드는 살리되 body 형태는 일관 유지 (`api-note 결정 1`).

## 미구현 / 제약사항

| 항목 | 사유 / 우회 |
|---|---|
| API 에러 별도 테이블(`api_error_log`) | SLF4J 파일 로깅(`logs/notif-sys.log`)으로 대체 (`api-note 결정 4`). 운영 환경에선 ELK / Loki 같은 로그 어그리게이터로 흘려보내는 게 표준 |
| 사용자 알림 목록 복합 인덱스 `(recipient_id, created_at DESC)` | 현재 `recipient_id` 단일 인덱스만. 트래픽 / 알림 수 늘어나면 추가 (`temp-note.md`) |
| 읽음 처리 first-write-wins 엄격 보장 | `Notification#markRead`의 in-memory `readAt == null` 체크 — 두 트랜잭션이 동시에 readAt=null을 보면 last-write-wins가 됨. 동작상 무해(둘 다 read 상태로 수렴)하나 엄격한 first-write-wins이 필요하면 `@Modifying UPDATE … WHERE read_at IS NULL`로 변경 가능 |
| 일괄 발송 API | 명세 범위 밖이라 미구현 |
| 시나리오 curl 스크립트 (`scripts/`) | 미구현. README의 "API 목록 및 예시"에 단일 호출 샘플만 게재. 흐름 예제(요청→상태→읽음 등)는 평가자가 README 예시들을 순서대로 호출하면 재현 가능 |
| 실제 인증 (JWT 등) | 명세 명시적 허용 — `X-User-Id` 헤더로 대체 (`api-note 결정 3`) |
| 실제 메시지 브로커 (Kafka 등) | 명세 명시적 면제. Mock sender + JSON 파일 템플릿으로 대체. Dispatcher / Poller 분리는 *전환 가능한 구조 의도*까지로, 실제 Consumer 구현은 미실현 |

## AI 활용 범위

본 과제는 Claude Code (Opus 4.7) 와 페어 프로그래밍으로 진행했다. 솔직하게 단계별로 누가 무엇을 했는지 적는다 — 평가에서 *AI 작성 비중*이 아닌 *결정의 사유 / 일관성 / 검증*을 봐 주시기를 부탁드린다.

### 1. 분석 단계 — 본인 작성, AI 첨삭(2일)
- 이 단계에서는 Claude code 가 아닌, Claude 웹 채팅UI를 활용해 큰 밑그림을 함께 그렸음.
- 아웃박스 패턴, SELECT FOR UPDATE SKIP LOCKED 을 활용한 DB단에서의 동시성 이슈 해결, 유니크 제약을 활용한 멱등성 이슈 해결 등의 핵심 아이디어를 제안받고 학습.
- `docs/analysis-note.md`의 본문(과제 정독 분석, 워커 설계, 멱등성 처리, 상태 관리, 재시도 정책 등)을 코딩 시작 전 본인이 직접 작성.
- 작성한 분석을 Claude code에서 검증 요청 → "잘 짚은 부분 / 보완이 필요한 부분" 형태로 첨삭받음 (analysis-note 하단의 "AI의 첨삭 결과" 섹션이 그 출력).

### 2. 설계 단계 — AI 트레이드오프 제시 + 본인 채택(1일)
- 이 단계에서는 매 작업 시작 전에 AI와 방향성을 논의하고, 결정한 뒤 결정 내역을 *-note 의 형태로 정리 후 작업을 시작함.
- 스키마 / 정책 / 구현 / API 결정 노트는 결정/사유/대안 형식으로 정리.
- AI가 트레이드오프를 제시한 결정: 통합 응답 envelope vs REST 순수, Pageable 사용 여부, API 에러 별도 테이블 도입 여부 등. 채택 / 거부는 본인 판단.

### 3. 구현 단계 — AI가 대부분 작성, 본인이 리뷰 / 채택(0.5일)
- 엔티티 / 도메인 서비스 / DTO / 컨트롤러 / 테스트 코드의 작성은 대부분 AI가 했으며, 본인은 PR 리뷰 관점에서 검토하고 채택 / 수정 지시.
- 다만 *어떤 클래스를 만들지, 어떤 시그니처일지, 어떤 트랜잭션 경계일지*는 사전에 노트(`impl-note`, `api-note`)로 합의한 뒤 AI가 그 결정을 따랐음. 즉 "구조 결정" 단계는 본인 주도, "타이핑" 단계는 AI 주도.

### 4. 검증 / 디버깅 단계(0.5일)
- 1단계 스모크 부팅(실제 Postgres 18 컨테이너에 부팅하여 Hibernate 생성 DDL 검증)은 AI가 절차 제안 → 부팅 결과를 공유하고 AI가 `failure_reason varchar(255)` 잘림 위험 식별 → 본인이 fix 채택.
- 2단계 통합 테스트(TestContainers, 멱등성 / SKIP LOCKED 동시성) 작성은 AI가 주도, 본인은 통과 확인.

### 5. 결정의 책임
- 모든 설계 결정의 *채택*은 본인 판단. AI에 트레이드오프를 제시하도록 지시했고, 그 중 결정된 사항을 AI를 활용해 노트에 기록.
- 코드 동작 정합성은 통합 테스트 74개로 검증되지만, AI 생성 코드를 *행 단위로 다시 작성*해 본 부분은 거의 없음.
- 본인의 명시적 기여: 분석 노트 본문 작성, 모든 설계 결정의 채택 판단, "스모크 → 통합 테스트 → API → README" 작업 순서 결정, AI 제안 거부 / 수정 / 명세 재확인 등.

### 인텔리제이 환경에서의 컨텍스트 운영
세션이 끊길 때마다 다음 세션이 즉시 복귀할 수 있도록 `CLAUDE.md`(gitignored)를 인계 노트로 운영했다. 과제 요구사항 ↔ 현 상태 매트릭스, 이번 세션의 결정사항, 환경 이슈(JAVA_HOME 위치 등)를 한 파일에 모아 다음 세션에서 컨텍스트 재구성 비용 최소화. 이 운영 방식 자체도 본 README의 "솔직성" 원칙의 연장으로 공개.

## API 목록 및 예시

| 메서드 | 경로 | 설명 | 가드 |
|---|---|---|---|
| `POST` | `/notifications` | 발송 요청 등록 (즉시 발송 X, 접수만) | 없음 |
| `GET` | `/notifications/{id}` | 알림 상태 조회 | 없음 |
| `GET` | `/users/{userId}/notifications` | 수신자 알림 목록 (읽음 / 안읽음 필터) | `X-User-Id` == `userId` |
| `PATCH` | `/notifications/{id}/read` | 읽음 처리 | `X-User-Id` == `recipient_id` |
| `POST` | `/notifications/{id}/retry` | DEAD_LETTER 수동 재시도 (운영자) | 없음 |

### 응답 envelope

성공:
```jsonc
{
  "success": true,
  "data": { /* 엔드포인트별 페이로드 */ },
  "error": null,
  "timestamp": "2026-04-30T10:00:00.123456Z"
}
```

실패:
```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "Request validation failed",
    "details": { "eventId": "must not be blank" }
  },
  "timestamp": "2026-04-30T10:00:00.123456Z"
}
```

### 에러 코드 표

| HTTP | code | 발생 시점 |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Bean Validation 실패 |
| 400 | `MALFORMED_REQUEST` | JSON 파싱 실패 / 알 수 없는 enum 값 |
| 400 | `MISSING_USER_ID` | `X-User-Id` 헤더 누락 |
| 400 | `INVALID_ARGUMENT` | 일반 IllegalArgumentException |
| 403 | `FORBIDDEN` | 본인 자원 아님 |
| 404 | `NOT_FOUND` | id로 조회한 자원 없음 |
| 409 | `DUPLICATE` | 멱등성 유니크 제약 위반 |
| 409 | `INVALID_STATE` | 상태 전이 불가 (예: PENDING 알림에 read 시도) |
| 500 | `INTERNAL_ERROR` | 처리되지 않은 예외 (스택트레이스는 로그에만) |

### 샘플 요청 / 응답

#### 1) 알림 발송 요청 등록

```bash
curl -X POST http://localhost:8080/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "recipientId": 1,
    "type": "COURSE_REGISTRATION",
    "channel": "EMAIL",
    "eventId": "course-001",
    "referenceData": { "lectureId": 42, "lectureName": "Spring Boot 마스터" }
  }'
```

응답 `202 Accepted`:
```json
{
  "success": true,
  "data": {
    "id": 1,
    "recipientId": 1,
    "type": "COURSE_REGISTRATION",
    "channel": "EMAIL",
    "eventId": "course-001",
    "referenceData": { "lectureId": 42, "lectureName": "Spring Boot 마스터" },
    "status": "PENDING",
    "failCount": 0,
    "stuckCount": 0,
    "retryAfter": null,
    "failureReason": null,
    "scheduledAt": null,
    "readAt": null,
    "createdAt": "2026-04-30T10:00:00.123456Z",
    "updatedAt": "2026-04-30T10:00:00.123456Z"
  },
  "error": null,
  "timestamp": "2026-04-30T10:00:00.234567Z"
}
```

스케줄링이 필요하면 `scheduledAt: "2026-05-01T09:00:00Z"` 추가. CUSTOM 메시지는 `type: "CUSTOM"` + `referenceData: { "title": "...", "message": "..." }`.

#### 2) 알림 상태 조회

```bash
curl http://localhost:8080/notifications/1
```

#### 3) 사용자 알림 목록 (안 읽은 것만, 페이지네이션)

```bash
curl "http://localhost:8080/users/1/notifications?read=false&page=0&size=20&sort=createdAt,desc" \
  -H "X-User-Id: 1"
```

응답 `200 OK` (Spring Data `Page<T>` 직렬화 형태):
```jsonc
{
  "success": true,
  "data": {
    "content": [ /* NotificationResponse 객체 N개 (위 1번 응답의 data 형태와 동일) */ ],
    "pageable": { "pageNumber": 0, "pageSize": 20, "sort": { /* ... */ } },
    "totalElements": 2,
    "totalPages": 1,
    "first": true,
    "last": true,
    "size": 20,
    "number": 0,
    "numberOfElements": 2,
    "empty": false
  },
  "error": null,
  "timestamp": "2026-04-30T10:00:00.234567Z"
}
```

#### 4) 읽음 처리

```bash
curl -X PATCH http://localhost:8080/notifications/1/read \
  -H "X-User-Id: 1"
```

`PENDING`인 알림에 호출 시 `409 Conflict { "code": "INVALID_STATE" }`.

#### 5) 수동 재시도 (DEAD_LETTER 알림만)

```bash
curl -X POST http://localhost:8080/notifications/1/retry
```

## 데이터 모델 설명

### ERD (텍스트)

```
┌────────────────────────────────────────┐
│ notification                           │
├────────────────────────────────────────┤
│ id              BIGSERIAL  PK          │
│ recipient_id    BIGINT     NOT NULL    │  ── 수신자
│ type            VARCHAR(30) NOT NULL   │  ── COURSE_REGISTRATION / PAYMENT_CONFIRMED /
│                                         │     LECTURE_REMINDER / CANCELLATION / CUSTOM
│ channel         VARCHAR(10) NOT NULL   │  ── EMAIL / IN_APP
│ event_id        VARCHAR(100) NOT NULL  │  ── 외부 시스템의 이벤트 식별자, 멱등성 키
│ reference_data  JSONB                  │  ── 부가 참조 (lecture_id 등) + CUSTOM 메시지 본문
│ status          VARCHAR(20) NOT NULL   │  ── PENDING / PROCESSING / SENT / FAILED / DEAD_LETTER
│ fail_count      INT         NOT NULL   │  ── 외부 응답 실패 누적
│ stuck_count     INT         NOT NULL   │  ── 워커 사망으로 인한 stuck 복구 누적
│ retry_after     TIMESTAMPTZ            │  ── FAILED 상태에서 다음 재시도 가능 시각
│ failure_reason  TEXT                   │  ── 최신 실패 사유 캐시 (전체 이력은 error_log)
│ scheduled_at    TIMESTAMPTZ            │  ── 예약 발송 시각 (선택 구현)
│ read_at         TIMESTAMPTZ            │  ── 읽음 시각 (NULL = 안 읽음)
│ created_at      TIMESTAMPTZ NOT NULL   │
│ updated_at      TIMESTAMPTZ NOT NULL   │
│                                         │
│ UNIQUE (recipient_id, event_id, type, channel)  ── 멱등성 제약
│ INDEX  (recipient_id)                   │
│ INDEX  (status)                         │
└────────────────────────────────────────┘
                  │ 1
                  │
                  │ N
                  ▼
┌────────────────────────────────────────┐
│ notification_error_log                 │
├────────────────────────────────────────┤
│ id              BIGSERIAL  PK          │
│ notification_id BIGINT     FK NOT NULL │  ── notification.id
│ error_message   TEXT       NOT NULL    │
│ occurred_at     TIMESTAMPTZ NOT NULL   │
└────────────────────────────────────────┘
```

### 주요 컬럼 / 인덱스 / 제약 설명

- **`reference_data` JSONB** — 알림 타입마다 부가 정보 구조가 다르므로 개별 컬럼 대신 JSONB 단일 컬럼. 직접 조회 / 필터링 대상이 아니라 *렌더링 시점의 부가 정보 용도* (`schema-note 결정 1`).
- **`uq_idempotency UNIQUE (recipient_id, event_id, type, channel)`** — 멱등성 단위. 동일 키로 두 번 INSERT 시 두 번째가 차단되어 중복 발송 / 동시 요청 race를 모두 막음.
- **`idx_notification_status`** — 워커 폴링 쿼리(`WHERE status IN ('PENDING','FAILED')` / `WHERE status='PROCESSING'`)의 1차 필터 가속.
- **`failure_reason TEXT`** — `varchar(255)`로 두면 긴 에러 메시지가 잘릴 위험. 명시적으로 TEXT로 지정 (실제 부팅으로 검증).
- **enum `CHECK` 제약 (Hibernate 자동 생성)** — `channel`, `status`, `type` 컬럼에 enum 값 외 INSERT를 DB가 차단. 도메인 무결성 강화.
- **모든 timestamp `TIMESTAMPTZ`** — 시간대 정보 보존. JPA `Instant`와 자연스럽게 매핑.

### 알림 상태 머신 (위 5.1 참조)

전이 트리거:
- `PENDING → PROCESSING`: 워커 픽업 (`SELECT FOR UPDATE SKIP LOCKED` + `UPDATE`)
- `PROCESSING → SENT`: sender가 성공 응답
- `PROCESSING → FAILED`: sender가 TRANSIENT 실패 응답 (재시도 예약)
- `PROCESSING → DEAD_LETTER`: sender가 PERMANENT 응답 / `fail_count` 임계치 초과 / `stuck_count` 임계치 초과
- `PROCESSING → PENDING`: stuck 복구 (`updated_at` 기준 시간 초과 + `stuck_count` 미초과)
- `FAILED → PROCESSING`: `retry_after` 도래 후 재픽업
- `DEAD_LETTER → PENDING`: 운영자 수동 재시도 (`POST /retry`, 카운터 미리셋)

## 테스트 실행 방법

```bash
# 전체 테스트 실행
./gradlew test
```

테스트는 **Testcontainers를 통해 자체 PostgreSQL 컨테이너를 자동 기동**하므로, `docker compose up`이 사전에 떠 있을 필요는 없다 (Docker 데몬만 실행 중이면 됨). 통합 테스트는 컨테이너 부팅 + Spring 컨텍스트 부팅에 ~5-6초가 들지만 동일 컨텍스트를 모든 통합 테스트가 공유해 두 번째 클래스부터는 캐시된다.

### 테스트 통계

총 **74개 테스트**:

| 종류 | 클래스 | 개수 | 검증 범위 |
|---|---|---|---|
| 단위 | `NotificationStateMachineTest` | 15 | 도메인 상태 전이 가드 |
| 단위 | `RetryPolicyTest` | 4 | 백오프 공식 / max 임계치 |
| 단위 | `MockSenderTest` | 7 | event_id 패턴별 결과 + CUSTOM 렌더 실패 |
| 단위 | `MessageRendererTest` | 8 | 템플릿 placeholder 치환 / CUSTOM 분기 |
| 단위 | `NotificationDispatcherTest` | 3 | sender 예외 → TRANSIENT 매핑 |
| 단위 | `NotificationOutcomeRecorderTest` | 4 | 성공 / 일시 / 영구 실패 / 재시도 소진 분기 |
| 단위 | `NotificationStuckRecoveryServiceTest` | 3 | stuck 임계치 진단 |
| **통합** | `WorkerIntegrationTest` | **8** | 멱등성 제약 (정/반례), SKIP LOCKED 동시성, 풀 폴 사이클 3종, 스케줄링 미래/과거 |
| **통합** | `NotificationControllerCreateTest` | **7** | POST /notifications: happy path, 검증, 멱등성 충돌, CUSTOM |
| **통합** | `NotificationControllerQueryAndActionsTest` | **14** | GET /id, GET 목록 (read 필터), PATCH read, POST retry |
| 컨텍스트 | `NotifSysApplicationTests` | 1 | Spring 부팅 검증 |

### 핵심 통합 테스트가 검증하는 것

- **DB 멱등성 제약** — `(recipient_id, event_id, type, channel)`로 중복 INSERT 시 `DataIntegrityViolationException`. type만 달라도 통과 (`schema-note 결정 6` 정당화).
- **다중 워커 SKIP LOCKED** — 5개 워커가 batchSize=50으로 50 row 동시 픽업 → 합쳐서 정확히 50건이 1번씩만 PROCESSING. SKIP LOCKED 없거나 markProcessing 가드 없으면 IllegalStateException으로 실패.
- **풀 폴 사이클 3종** — `success-`/`fail-transient-`/`fail-permanent-` 접두 event_id로 mock sender가 분기, 각각 SENT / FAILED+retry_after / DEAD_LETTER 종착 검증.
- **스케줄링** — `scheduledAt`이 미래면 폴 사이클에서 스킵, 과거면 즉시 픽업.

### 통합 테스트 인프라

- `TestcontainersConfiguration` 클래스가 `@TestConfiguration` + `@ServiceConnection`으로 PostgreSQL 컨테이너를 정의. 모든 `@SpringBootTest` 클래스가 `@Import`해서 *컨텍스트 캐시 + 컨테이너*를 공유.
- 테스트 전용 `application.yaml`이 `poll-interval-ms`를 1일로 두어 `@Scheduled`가 테스트 도중 발화하지 않게 차단. 테스트는 `poller.poll()`을 *수동 호출*해 결정성을 보장.

---

## 라이선스 / 제출 메모

- 본 리포지토리는 채용 과제용으로 작성됨
- 개발 환경: Windows 11, IntelliJ IDEA 2026.1.1, Java 17 (corretto), Docker
- 설계 노트: `docs/` 디렉터리 (`analysis-note`, `schema-note`, `policy-note`, `impl-note`, `api-note`)