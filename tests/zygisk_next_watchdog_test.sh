#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
script_path="$repo_root/scripts/zygisk_next_watchdog.sh"

if [[ ! -f "$script_path" ]]; then
  echo "missing script: $script_path"
  exit 1
fi

# shellcheck source=/dev/null
source "$script_path"

expected_bootstrap="$repo_root/scripts/zygisk_next_bootstrap.sh"
if [[ "$BOOTSTRAP_SCRIPT" != "$expected_bootstrap" ]]; then
  echo "unexpected BOOTSTRAP_SCRIPT default: $BOOTSTRAP_SCRIPT"
  exit 1
fi

adb_cmd() {
  case "$*" in
    "shell su 0 sh -c '/data/adb/modules/zygisksu/bin/zygiskd64 status 2>&1'")
      printf 'failed to connect to server\nerr:❌ Could not connect to service!\n'
      ;;
    "shell ps -A | grep -E 'lspd|com.kakao.talk' || true")
      printf 'system 4163 1 lspd\nu0_a78 6100 1 com.kakao.talk\n'
      ;;
    "shell grep -F '@iris-image-bridge' /proc/net/unix >/dev/null 2>&1")
      return 0
      ;;
    *)
      return 1
      ;;
  esac
}

if ! has_zygisk_runtime; then
  echo "has_zygisk_runtime should accept bridge-ready fallback"
  exit 1
fi

echo "zygisk next watchdog test passed"
