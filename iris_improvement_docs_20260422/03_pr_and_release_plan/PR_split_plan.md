# PR 분리 계획

전체 수정은 한 PR로 넣으면 리뷰가 거의 불가능합니다. 아래 순서대로 나누면 기능 계약, 보안 경계, 런타임 안정성, 입력 검증을 단계적으로 안정화할 수 있습니다.

## 현재 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- `PR 1 — 테스트 번들 무결성 복구`: 현재 워크트리에 구현 반영됨. closeout scripts 복구, `tests/closeout_packet_scripts_test.sh`, `BUNDLE_MANIFEST.txt`, 재생성 번들 export/verify까지 확인했습니다.
- `PR 2 — bridge optional/readiness 계약 정리`: 현재 워크트리에 구현 반영됨.
- `PR 3 — 설정 변경 원자성`: 현재 워크트리에 구현 반영됨.
- `PR 4 — shell quoting hardening`: 현재 워크트리에 구현 반영됨.
- `PR 5 — SSE 안정성`: 현재 워크트리에 구현 반영됨.
- `PR 6 — shutdown / actor boundary / SQLite 안정성`: 현재 워크트리에 구현 반영됨.
- `PR 7 — 입력 검증 및 인증 계약 강화`: 현재 워크트리에 구현 반영됨.
- `PR 8 — P2 운영 품질 개선`: 현재 워크트리에 구현 반영됨.

## PR 1 — 테스트 번들 무결성 복구

포함 항목:

- closeout 테스트 누락 파일 수정
- 테스트 시작부 필수 파일 검증 추가
- `BUNDLE_MANIFEST.txt` 정합성 확인

완료 조건:

- 모든 shell test가 누락 파일 없이 실행된다.
- closeout 기능이 유지되는지 제거되는지 명확히 결정되어 있다.

## PR 2 — bridge optional/readiness 계약 정리

포함 항목:

- `RuntimeConfigReadiness.bridgeRequired` 추가
- `/ready` bridge 검사 조건화
- `IRIS_REQUIRE_BRIDGE` 문서화
- readiness 테스트 추가

완료 조건:

- 텍스트 전용 모드와 bridge 필수 모드가 테스트로 구분된다.
- README와 코드가 같은 계약을 말한다.

## PR 3 — 설정 변경 원자성

포함 항목:

- `ConfigMutationPlan` 도입
- persist-then-commit 구조 적용
- `ConfigStateStore.replace()` 추가
- 저장 실패 테스트 추가

완료 조건:

- 설정 저장 실패 시 runtime state가 변경되지 않는다.

## PR 4 — shell quoting hardening

포함 항목:

- `iris_control` `sh_quote()` 적용
- zygisk script quoting 적용
- Rust `config_sync` path quoting 적용
- env file 기반 secret 전달 검토
- secret 전달 운영 문서 추가

완료 조건:

- 특수문자 포함 경로/token으로도 명령 주입이 발생하지 않는다.

## PR 5 — SSE 안정성

포함 항목:

- `openSubscriberWithReplaySuspend()` 추가
- SSE frame formatter 추가
- Rust SSE parser multiline 처리
- actor 예외 fallback 처리
- SSE reconnect 테스트 추가

완료 조건:

- 재연결 중 이벤트 유실이 없고 multiline payload가 정상 처리된다.

## PR 6 — shutdown / actor boundary / SQLite 안정성

포함 항목:

- `CommandIngressService.closeSuspend()` 추가
- `ObserverHelper.close()` 순서 수정
- `ReplyAdmissionService` actor state boundary 수정
- `AndroidSqliteDriver` statement close
- `SqliteWebhookDeliveryStore` claim 구조 수정

완료 조건:

- 종료 시 checkpoint 유실 위험이 줄고 SQLite resource leak 가능성이 제거된다.

## PR 7 — 입력 검증 및 인증 계약 강화

포함 항목:

- multipart unknown part 거부
- body size overflow 방어
- config load validation 확장
- Kotlin/Rust canonical query fixture 추가
- nonce 정책 문서화

완료 조건:

- 비정상 입력이 조용히 통과하지 않고 명확히 reject된다.

## PR 8 — P2 운영 품질 개선

포함 항목:

- APK checksum SHA-256 전환
- readiness error 정보 노출 최소화
- ConfigStateStore lock 단순화
- SqliteSseEventStore prune 검증
- 관련 README 업데이트

완료 조건:

- 릴리스/운영/보안 품질 항목이 문서와 테스트로 고정된다.
