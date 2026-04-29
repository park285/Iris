#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

state_file="$tmpdir/iris_started"
command_log="$tmpdir/adb_command.log"

cat >"$tmpdir/iris.env" <<'EOF'
IRIS_SHARED_TOKEN=shared-secret
IRIS_DEVICE=127.0.0.1:5555
IRIS_ALLOW_CLEARTEXT_HTTP=1
IRIS_BIND_HOST=0.0.0.0
IRIS_CHATROOM_REFRESH_ENABLED=1
IRIS_CHATROOM_REFRESH_INTERVAL_MS=60000
IRIS_CHATROOM_REFRESH_OPEN_DELAY_MS=5000
IRIS_NATIVE_CORE=on
IRIS_NATIVE_DECRYPT=shadow
IRIS_NATIVE_ROUTING=shadow
IRIS_NATIVE_PARSERS=shadow
IRIS_NATIVE_WEBHOOK_PAYLOAD=shadow
IRIS_NATIVE_STRICT=on
IRIS_NATIVE_STRICT_DECRYPT=off
IRIS_NATIVE_STRICT_ROUTING=on
IRIS_NATIVE_STRICT_PARSERS=on
IRIS_NATIVE_STRICT_WEBHOOK_PAYLOAD=off
IRIS_NATIVE_LIB_PATH=/custom/iris/lib/libiris_native_core.so
EOF

cat >"$tmpdir/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="$(dirname "$0")/iris_started"
command_log="$(dirname "$0")/adb_command.log"

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" && "${4:-}" == "ps" && "${5:-}" == "-f" ]]; then
  if [[ -f "$state_file" ]]; then
    cat <<'OUT'
root       1234     1  0 00:00 ?        00:00:00 app_process / party.qwer.iris.Main
OUT
  fi
  exit 0
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" ]]; then
  printf '%s\n' "$*" >>"$command_log"
  touch "$state_file"
  exit 0
fi

echo "unexpected adb invocation: $*" >&2
exit 1
EOF
chmod +x "$tmpdir/adb"

set +e
output="$(IRIS_ENV_FILE="$tmpdir/iris.env" PATH="$tmpdir:$PATH" "$repo_root/iris_control" start 2>&1)"
status=$?
set -e

if [[ $status -ne 0 ]]; then
  echo "iris_control start failed unexpectedly"
  echo "$output"
  exit 1
fi

if ! grep -Fq 'Loaded iris.env:' <<<"$output"; then
  echo "expected iris.env load output not found"
  echo "$output"
  exit 1
fi

if ! grep -Fq "IRIS_ALLOW_CLEARTEXT_HTTP='\"'\"'1'\"'\"'" "$command_log"; then
  echo "expected cleartext env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_BIND_HOST='\"'\"'0.0.0.0'\"'\"'" "$command_log"; then
  echo "expected bind host passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_CHATROOM_REFRESH_ENABLED='\"'\"'1'\"'\"'" "$command_log"; then
  echo "expected chatroom refresh enabled passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_CHATROOM_REFRESH_INTERVAL_MS='\"'\"'60000'\"'\"'" "$command_log"; then
  echo "expected chatroom refresh interval passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_CHATROOM_REFRESH_OPEN_DELAY_MS='\"'\"'5000'\"'\"'" "$command_log"; then
  echo "expected chatroom refresh open delay passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_CORE='\"'\"'on'\"'\"'" "$command_log"; then
  echo "expected native core env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_DECRYPT='\"'\"'shadow'\"'\"'" "$command_log"; then
  echo "expected native decrypt env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_ROUTING='\"'\"'shadow'\"'\"'" "$command_log"; then
  echo "expected native routing env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_PARSERS='\"'\"'shadow'\"'\"'" "$command_log"; then
  echo "expected native parsers env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_WEBHOOK_PAYLOAD='\"'\"'shadow'\"'\"'" "$command_log"; then
  echo "expected native webhook payload env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_STRICT='\"'\"'on'\"'\"'" "$command_log"; then
  echo "expected native strict env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_STRICT_DECRYPT='\"'\"'off'\"'\"'" "$command_log"; then
  echo "expected native strict decrypt env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_STRICT_ROUTING='\"'\"'on'\"'\"'" "$command_log"; then
  echo "expected native strict routing env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_STRICT_PARSERS='\"'\"'on'\"'\"'" "$command_log"; then
  echo "expected native strict parsers env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_STRICT_WEBHOOK_PAYLOAD='\"'\"'off'\"'\"'" "$command_log"; then
  echo "expected native strict webhook payload env passthrough not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_NATIVE_LIB_PATH='\"'\"'/custom/iris/lib/libiris_native_core.so'\"'\"'" "$command_log"; then
  echo "expected native lib path passthrough not found"
  cat "$command_log"
  exit 1
fi

if [[ "$(grep -o "IRIS_NATIVE_LIB_PATH=.*CLASSPATH=" "$command_log")" != *"IRIS_NATIVE_LIB_PATH="*"CLASSPATH="* ]]; then
  echo "expected native lib path env before CLASSPATH"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_WEBHOOK_TOKEN='\"'\"'shared-secret'\"'\"'" "$command_log"; then
  echo "expected webhook token fallback not found"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_BOT_TOKEN='\"'\"'shared-secret'\"'\"'" "$command_log"; then
  echo "expected bot token fallback not found"
  cat "$command_log"
  exit 1
fi

echo "iris_control env loading test passed"
