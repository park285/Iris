#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
script_path="$repo_root/scripts/zygisk_next_bootstrap.sh"

if [[ ! -f "$script_path" ]]; then
  echo "missing script: $script_path"
  exit 1
fi

# shellcheck source=/dev/null
source "$script_path"

run_root_shell() {
  case "$1" in
    "/data/adb/modules/zygisksu/bin/zygiskd64 dump-zn -sa")
      printf 'failed to connect to server\nerr:❌ Last injection failed!\n'
      ;;
    "ps -A | grep -E 'magiskd|zn-nsdaemon-zygote64|zygote64' || true")
      printf ''
      ;;
    *)
      return 0
      ;;
  esac
}

run_adb() {
  case "$*" in
    "shell logcat -d -b all -v threadtime | grep -E 'inject success for|loaded 64bit zygisk module zygisk_lsposed|loaded 1 64bit zygisk module|daemon launched' | tail -n 40")
      printf ''
      ;;
    "shell ps -A | grep -E 'lspd|com.kakao.talk' || true")
      printf 'system 4163 1 lspd\nu0_a78 6100 1 com.kakao.talk\n'
      ;;
    "shell grep -F '@iris-image-bridge' /proc/net/unix >/dev/null 2>&1")
      return 0
      ;;
    *)
      return 0
      ;;
  esac
}

IRIS_ZN_READY_TIMEOUT_SECONDS=1
SECONDS=0
dump="$(wait_for_zygisk_ready)"

if ! grep -Fq "Fallback: bridge runtime ready" <<<"$dump"; then
  echo "wait_for_zygisk_ready should report bridge-ready fallback"
  printf '%s\n' "$dump"
  exit 1
fi

echo "zygisk next bootstrap bridge-ready test passed"
