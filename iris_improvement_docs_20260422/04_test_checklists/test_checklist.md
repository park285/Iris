# 테스트 체크리스트

## 현재 실행 근거

기준 시점: `2026-04-23` 현재 워크트리

- [x] `bash tests/closeout_packet_scripts_test.sh`
- [x] `bash tests/iris_control_shell_quote_test.sh`
- [x] `bash tests/zygisk_next_bootstrap_test.sh`
- [x] `bash tests/zygisk_next_watchdog_test.sh`
- [x] `cargo test --manifest-path tools/iris-ctl/Cargo.toml sse`
- [ ] Gradle/Kotlin focused rerun은 repo-compatible 명령을 별도로 찾아 다시 실행해야 합니다. 현재 `./gradlew ... --tests ...` 형태는 이 저장소에서 거부되었습니다.

## 1. Shell tests

```bash
bash tests/iris_control_env_loading_test.sh
bash tests/iris_control_device_selection_test.sh
bash tests/iris_control_stop_race_test.sh
bash tests/zygisk_next_bootstrap_test.sh
bash tests/zygisk_next_bootstrap_bridge_ready_test.sh
bash tests/zygisk_next_watchdog_test.sh
bash tests/closeout_packet_scripts_test.sh
```

## 2. Android/Kotlin tests

```bash
./gradlew test
```

환경에 따라 Gradle wrapper가 외부 네트워크에서 distribution을 내려받으려 할 수 있습니다. CI에서는 wrapper distribution cache 또는 내부 mirror를 준비합니다.

## 3. Rust tests

```bash
cargo test --workspace
```

## 4. 신규로 추가해야 하는 핵심 테스트

### 설정 원자성

- [ ] persistence save 실패 시 runtime config 불변
- [ ] immediate apply 설정 성공/실패
- [ ] restart required 설정 성공/실패
- [ ] 동시 설정 변경 요청

### bridge readiness

- [ ] `IRIS_REQUIRE_BRIDGE=false`에서 bridge token 없음 + `/ready` 성공
- [ ] `IRIS_REQUIRE_BRIDGE=true`에서 bridge token 없음 + `/ready` 실패
- [ ] bridge health fail 시 required 모드에서 `/ready` 실패

### shell quoting

- [ ] config path에 공백 포함
- [ ] config path에 작은따옴표 포함
- [ ] token에 `$()` 포함
- [ ] token에 백틱 포함
- [ ] token 원문 로그 미노출

### SSE

- [ ] reconnect 중 emit된 이벤트 유실 없음
- [ ] replay/live 중복 id 제거
- [ ] multiline payload formatter
- [ ] Rust parser multiple data line
- [ ] store insert/replay 예외 fallback

### webhook outbox

- [ ] dispatcher start 동시 호출
- [ ] stale claim candidate 제외
- [ ] 동시 claim 중복 방지
- [ ] route별 endpoint dispatch
- [ ] endpoint 누락 시 entry reject

### shutdown

- [ ] close 반환 후 dispatch job 종료
- [ ] shutdown 직전 checkpoint flush
- [ ] reply admission shutdown 중 worker closed event

### multipart/body

- [ ] unknown multipart part 거부
- [ ] metadata 초과 거부
- [ ] image 초과 거부
- [ ] body size overflow 방어
- [ ] negative content length 거부

### auth contract

- [ ] Kotlin/Rust canonical target fixture 일치
- [ ] 한글 query encoding
- [ ] 공백 query encoding
- [ ] `&`, `=`, `%` query encoding
- [ ] nonce replay 동시 요청
