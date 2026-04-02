# iris-ctl

Iris HTTP API용 Ratatui 기반 TUI 제어 클라이언트.
SSE 실시간 스트림과 주기적 폴링을 결합하여 모니터링한다.

## 빠른 시작

```bash
# 빌드
cargo build --release -p iris-ctl

# 실행 (~/.config/iris-ctl/config.toml 자동 로드)
iris-ctl
```

## 설정

**파일 경로**: `~/.config/iris-ctl/config.toml`

```toml
[server]
url = "http://<iris-host>:3000"
token = "your-hmac-token"
transport = "h2c"            # 기본값. "http1"로 HTTP/1.1 강제 가능

[ui]
poll_interval_secs = 5       # 기본값
```

**환경 변수 오버라이드** (설정 파일보다 우선):

| 변수 | 대상 |
|------|------|
| `IRIS_TOKEN` | `server.token` |
| `IRIS_TRANSPORT` | `server.transport` |

## 탭 구성

| 탭 | 설명 |
|----|------|
| Rooms | 방 목록. `Enter` → Members 이동, `s` → Stats 이동 |
| Members | 선택된 방의 멤버 목록. `Enter` → Stats에서 멤버 활동 조회 |
| Stats | 방 통계 + 멤버 활동 히트맵 |
| Messages | 최근 50건 메시지를 시간순으로 표시 |
| Events | SSE 실시간 이벤트 스트림 + 이력 로드 |

## 키 바인딩

| 키 | 동작 |
|----|------|
| `Tab` / `Shift+Tab` | 탭 순환 |
| `r` | 답장 모달 열기 |
| `q` / `Ctrl+C` | 종료 |

## 답장 모달

`r` 키로 열린다.

- **타입**: `text`, `image`, `markdown`, `image-multiple`
- **스레드 ID**: 선택 사항. 스레드 답장 시 지정
- `/reply` API 엔드포인트를 통해 전송

## SSE 스트림

`/events/stream`에 HMAC 서명 헤더를 포함하여 연결한다.
연결 끊김 시 3초 후 자동 재연결하며, 재연결마다 서명을 새로 생성한다.
