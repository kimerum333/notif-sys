# 구현 노트

설계 단계(`analysis-note.md`, `schema-note.md`)에서 미처 다루지 못했고, 실제 구현을 진행하며 도출되는 결정사항들을 누적함. 정책(`policy-note.md`)이 런타임 *동작* 정책이라면, 이 노트는 *구현 구조*에 대한 결정.

## 결정 1. 워커 트랜잭션 경계 — 외부 호출은 TX 밖

**결정:** 워커는 `짧은 TX1` → `외부 호출(TX 밖)` → `짧은 TX2`의 3단계로 처리. 외부 시스템 응답을 트랜잭션 안에서 기다리지 않음.

**구체:**
- **TX1**: `SELECT ... FOR UPDATE SKIP LOCKED` → `UPDATE status=PROCESSING` → `COMMIT`
- **TX 밖**: `sender.send(notification)` 호출, 응답/예외 수집
- **TX2**: 결과에 따라 `markSent` / `markFailed` / `markDeadLetter` + `error_log` 적재 → `COMMIT`

**사유:**
- 외부 호출을 TX1 안에 두면 응답이 느릴 때 락 점유 시간이 외부 시스템 RTT만큼 늘어나, 같은 워커가 다른 알림을 처리할 수 없게 되고 트랜잭션 로그도 비대해짐.
- TX1 종료 시점에 row 상태가 `PROCESSING`으로 영속화되므로, 워커가 외부 호출 도중 죽어도 stuck 복구 워커가 `updated_at` 기준으로 이를 식별 가능.
- 이 분리가 가능한 것은 **상태 자체가 진행 단계를 표현하기 때문**. PROCESSING이라는 중간 상태가 있어 외부 호출을 TX 밖으로 빼도 정합성이 유지됨.

## 결정 2. 실패 분류 — HTTP 응답코드 기준의 transient/permanent 이원화

**결정:** `NotificationSender`는 `SendResult`를 반환. 실패는 `FailureKind { TRANSIENT, PERMANENT }`로 이원화.

**매핑 규칙(HTTP 기반 sender 구현체 내부에서 적용):**
- HTTP 4xx / 페이로드 형식 오류 / 인증 영구 실패 → `PERMANENT` → 즉시 `DEAD_LETTER`
- HTTP 5xx / 네트워크 타임아웃 / `IOException` → `TRANSIENT` → `FAILED` + `retry_after` 산정
- Mock sender는 HTTP를 거치지 않으므로 `FailureKind`를 직접 리턴

**사유:**
- 모든 실패를 transient로 취급하면 영구 실패 알림이 max_fail까지 의미없이 재시도됨 (외부 시스템에 부하, 본인 워커도 점유).
- 내부 에러코드 체계는 과제 규모에 과도. HTTP 코드만으로 분류 의도 표현이 충분.
- HTTP→FailureKind 매핑을 sender 구현체 내부에 가두면, 추후 sender 프로토콜이 바뀌어도(gRPC, SMTP 등) 서비스 레이어는 영향 없음. 운영 환경 전환 가능 구조의 일부.

**대안:** 운영 시 transient로 잡혔던 에러가 사실상 영구 실패였음이 드러나면 매핑 테이블을 외부 config로 빼는 방향으로 확장 가능.

## 결정 3. 백오프 / 임계치 외부 주입

**결정:** 다음 값들을 `application.yaml`로 외부화. 코드에 하드코딩 금지.

```yaml
notification:
  worker:
    max-fail-count: 5            # FAILED → DEAD_LETTER 임계
    max-stuck-count: 3           # PROCESSING stuck → DEAD_LETTER 임계 (poison pill 방지)
    stuck-threshold-seconds: 60  # PROCESSING이 이 시간 이상 지속되면 stuck 판정
    base-backoff-seconds: 5      # 백오프 베이스 (지수 + jitter 적용)
```

**백오프 공식:** `next_retry = now + base * 2^(fail_count - 1) + random_jitter(0 ~ base)`

**사유:**
- 운영 중 외부 시스템 SLA에 따라 적정값이 달라지므로 코드 재배포 없이 조정 가능해야 함.
- jitter 없는 순수 지수 백오프는 다중 워커 환경에서 재시도 타이밍이 동기화되어 thundering herd 발생 가능.

## 결정 4. Dispatcher / Poller 인터페이스 경계 — 운영 전환 가능성의 실체

**결정:** 알림 1건의 발송 책임과, 발송 대상을 발견하는 책임을 별도 컴포넌트로 분리.

```
NotificationDispatcher  ← 알림 1건 받아 sender 호출 + 결과 반영 (재사용)
NotificationPoller      ← DB FOR UPDATE SKIP LOCKED 폴링 후 dispatcher 호출 (현재 구현)
NotificationConsumer    ← 운영 전환 시 브로커 메시지 수신 후 dispatcher 호출 (대체 구현, 본 과제 미구현)
```

**사유:**
- "운영 환경에서 메시지 브로커로 전환 가능한 구조"라는 요구사항을 코드 수준에서 증명하는 가장 직접적인 방법. dispatcher가 발견 메커니즘에 의존하지 않으므로 poller만 consumer로 갈아끼우면 전환 완료.
- README의 아키텍처 다이어그램에서 이 경계를 시각화하면 평가에서 즉각 파악 가능.

## 결정 5. Mock Sender 실패 시뮬레이션 — `event_id` 패턴 기반

**결정:** Mock sender는 `event_id`의 패턴으로 결과를 결정.

| `event_id` 접두 | 동작 |
|---|---|
| `success-` 또는 그 외 | 성공 |
| `fail-transient-` | `Failure(TRANSIENT)` 반환 |
| `fail-permanent-` | `Failure(PERMANENT)` 반환 |
| `stuck-` | 응답을 무한 지연 (테스트에서는 짧은 타임아웃 후 stuck 처리됨을 검증) |

**사유:**
- 확률 기반(`mock.fail-rate`) 시뮬레이션은 테스트 결정성을 해침. 같은 입력에 같은 결과가 나와야 회귀 테스트가 의미있음.
- `event_id`로 분기하면 단일 Mock 구현체로 모든 실패 시나리오를 테스트 데이터만 바꿔 재현 가능.
- 실제 운영 코드에는 영향 없음 (실제 sender는 event_id를 보지 않음).

**대안:** 시나리오별 별도 Mock 구현체(`AlwaysSuccessMockSender`, `AlwaysFailMockSender`)도 가능하나, 통합 테스트에서 워커 한 사이클 안에 성공/실패가 섞여야 할 때 단일 구현체가 편리.

## 결정 6. Notification 엔티티 state mutator의 visibility 통제

**결정:** `markProcessing` / `markSent` / `markFailed` / `markDeadLetter` / `recoverFromStuck` / `markStuckDeadLetter` / `markRead` / `resetForManualRetry` 등 모든 상태 변경 메서드를 package-private으로 둠. `com.example.notifsys.domain.notification` 패키지 내부의 서비스(Dispatcher / OutcomeRecorder / 추후 Poller·ReadService 등)만 호출 가능.

**사유:**
- 도메인 메서드가 public이면, 호출자가 부수 책임(`error_log` 적재, 백오프 시각 산정 등)을 수행하지 않은 채 entity 상태만 바꾸는 코드 경로가 컴파일러에 의해 차단되지 않음. 데이터 정합성이 코드 컨벤션과 리뷰에만 의존.
- 예: `markFailed` 호출 후 `errorLogRepo.save`를 빠뜨리면 알림은 실패로 기록되지만 에러 이력은 유실. 신규 개발자가 같은 실수를 반복할 위험.
- package-private으로 두면 단일 서비스(`OutcomeRecorder`)를 거치도록 강제됨. 외부 패키지에서 entity 상태를 직접 변경하려는 시도는 컴파일 에러로 즉시 발견.

**트레이드오프:** 서비스 클래스를 entity와 동일 패키지에 위치시켜야 함(엄격한 헥사고날 레이어링과 거리). 본 과제 규모에선 데이터 정합성 보장이 패키지 분리보다 우선이라 판단.

**대안:** 도메인 이벤트 기반(메서드는 public 유지, 상태 변경 시 이벤트 발행 → 리스너가 부수 책임 수행). 결합도는 낮으나 복잡도 상승. 모듈 분할 필요 시점에 재검토.