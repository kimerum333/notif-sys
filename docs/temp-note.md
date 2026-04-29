# 임시 메모

본 흐름과 별개로, 코딩 중 떠오른 "나중에 처리할 항목"을 누적하는 문서. 처리 완료 시 체크.

## 미처리

### API
- [ ] 수동 재시도 엔드포인트 (`POST /notifications/{id}/retry`)
  - DEAD_LETTER 상태인 알림에만 동작
  - 정책은 `policy-note.md` 결정 1 따름 (카운터 미리셋, 상태만 PENDING으로)

### 빌드/실행 도구
- [ ] Makefile 작성 (Windows/Mac/Linux 환경 구분 없이 통일된 커맨드)
  - 현재 윈도우에서 `gradlew.bat` 직접 호출 중
  - `make build`, `make run`, `make test`, `make clean` 정도 정의
  - JAVA_HOME 자동 감지 또는 README에서 안내

### 동작 검증용 스크립트
- [ ] curl 또는 httpie 스크립트로 API 호출 시나리오 정리 (`scripts/` 또는 README 내)
  - 알림 발송 요청 → 상태 조회 → 사용자 알림 목록 조회 → 읽음 처리 흐름
  - DEAD_LETTER 만들기 위한 강제 실패 시나리오 (테스트용 mock sender 옵션)

### 정합성/성능 개선
- [ ] 읽음 처리 first-write-wins 보장
  - 현재 `Notification#markRead`는 `if (readAt == null) readAt = now()` 형태. JPA managed entity 위 in-memory 체크라, 두 트랜잭션이 동시에 readAt=null을 보고 둘 다 update하는 경우 last-write-wins (두 번째 timestamp가 덮어씀).
  - 동작상 문제는 없지만 엄밀한 first-write-wins로 가려면 ReadService에서 `@Modifying UPDATE notification SET read_at = :now WHERE id = :id AND read_at IS NULL` 형태의 조건부 update 사용.
- [ ] 사용자 알림 목록 조회용 복합 인덱스
  - 현재는 `idx_notification_recipient` 단일 컬럼만 있음. `WHERE recipient_id = ? ORDER BY created_at DESC` 패턴이 빈번하면 `(recipient_id, created_at DESC)` 복합 인덱스가 정렬 비용 제거에 효과적.

### 제출 준비
- [ ] master → main 브랜치 rename (과제 요구사항: main 브랜치 기준 실행 가능 상태)
- [ ] README.md 작성 (필수 항목: 프로젝트 개요 / 기술 스택 / 실행 방법 / 요구사항 해석 / 설계 결정 / 미구현 / AI 활용 범위 / API 목록 / 데이터 모델 / 테스트 실행 방법)
- [ ] 비동기 처리 + 재시도 정책 별도 문서 (README 내 또는 `docs/async-retry-design.md`)
- [ ] AI 활용 범위 기재 — Claude로 설계 검토, 보일러플레이트 제안, 결정 사유 정리 등

## 처리 완료
(처리되면 여기로 옮기기)