# STEP 19. APK checksum 검증 SHA-256 전환

우선순위: **P2**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `iris_control`, `README.md`
- 검증 근거: `tests/iris_control_install_checksum_test.sh`, `bash tests/iris_control_env_loading_test.sh`, `bash tests/iris_control_shell_quote_test.sh`, `bash tests/iris_control_stop_race_test.sh`

## 1. 목적

`iris_control`이 MD5 checksum을 사용합니다. 우발적 손상 검사용으로도 SHA-256으로 올리는 편이 맞습니다.

## 2. 대상 파일

- `iris_control`
- `README.md`
- `릴리스 산출물 생성 스크립트`

## 3. 확인된 위치

- iris_control:274 — verify_md5_if_available()

## 4. 현재 문제

MD5는 충돌 공격에 취약하고 릴리스 산출물 검증 용도로 부적절합니다. APK와 `.MD5`를 같은 위치에서 받으면 공급망 공격을 완전히 막지는 못하지만, 최소한 SHA-256으로 올려야 합니다.

## 5. 수정 방향

`.MD5` 대신 `.SHA256` 파일을 사용하고 checksum 형식이 64자리 hex인지 검증합니다. 보안 요구가 높으면 APK signing certificate pinning이나 서명 파일 검증을 추가합니다.

## 6. 구현 절차

- [ ] IRIS_SHA256_URL 추가
- [ ] verify_sha256_if_available 구현
- [ ] MD5 함수 제거 또는 deprecated
- [ ] 릴리스 산출물에 .SHA256 추가

## 7. 코드 레벨 변경안

```bash
if ! [[ "$downloaded_sha256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "Invalid SHA256 checksum format."
  return 1
fi
```

## 8. 테스트 계획

- [ ] 정상 SHA-256 검증 테스트
- [ ] checksum mismatch 실패 테스트
- [ ] checksum 형식 오류 테스트
- [ ] checksum 파일 없음 정책 테스트

## 9. 문서화 반영

README에서 MD5 표현을 제거하고 SHA-256 checksum 검증 방식을 설명합니다.

## 10. 완료 기준

- APK 다운로드 검증이 SHA-256 기반으로 동작한다.
- 릴리스 산출물에 SHA-256 checksum이 포함된다.

## 11. 주의할 리스크

- SHA-256도 같은 채널에서 받으면 공급망 공격을 완전히 막지는 못합니다.
