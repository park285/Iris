# iris-daemon

호스트 측 런타임 감시자(supervisor).
ADB를 통해 Redroid 인스턴스를 제어하고, 상태 머신 기반으로 Iris 프로세스 생명주기를 관리한다.

## 빠른 시작

```bash
# 빌드
cargo build --release -p iris-daemon

# 초기화 (ADB 연결 → 부팅 대기 → config push → APK sync → Iris 시작)
iris-daemon init

# 감시 루프 시작
iris-daemon watch

# 헬스 프로브 1회 실행
iris-daemon status
```

## 서브커맨드

| 커맨드 | 설명 |
|--------|------|
| `init` | ADB 연결 → 부팅 완료 대기 → phantom killer 비활성화 → config 렌더/push → host SSOT APK sync → KakaoTalk 확인 → Iris 시작 |
| `watch` | 상태 머신 감시 루프 실행. systemd watchdog 연동 |
| `status` | 헬스 프로브를 1회 실행하고 결과를 stdout에 출력 |

**공통 플래그**: `iris-daemon [-c <config-path>] <subcommand>`

## 설정

**파일 경로**: `/etc/iris-daemon/config.toml` (기본값), `-c` 플래그로 오버라이드 가능

```toml
[iris]
health_url = "http://localhost:3000"
shared_token = ""

[adb]
device = "192.168.219.201:5555"

[watch]
check_interval_secs = 30        # 감시 주기
health_fail_threshold = 2       # liveness 연속 실패 임계값
readiness_fail_threshold = 4    # readiness 연속 실패 임계값
curl_timeout_secs = 3           # 프로브 타임아웃
config_check_every = 10         # N 사이클마다 config drift 확인

[alert]
enabled = false
webhook_url = ""                # 상태 전이 시 webhook POST 대상

[rollback]
enabled = false
max_consecutive_failures = 5    # 이 횟수 초과 시 롤백 시도
backup_dir = "/root/work/Iris/.backup-latest"

[init]
phantom_killer_disable = true
boot_timeout_secs = 120
config_template = "/root/work/arm-iris-runtime/configs/iris/config.json"
config_dest = "/data/iris/config.json"
apk_src = "/root/work/Iris/Iris.apk"
apk_dest = "/data/local/tmp/Iris.apk"
```

**환경 변수 오버라이드** (설정 파일보다 우선):

| 변수 | 대상 |
|------|------|
| `IRIS_HEALTH_URL` | `iris.health_url` |
| `IRIS_SHARED_TOKEN` | `iris.shared_token` |
| `IRIS_DEVICE` | `adb.device` |
| `IRIS_APK_SRC` | `init.apk_src` |

`config_template` 내부의 `${VAR}` 플레이스홀더는 현재 환경 변수(`IRIS_*`, `WEBHOOK_*` 등)로 치환된다.

## 상태 머신

```
Starting → Healthy ↔ Degraded → Recovering → RollbackNeeded
```

| 상태 | 전이 조건 |
|------|-----------|
| **Healthy** | 프로브 성공 |
| **Degraded** | liveness 또는 readiness가 임계값 이상 연속 실패 |
| **Recovering** | Degraded 상태에서 추가 실패 → 프로세스 재시작 실행 |
| **RollbackNeeded** | recovery 횟수가 `max_consecutive_failures` 초과 |

## 헬스 프로브

`watch` 루프는 매 사이클마다 3개 프로브를 병렬 실행한다:

| 프로브 | 엔드포인트 | 역할 |
|--------|-----------|------|
| Liveness | `/health` | 프로세스 생존 확인 |
| Readiness | `/ready` | 서비스 준비 상태 확인 |
| Bridge | `/diagnostics/bridge` | KakaoTalk 브릿지 상태 확인 |

## Config Drift 감지

`config_check_every` 사이클마다 디바이스의 config JSON과 APK drift를 확인한다.
차이가 발견되면 config를 재push하거나 APK를 재sync한 뒤 Iris를 재시작한다.

## 프로세스 제어

- `iris_control` 바이너리를 통해 start/stop/restart 수행
- 종료 시 SIGTERM → 5초 대기 → SIGKILL 폴백
- `kakaotalk_alive` 확인 후 Iris 시작

## systemd 연동

- `sd-notify`로 `READY=1` 및 `WATCHDOG=1` 통지
- `tracing-journald`로 journald 직접 출력

## 알림

`[alert]` 활성화 시 상태 전이마다 webhook으로 JSON POST:

```json
{
  "event": "state_transition",
  "from_state": "Healthy",
  "to_state": "Degraded",
  "timestamp": "2026-04-02T12:00:00Z",
  "details": "..."
}
```
