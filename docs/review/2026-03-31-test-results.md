# 2026-03-31 test results

이 문서는 최신 reviewer pack의 실행 결과 요약이다.
이 패키지에서는 과거 test-results 문서를 기준으로 보지 않는다.

## 최종 전체 검증

실행 커맨드:

```bash
./scripts/verify-all.sh
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
```

최종 결과:

- `BUILD SUCCESSFUL`
- `[verify-all] all checks passed`

## 추가 집중 검증

실행 커맨드:

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest \
  --tests "party.qwer.iris.RequestAuthenticatorTest" \
  --tests "party.qwer.iris.http.AuthSupportTest" \
  --tests "party.qwer.iris.snapshot.SnapshotCoordinatorTest" \
  --tests "party.qwer.iris.SnapshotObserverTest" \
  --tests "party.qwer.iris.ObserverHelperSnapshotTest" \
  --tests "party.qwer.iris.reply.ReplyAdmissionServiceTest" \
  --tests "party.qwer.iris.delivery.webhook.WebhookOutboxDispatcherTest" \
  --tests "party.qwer.iris.http.Base64ImageIngressServiceTest" \
  --tests "party.qwer.iris.MemberRepositoryTest" \
  --tests "party.qwer.iris.ThreadQueriesTest"
```

결과:

- 모두 통과

## app unit test

- suite 수: 100
- test 수: 635
- failures: 0
- errors: 0
- skipped: 0

근거 경로:

- [index.html](/home/kapu/gemini/Iris/app/build/reports/tests/testDebugUnitTest/index.html)
- [test-results](/home/kapu/gemini/Iris/app/build/test-results/testDebugUnitTest)

## bridge unit test

- suite 수: 16
- test 수: 66
- failures: 0
- errors: 0
- skipped: 0

근거 경로:

- [index.html](/home/kapu/gemini/Iris/bridge/build/reports/tests/testDebugUnitTest/index.html)
- [test-results](/home/kapu/gemini/Iris/bridge/build/test-results/testDebugUnitTest)

## imagebridge-protocol test

- suite 수: 2
- test 수: 8
- failures: 0
- errors: 0
- skipped: 0

근거 경로:

- [test-results](/home/kapu/gemini/Iris/imagebridge-protocol/build/test-results/test)

## Rust / shell / boundary 검증

`verify-all.sh` 안에서 다시 확인된 항목:

- `cargo fmt --manifest-path tools/Cargo.toml --all -- --check`: 통과
- `cargo clippy --manifest-path tools/Cargo.toml --workspace --all-targets -- -D warnings`: 통과
- `cargo test --manifest-path tools/Cargo.toml --workspace`: 통과
  - `iris-common`: 15 passed
  - `iris-ctl`: 38 passed
  - `iris-daemon`: 45 passed
- `cargo audit --file tools/Cargo.lock`: 통과
- `cargo deny --manifest-path tools/Cargo.toml check`: 통과
  - duplicate / license-not-encountered warning은 있었지만 exit code는 성공
- `env MIRIFLAGS='-Zmiri-disable-isolation' cargo +nightly miri test --manifest-path tools/Cargo.toml --workspace`: 통과
  - `iris-common`: 15 passed
  - `iris-ctl`: 36 passed, 2 ignored
  - `iris-daemon`: 45 passed
- `./scripts/check-bridge-boundaries.sh`: 통과
- `bash ./tests/iris_control_device_selection_test.sh`: 통과
- `bash ./tests/zygisk_next_bootstrap_test.sh`: 통과

## reviewer용 해석

이 reviewer pack 기준으로 신뢰해도 되는 최소 사실은 아래와 같다.

- `review-2.txt` 기준 후속 수정이 현재 코드와 테스트에 반영돼 있다
- app / bridge / imagebridge-protocol 테스트는 최신 작업트리에서 다시 실패하지 않았다
- Rust/tools, boundary script, shell integration까지 포함한 전체 품질 게이트가 다시 성공했다
- 별도 carry-forward 검증 항목은 없다
