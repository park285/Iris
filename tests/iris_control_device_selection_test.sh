#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

cat >"$tmpdir/adb" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "${1:-}" == "devices" ]]; then
  cat <<'OUT'
List of devices attached
127.0.0.1:5555	device
emulator-5554	device
OUT
  exit 0
fi

if [[ "${1:-}" == "-s" && "${2:-}" == "127.0.0.1:5555" && "${3:-}" == "shell" && "${4:-}" == "ps" && "${5:-}" == "-f" ]]; then
  cat <<'OUT'
root       1234     1  0 00:00 ?        00:00:00 app_process / party.qwer.iris.Main
OUT
  exit 0
fi

echo "unexpected adb invocation: $*" >&2
exit 1
EOF
chmod +x "$tmpdir/adb"

set +e
output="$(printf '1\n' | PATH="$tmpdir:$PATH" "$repo_root/iris_control" status 2>&1)"
status=$?
set -e

if [[ $status -ne 0 ]]; then
  echo "iris_control status failed unexpectedly"
  echo "$output"
  exit 1
fi

if ! grep -Fq "Iris is running. PID: 1234" <<<"$output"; then
  echo "expected PID output not found"
  echo "$output"
  exit 1
fi

echo "iris_control device selection test passed"
