#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cat >"$tmpdir/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

state_file="${TMPDIR_STATE_FILE:?}"
count=0
if [[ -f "$state_file" ]]; then
  count="$(cat "$state_file")"
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" && "${4:-}" == "ps" && "${5:-}" == "-f" ]]; then
  count=$((count + 1))
  printf '%s' "$count" >"$state_file"
  if (( count <= 6 )); then
    cat <<'OUT'
root       1234     1  0 00:00 ?        00:00:00 app_process / party.qwer.iris.Main
OUT
  fi
  exit 0
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" && "${4:-}" == "su" ]]; then
  if [[ "$*" == *"kill -s SIGKILL 1234"* ]]; then
    echo "/system/bin/sh: kill: 1234: No such process" >&2
    exit 1
  fi
  exit 0
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" ]]; then
  exit 0
fi

echo "unexpected adb invocation: $*" >&2
exit 1
EOF
chmod +x "$tmpdir/adb"

cat >"$tmpdir/sleep" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "$tmpdir/sleep"

state_file="$tmpdir/ps-count"

set +e
output="$(
  TMPDIR_STATE_FILE="$state_file" \
  PATH="$tmpdir:$PATH" \
  IRIS_ADB_DEVICE="127.0.0.1:5555" \
  "$repo_root/iris_control" stop 2>&1
)"
status=$?
set -e

if [[ $status -ne 0 ]]; then
  echo "iris_control stop failed unexpectedly"
  echo "$output"
  exit 1
fi

if ! grep -Fq "Iris service stopped after SIGKILL fallback." <<<"$output"; then
  echo "expected SIGKILL fallback success output not found"
  echo "$output"
  exit 1
fi

echo "iris_control stop race test passed"
