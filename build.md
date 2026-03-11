# sendmsg

## 현재 권장 검증 절차

### cmdline

```bash
./gradlew detekt ktlintCheck assembleDebug assembleRelease
```

산출물:

- `output/Iris-debug.apk`
- `output/Iris-release.apk`

## 실행

1. `output/Iris-debug.apk` 또는 `output/Iris-release.apk`를 장치로 복사합니다.
2. runtime config는 `/data/local/tmp/config.json` 또는 `IRIS_CONFIG_PATH`로 지정합니다.
3. 아래 명령으로 실행합니다.

```shell
IRIS_CONFIG_PATH=/data/local/tmp/config.json \
CLASSPATH=/data/local/tmp/Iris.apk \
app_process / party.qwer.iris.Main
```

## 운영 메모

- 현재 운영 환경은 headless Redroid 기준입니다.
- 보호 API(`/config`, `/reply`, `/query`)는 `botToken`이 없으면 `503 service unavailable`으로 거부됩니다.
- webhook transport 기본값은 h2c이며, 필요 시 `IRIS_WEBHOOK_TRANSPORT=http1`을 사용할 수 있습니다.
