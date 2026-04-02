# Verification Summary

## Revision
- commit: 18a5f9bffc7450096f76e22aa0ad06a1a6e63587
- branch: main
- dirty files: 110 (uncommitted closeout changes)
- executed at: 2026-04-02T02:15:00+09:00

## Gradle — :app:testDebugUnitTest

| 항목 | 값 |
|------|-----|
| 상태 | **PASS** |
| 테스트 수 | 734 |
| 실패 | 0 |
| 에러 | 0 |
| 스킵 | 0 |
| 테스트 스위트 수 | 107 |
| 실행 시간 | ~1m |

JUnit XML 결과: `artifacts/test-results/junit/` (107 파일)

## Rust — cargo test (전체 워크스페이스)

| crate | 테스트 수 | 결과 |
|-------|----------|------|
| iris-common (unit) | 16 | **PASS** |
| iris-common (contract) | 1 | **PASS** |
| iris-ctl | 55 | **PASS** |
| iris-daemon | 48 | **PASS** |
| **합계** | **120** | **PASS** |

Cargo 출력: `artifacts/test-results/cargo-test.txt`

## Auth Contract Vectors

| 벡터 | Kotlin | Rust | 상태 |
|------|--------|------|------|
| get-config-empty-body | PASS | PASS | 기존 |
| post-reply-json-body | PASS | PASS | 기존 |
| get-stats-query-body-hash | PASS | PASS | 기존 |
| post-reply-unicode-body | PASS | PASS | **신규** |
| get-percent-encoded-path | PASS | PASS | **신규** |
| get-repeated-query-param | PASS | PASS | **신규** |
| post-lowercase-method-normalization | PASS | PASS | **신규** |

총 7/7 벡터 양측 통과.
