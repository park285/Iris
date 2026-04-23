# STEP 05. shell quoting 및 명령 주입 방어

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `iris_control`, `scripts/zygisk_next_bootstrap.sh`, `scripts/zygisk_next_watchdog.sh`, `tools/iris-daemon/src/config_sync.rs`
- 검증 근거: `bash tests/iris_control_shell_quote_test.sh`, `bash tests/zygisk_next_bootstrap_test.sh`, `bash tests/zygisk_next_watchdog_test.sh` 통과
- 메모: shell quoting hardening이 주요 command 조립 경계에 반영되었고, zygisk bootstrap 디렉터리 권한도 `0700`으로 낮아졌습니다.

## 1. 목적

`iris_control`, zygisk 스크립트, Rust daemon에서 경로와 token이 shell command에 안전하게 quote되지 않습니다. 특수문자 포함 값이 명령을 깨거나 주입으로 이어질 수 있습니다.

## 2. 대상 파일

- `iris_control`
- `scripts/zygisk_next_bootstrap.sh`
- `scripts/zygisk_next_watchdog.sh`
- `tools/iris-daemon/src/config_sync.rs`
- `tools/iris-daemon/src/launch_spec.rs`

## 3. 확인된 위치

- iris_control:133 — cat '$IRIS_CONFIG_PATH'
- iris_control:186 — build_remote_runtime_command()
- iris_control:204 — su root sh -c '$remote_command'
- zygisk_next_bootstrap.sh:72 — run_root_shell()
- zygisk_next_bootstrap.sh:140 — chmod 0777
- zygisk_next_watchdog.sh:5 — hardcoded bootstrap path
- config_sync.rs:93 — cat {}
- config_sync.rs:164 — build_device_sha256_command()

## 4. 현재 문제

스크립트는 환경변수와 경로를 shell command 문자열 안에 직접 삽입합니다. token, 경로, URL에 작은따옴표, 공백, `$()`, 백틱, 줄바꿈이 들어가면 명령이 깨지거나 의도치 않은 명령이 실행될 수 있습니다.

특히 token은 신뢰할 수 있는 값이라고 생각하기 쉽지만, 운영 환경에서는 secret store, CI 변수, 수동 입력 등을 통해 들어옵니다. shell 경계에서는 모든 값을 의심해야 합니다.

## 5. 수정 방향

모든 shell command 조립 지점에 안전한 quoting 함수를 적용합니다. 더 좋은 방향은 token을 명령줄에 직접 싣지 않고 권한 제한된 env 파일로 전달하는 것입니다.

Bash 쪽은 `sh_quote()`를 공통으로 두고, Rust 쪽은 이미 있는 `shell_quote()`를 모든 inner command의 변수에도 적용합니다. 바깥 shell만 quote하고 inner script에 raw path를 넣는 실수도 막아야 합니다.

## 6. 구현 절차

- [ ] `iris_control`에 `sh_quote()`와 `env_assign()`를 추가합니다.
- [ ] `build_remote_runtime_command()`에서 모든 env 값을 quote합니다.
- [ ] `su root sh -c '$remote_command'` 형태를 제거하고 `su root sh -c $(sh_quote "$remote_command")`로 바꿉니다.
- [ ] `get_iris_http_port()`의 config path도 quote합니다.
- [ ] `zygisk_next_bootstrap.sh`의 `run_root_shell()`에도 `sh_quote()`를 적용합니다.
- [ ] `zygisk_next_watchdog.sh`의 bootstrap 기본 경로를 script-relative 경로로 바꿉니다.
- [ ] `chmod 0777`이 정말 필요한지 검토하고 가능하면 `0700`으로 낮춥니다.
- [ ] Rust `config_sync.rs`에서 `cat`, `sha256sum`, `chmod` 모두 path quote를 적용합니다.

## 7. 코드 레벨 변경안

```bash
sh_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

env_assign() {
  local name="$1"
  local value="$2"
  printf '%s=%s ' "$name" "$(sh_quote "$value")"
}
```

```bash
build_remote_runtime_command() {
  local redirect_suffix="${1:-}"

  {
    env_assign IRIS_CONFIG_PATH "$IRIS_CONFIG_PATH"
    env_assign IRIS_WEBHOOK_TOKEN "$IRIS_WEBHOOK_TOKEN"
    env_assign IRIS_BOT_TOKEN "$IRIS_BOT_TOKEN"
    env_assign IRIS_LOG_LEVEL "$IRIS_LOG_LEVEL"
    env_assign CLASSPATH "$IRIS_APK_PATH"
    printf 'exec app_process / party.qwer.iris.Main%s' "$redirect_suffix"
  }
}

remote_command="$(build_remote_runtime_command ' > /dev/null 2>&1 &')"
"${ADB_CMD[@]}" shell "su root sh -c $(sh_quote "$remote_command")"
```

```rust
let config_path = shell_quote(&cfg.init.config_dest);
let device_config = match adb.shell(&format!("cat {config_path}")).await {
    Ok(content) => content,
    Err(error) => {
        tracing::warn!(error = %error, "디바이스 config 읽기 실패 — config drift check 건너뜀");
        return Ok(false);
    }
};
```

## 8. 테스트 계획

- [ ] `IRIS_CONFIG_PATH="/data/local/tmp/iris config ' weird.json"`로 command builder 테스트
- [ ] `IRIS_WEBHOOK_TOKEN="abc' $(touch /tmp/pwned)"`로 명령 주입이 실행되지 않는지 테스트
- [ ] `IRIS_BOT_TOKEN`에 백틱과 공백을 넣어도 실행 명령이 깨지지 않는지 테스트
- [ ] Rust `config_sync` 단위 테스트에서 path에 공백/작은따옴표 포함
- [ ] 스크립트 로그에 token 원문이 출력되지 않는지 확인

## 9. 문서화 반영

운영 문서에 secret 전달 원칙을 적습니다. 명령줄 인자로 secret을 싣지 않는 것, env 파일 권한을 0600으로 두는 것, 로그에 secret 원문을 남기지 않는 것을 명시합니다.

## 10. 완료 기준

- 특수문자 포함 경로와 token으로도 명령이 깨지지 않는다.
- 추가 shell 명령이 실행되지 않는다.
- token이 로그나 process command line에 불필요하게 노출되지 않는다.

## 11. 주의할 리스크

- quote 함수를 부분적으로만 적용하면 안전하다는 착각이 생깁니다. 모든 shell 경계를 inventory로 관리해야 합니다.
- env 파일 방식으로 바꿀 경우 파일 삭제와 권한 관리까지 함께 구현해야 합니다.
