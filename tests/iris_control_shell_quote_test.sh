#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

state_file="$tmpdir/iris_started"
command_log="$tmpdir/adb_command.log"
env_log="$tmpdir/app_process_env.log"
injected_file="$tmpdir/injected"

: >"$tmpdir/empty.env"

cat >"$tmpdir/adb" <<EOF
#!/usr/bin/env bash
set -euo pipefail

state_file="$state_file"
command_log="$command_log"

if [[ "\${1:-}" == "-s" && "\${2:-}" == "127.0.0.1:5555" && "\${3:-}" == "shell" && "\${4:-}" == "ps" && "\${5:-}" == "-f" ]]; then
  if [[ -f "\$state_file" ]]; then
    cat <<'OUT'
root       1234     1  0 00:00 ?        00:00:00 app_process / party.qwer.iris.Main
OUT
  fi
  exit 0
fi

if [[ "\${1:-}" == "-s" && "\${2:-}" == "127.0.0.1:5555" && "\${3:-}" == "shell" ]]; then
  printf '%s\n' "\$*" >>"\$command_log"
  PATH="$(dirname "$0"):\$PATH" sh -c "\$4"
  exit 0
fi

echo "unexpected adb invocation: \$*" >&2
exit 1
EOF
chmod +x "$tmpdir/adb"

cat >"$tmpdir/su" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "root" || "${1:-}" == "0" ]]; then
  shift
fi

if [[ "${1:-}" != "sh" || "${2:-}" != "-c" ]]; then
  echo "unexpected su invocation: $*" >&2
  exit 1
fi

shift 2
sh -c "$1"
EOF
chmod +x "$tmpdir/su"

cat >"$tmpdir/app_process" <<EOF
#!/usr/bin/env bash
set -euo pipefail

state_file="$state_file"
env_log="$env_log"

touch "\$state_file"
{
  printf 'IRIS_CONFIG_PATH=%s\n' "\${IRIS_CONFIG_PATH:-}"
  printf 'IRIS_WEBHOOK_TOKEN=%s\n' "\${IRIS_WEBHOOK_TOKEN:-}"
  printf 'IRIS_BOT_TOKEN=%s\n' "\${IRIS_BOT_TOKEN:-}"
} >"\$env_log"
EOF
chmod +x "$tmpdir/app_process"

set +e
output="$(
  IRIS_ADB_DEVICE="127.0.0.1:5555" \
  IRIS_ENV_FILE="$tmpdir/empty.env" \
  IRIS_CONFIG_PATH=$'/data/local/tmp/iris config '\'' weird.json' \
  IRIS_WEBHOOK_TOKEN=$'abc'\''$(touch '"$injected_file"$')' \
  IRIS_BOT_TOKEN=$'bot'\''`touch '"$injected_file"'.bot`' \
  PATH="$tmpdir:$PATH" \
  "$repo_root/iris_control" start 2>&1
)"
status=$?
set -e

if [[ $status -ne 0 ]]; then
  echo "iris_control start failed unexpectedly"
  echo "$output"
  exit 1
fi

if [[ -e "$injected_file" || -e "$injected_file.bot" ]]; then
  echo "shell injection payload unexpectedly executed"
  cat "$command_log"
  exit 1
fi

if ! grep -Fq "IRIS_CONFIG_PATH=/data/local/tmp/iris config ' weird.json" "$env_log"; then
  echo "config path was not preserved through shell quoting"
  cat "$env_log"
  exit 1
fi

if ! grep -Fq "IRIS_WEBHOOK_TOKEN=abc'\$(touch $injected_file)" "$env_log"; then
  echo "webhook token was not preserved through shell quoting"
  cat "$env_log"
  exit 1
fi

if ! grep -Fq "IRIS_BOT_TOKEN=bot'\`touch $injected_file.bot\`" "$env_log"; then
  echo "bot token was not preserved through shell quoting"
  cat "$env_log"
  exit 1
fi

echo "iris_control shell quote test passed"
