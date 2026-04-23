# STEP 18. Rust/Kotlin canonical query 계약 일치화

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/RequestBodyReader.kt`, `tools/iris-common/src/auth.rs`, `docs/auth-contract.md`
- 검증 근거: `app/src/test/java/party/qwer/iris/CanonicalRequestTargetTest.kt`, `app/src/test/java/party/qwer/iris/RequestAuthenticatorContractTest.kt`, `tools/iris-common/tests/auth_contract_test.rs`, `cargo test --manifest-path tools/iris-common/Cargo.toml canonical_target_percent_encodes_query_components`, `cargo test --manifest-path tools/iris-common/Cargo.toml shared_auth_vectors_match_rust_signer`

## 1. 목적

Kotlin 서버는 query key/value를 URL encode하지만 Rust client는 정렬만 하고 encoding을 하지 않습니다. 특수문자 query가 추가되면 서명 불일치가 납니다.

## 2. 대상 파일

- `tools/iris-common/src/auth.rs`
- `app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt`
- `docs/auth-contract.md`

## 3. 확인된 위치

- tools/iris-common/src/auth.rs:11 — canonical_target()

## 4. 현재 문제

서명 canonical target은 서버와 client가 완전히 같아야 합니다. Rust client는 현재 key/value를 그대로 붙이므로 한글, 공백, `&`, `=`, `%`가 들어가면 서버와 다른 문자열에 서명합니다.

## 5. 수정 방향

Kotlin과 Rust가 같은 golden fixture를 읽어 같은 결과를 내도록 계약 테스트를 만듭니다. Rust 쪽에는 percent encoding을 적용합니다.

## 6. 구현 절차

- [ ] canonical query 규칙 문서화
- [ ] Rust canonical_target key/value encode
- [ ] Kotlin/Rust 공통 fixture 추가
- [ ] 특수문자 query test 추가
- [ ] 빈 value 정책 확인

## 7. 코드 레벨 변경안

```rust
fn encode_query_component(value: &str) -> String {
    utf8_percent_encode(value, NON_ALPHANUMERIC).to_string()
}
```

## 8. 테스트 계획

- [ ] Rust canonical target fixture test
- [ ] Kotlin canonical target fixture test
- [ ] 서명 end-to-end 테스트
- [ ] query 순서 변경 테스트

## 9. 문서화 반영

`docs/auth-contract.md`를 추가해 서명 문자열 구성 방식을 문서화합니다.

## 10. 완료 기준

- 동일 입력에 대해 Kotlin과 Rust가 같은 canonical target을 만든다.
- 특수문자 query에서도 서명 검증이 성공한다.

## 11. 주의할 리스크

- percent encoding 방식이 Kotlin과 완전히 같은지 fixture로 확인해야 합니다.
