# 최종 릴리스 전 점검표

아래 항목은 모든 PR이 합쳐진 뒤 릴리스 직전에 확인합니다.

## 현재 워크트리 확인 메모

기준 시점: `2026-04-23` 현재 워크트리

이 체크리스트는 최종 릴리스 직전 재확인용으로 유지합니다. 이번 동기화에서는 실행 근거가 있는 항목만 별도로 적고, 본문 체크박스는 일괄 완료 처리하지 않습니다.

### 이번에 확인한 실행 근거

- [x] `bash tests/closeout_packet_scripts_test.sh`
- [x] `bash tests/iris_control_shell_quote_test.sh`
- [x] `bash tests/zygisk_next_bootstrap_test.sh`
- [x] `bash tests/zygisk_next_watchdog_test.sh`
- [x] `cargo test --manifest-path tools/iris-ctl/Cargo.toml sse`
- [x] `scripts/export-closeout-packet.sh <tmpdir>` + `python3 scripts/verify_closeout_packet.py <packet_dir>`

### 아직 최종 릴리스 전 재확인이 남은 항목

- [ ] STEP 09 ~ STEP 22 구현은 반영되었으므로 최종 릴리스 전에는 전체 회귀 범위만 재확인하면 됩니다.

## 1. 자동 테스트

- [ ] shell tests 전체 통과
- [ ] Gradle unit test 전체 통과
- [ ] Rust workspace test 전체 통과
- [ ] Kotlin/Rust canonical auth fixture test 통과
- [ ] closeout 테스트가 누락 파일 없이 통과

## 2. readiness / bridge

- [ ] bridge optional 모드에서 `/ready` 성공
- [ ] bridge required 모드에서 bridge token 없음 시 `/ready` 실패
- [ ] bridge required 모드에서 bridge health 실패 시 `/ready` 실패
- [ ] README의 bridge 설명과 실제 동작 일치

## 3. config

- [ ] 설정 저장 실패 시 runtime state 불변
- [ ] 즉시 적용 설정과 재시작 필요 설정 응답 구분
- [ ] config 파일 직접 수정 시 잘못된 endpoint 거부
- [ ] config 파일 직접 수정 시 잘못된 route 거부
- [ ] secret/token 앞뒤 공백 또는 제어문자 거부

## 4. shell / secret

- [ ] token에 작은따옴표, 공백, `$()`, 백틱 포함 시 command injection 없음
- [ ] config path에 공백/작은따옴표 포함 시 command 동작
- [ ] env 파일 사용 시 권한 0600
- [ ] 로그에 token 원문 미노출

## 5. SSE

- [ ] Last-Event-ID 재연결 중 이벤트 유실 없음
- [ ] replay/live 중복 이벤트 id 기준 방어
- [ ] multiline payload frame 정상
- [ ] Rust client가 multiple data line 복원
- [ ] SSE actor store 예외 시 무한 대기 없음

## 6. webhook outbox

- [ ] dispatcher start 중복 호출에도 polling loop 하나
- [ ] close 중 start 경합에서 상태 꼬임 없음
- [ ] 동시 claim에서 동일 delivery 중복 claim 없음
- [ ] claim update 실패 row가 결과에서 제외됨
- [ ] route별 endpoint 정책이 README와 일치

## 7. shutdown

- [ ] graceful shutdown 시 dispatch loop 종료 대기
- [ ] 종료 직전 checkpoint flush
- [ ] reply admission worker shutdown 경계 테스트 통과
- [ ] database close 전 pending flush 완료

## 8. 입력 검증

- [ ] multipart unknown form part 400
- [ ] multipart unknown file part 400
- [ ] metadata size 초과 413
- [ ] body size overflow-safe check
- [ ] max body 초과 요청 413

## 9. 릴리스 산출물

- [ ] APK SHA-256 checksum 생성
- [ ] checksum mismatch 실패 확인
- [ ] README의 MD5 표현 제거
- [ ] bundle manifest에 테스트가 참조하는 파일 모두 포함

## 10. 운영 보안

- [ ] `/ready` 상세 reason 기본 비노출
- [ ] `IRIS_READY_VERBOSE`는 개발용으로만 문서화
- [ ] `IRIS_BIND_HOST=0.0.0.0` 사용 시 접근 제어 안내
