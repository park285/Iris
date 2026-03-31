# 2026-03-31 reviewer bundle manifest

이 문서는 최신 reviewer pack의 포함/제외 범위를 정리한다.
의도는 단순하다. reviewer가 **현재 코드와 최신 문서만** 보게 하는 것이다.

배포용 최신 번들 파일:

- `output/review-packages/iris-reviewer-bundle-20260331-aligned.tar.gz`

## 포함

- `AGENTS.md`
- `README.md`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle.properties`
- `gradle/`
- `gradlew`
- `app/`
- `bridge/`
- `imagebridge-protocol/`
- `scripts/`
- `tests/`
- `tools/`
- `docs/agent-reference.md`
- `docs/review/2026-03-31-reviewer-guide.md`
- `docs/review/2026-03-31-reviewer-change-rationale.md`
- `docs/review/2026-03-31-final-closure.md`
- `docs/review/2026-03-31-test-results.md`
- `docs/review/2026-03-31-reviewer-bundle-index.md`
- `docs/review/2026-03-31-reviewer-bundle-manifest.md`
- `app/build/reports/tests/testDebugUnitTest/`
- `app/build/test-results/testDebugUnitTest/`
- `bridge/build/reports/tests/testDebugUnitTest/`
- `bridge/build/test-results/testDebugUnitTest/`
- `imagebridge-protocol/build/test-results/test/`

## 제외

- `docs/review/2026-03-30-*`
- `docs/review/2026-03-31-review-package.md`
- `docs/review/2026-03-31-code-focus-review-package.md`
- `docs/review/2026-03-31-code-focus-bundle-manifest.md`
- `docs/review/2026-03-31-test-results-bundle-manifest.md`
- `docs/runbooks/`
- 기존 `*.tar.gz`
- `review-delivery-*`
- 루트 `build/`
- 위에 명시한 test report / test-results 외의 `app/build/*`
- 위에 명시한 test report / test-results 외의 `bridge/build/*`
- 위에 명시한 test report / test-results 외의 `imagebridge-protocol/build/*`
- `tools/target/`
- `tools/iris-ctl/target/`
- `tools/iris-daemon/target/`
- `tools/*.tar.gz`
- `.git/`
- `.gradle/`
- `.idea/`
- `.vscode/`
- 기타 로컬 산출물

## reviewer용 해석

이 manifest의 목적은 “과거 설명 문서와 최신 설명 문서가 섞이지 않게 한다”는 데 있다.
이번 aligned bundle은 코드와 문서뿐 아니라 현재 스냅샷의 상세 테스트 리포트까지 함께 담는다.
즉 reviewer는 이 문서가 포함으로 적은 최신 문서와 테스트 증빙만 보면 된다.
