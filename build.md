# Iris

## 현재 권장 검증 절차

### cmdline

```bash
./gradlew detekt ktlintCheck assembleDebug assembleRelease
```

산출물:

- `output/Iris-debug.apk`
- `output/Iris-release.apk`

## 실행

1. `output/Iris-debug.apk` 또는 `output/Iris-release.apk`를 장치의 `/data/local/tmp/Iris.apk`로 복사합니다.
   ```bash
   adb push output/Iris-debug.apk /data/local/tmp/Iris.apk
   ```
2. runtime config는 `/data/local/tmp/config.json` 또는 `IRIS_CONFIG_PATH`로 지정합니다.
3. 아래 명령으로 실행합니다.

```shell
IRIS_CONFIG_PATH=/data/local/tmp/config.json \
CLASSPATH=/data/local/tmp/Iris.apk \
app_process / party.qwer.iris.Main
```

## 운영 메모

- 현재 운영 환경은 headless Redroid 기준입니다.
- host-side control script는 `iris_control` 하나만 유지하며 Linux 환경을 기준으로 지원합니다.
- `IRIS_LOG_LEVEL=NONE`으로 완전 무로그 실행이 가능합니다.
- 보호 API(`/config`, `/reply`, `/query`)는 `botToken`이 없으면 `503 service unavailable`으로 거부됩니다.
- `/reply`는 실제 전송 성공이 아니라 queue admission 성공 시 `202 Accepted`를 반환합니다.
- `/query`는 `SELECT`/`WITH`/`PRAGMA`만 허용하며 응답에 `rowCount`를 포함합니다.
- webhook transport 기본값은 h2c이며, 필요 시 `IRIS_WEBHOOK_TRANSPORT=http1`을 사용할 수 있습니다.
