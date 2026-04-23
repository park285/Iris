#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOOTSTRAP_SCRIPT="${BOOTSTRAP_SCRIPT:-$SCRIPT_DIR/zygisk_next_bootstrap.sh}"
IRIS_ADB_DEVICE="${IRIS_ADB_DEVICE:-127.0.0.1:5555}"
SLEEP_SECONDS="${SLEEP_SECONDS:-20}"
BRIDGE_SOCKET_NAME="${BRIDGE_SOCKET_NAME:-@iris-image-bridge}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

adb_cmd() {
  adb -s "$IRIS_ADB_DEVICE" "$@"
}

bridge_socket_marker() {
  printf '%s' "$BRIDGE_SOCKET_NAME"
}

is_booted() {
  adb connect "$IRIS_ADB_DEVICE" >/dev/null 2>&1 || true
  adb_cmd wait-for-device >/dev/null 2>&1 || return 1
  [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | head -n 1)" == "1" ]]
}

bridge_runtime_ready() {
  local processes
  processes="$(adb_cmd shell "ps -A | grep -E 'lspd|com.kakao.talk' || true" 2>/dev/null | tr -d '\r' || true)"

  grep -Fq "lspd" <<<"$processes" &&
    grep -Fq "com.kakao.talk" <<<"$processes" &&
    adb_cmd shell "grep -F '$(bridge_socket_marker)' /proc/net/unix >/dev/null 2>&1"
}

has_zygisk_runtime() {
  local status_out
  status_out="$(adb_cmd shell "su 0 sh -c '/data/adb/modules/zygisksu/bin/zygiskd64 status 2>&1'" 2>/dev/null | tr -d '\r' || true)"
  if grep -Fq "root_status:✅Magisk" <<<"$status_out" && grep -Fq "modules64:1,zygisk_lsposed" <<<"$status_out"; then
    return 0
  fi

  bridge_runtime_ready
}

main() {
  while true; do
    if is_booted; then
      if has_zygisk_runtime; then
        log "zygisk runtime healthy"
      else
        log "zygisk runtime missing or incomplete; running bootstrap"
        IRIS_ADB_DEVICE="$IRIS_ADB_DEVICE" IRIS_ZN_ZYGOTE_RESTART=1 IRIS_ZN_ZYGOTE_RESTART_DELAY_SECONDS=10 IRIS_ZN_READY_TIMEOUT_SECONDS=40 "$BOOTSTRAP_SCRIPT" || true
      fi
    else
      log "device not ready yet"
    fi

    sleep "$SLEEP_SECONDS"
  done
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
