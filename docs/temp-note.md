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

## 처리 완료
(처리되면 여기로 옮기기)