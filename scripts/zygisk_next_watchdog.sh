#!/usr/bin/env bash

set -euo pipefail

BOOTSTRAP_SCRIPT="${BOOTSTRAP_SCRIPT:-/root/work/Iris/zygisk_next_bootstrap.sh}"
IRIS_ADB_DEVICE="${IRIS_ADB_DEVICE:-127.0.0.1:5555}"
SLEEP_SECONDS="${SLEEP_SECONDS:-20}"

log() {
  printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"
}

adb_cmd() {
  adb -s "$IRIS_ADB_DEVICE" "$@"
}

is_booted() {
  adb connect "$IRIS_ADB_DEVICE" >/dev/null 2>&1 || true
  adb_cmd wait-for-device >/dev/null 2>&1 || return 1
  [[ "$(adb_cmd shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | head -n 1)" == "1" ]]
}

has_zygisk_runtime() {
  local status_out
  status_out="$(adb_cmd shell "su 0 sh -c '/data/adb/modules/zygisksu/bin/zygiskd64 status 2>&1'" 2>/dev/null | tr -d '\r' || true)"
  grep -Fq "root_status:✅Magisk" <<<"$status_out" && grep -Fq "modules64:1,zygisk_lsposed" <<<"$status_out"
}

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
