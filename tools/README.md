# Iris Tools

Iris 봇 프레임워크의 호스트 측 Rust 도구 워크스페이스.

## 크레이트 구성

| 크레이트 | 유형 | 설명 |
|----------|------|------|
| [iris-common](iris-common/) | 라이브러리 | 공유 인증, API 클라이언트, 전송 계층, 데이터 모델 |
| [iris-ctl](iris-ctl/) | 바이너리 | Iris HTTP API용 TUI 제어 클라이언트 |
| [iris-daemon](iris-daemon/) | 바이너리 | ADB 연동 호스트 측 런타임 감시자 |

## 의존 관계

```
iris-common  <─── iris-ctl
     ^
     └──────────── iris-daemon
```

## 요구 사항

- Rust 1.85.0 이상 (edition 2024)
- `unsafe` 코드 전역 금지 (`unsafe_code = "forbid"`)

## 빌드

```bash
# 전체 빌드
cargo build --release

# clippy 검사 (프로젝트 전체 경고 deny 적용)
cargo clippy --workspace

# 개별 바이너리 빌드
cargo build --release -p iris-ctl
cargo build --release -p iris-daemon
```

## 설정

두 바이너리 모두 `iris-common`에 정의된 HMAC 인증 체계를 공유한다.
바이너리별 설정은 각 크레이트의 README를 참고할 것.
