#!/usr/bin/env bash

set -euo pipefail

IRIS_ADB_DEVICE="${IRIS_ADB_DEVICE:-${ANDROID_SERIAL:-}}"
IRIS_ADB_CONNECT="${IRIS_ADB_CONNECT:-}"
IRIS_BOOT_TIMEOUT_SECONDS="${IRIS_BOOT_TIMEOUT_SECONDS:-90}"
IRIS_USE_ADB_ROOT="${IRIS_USE_ADB_ROOT:-0}"
IRIS_ZN_ZYGOTE_RESTART="${IRIS_ZN_ZYGOTE_RESTART:-1}"
IRIS_ZN_ZYGOTE_RESTART_DELAY_SECONDS="${IRIS_ZN_ZYGOTE_RESTART_DELAY_SECONDS:-8}"
IRIS_ZN_READY_TIMEOUT_SECONDS="${IRIS_ZN_READY_TIMEOUT_SECONDS:-30}"
IRIS_BRIDGE_SOCKET_NAME="${IRIS_BRIDGE_SOCKET_NAME:-@iris-image-bridge}"

declare -a ADB_CMD=()

check_linux_host() {
  if [[ "$(uname -s)" != "Linux" ]]; then
    echo "zygisk_next_bootstrap.sh is supported on Linux hosts only."
    return 1
  fi
}

check_adb_installed() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "adb is not installed. Please install adb and add it to your PATH."
    return 1
  fi
}

connect_adb_if_requested() {
  if [[ -n "$IRIS_ADB_CONNECT" ]]; then
    adb connect "$IRIS_ADB_CONNECT" >/dev/null
  fi
}

select_adb_device() {
  if (( ${#ADB_CMD[@]} > 0 )); then
    return 0
  fi

  connect_adb_if_requested

  if [[ -n "$IRIS_ADB_DEVICE" ]]; then
    ADB_CMD=(adb -s "$IRIS_ADB_DEVICE")
    echo "Using device from IRIS_ADB_DEVICE/ANDROID_SERIAL: $IRIS_ADB_DEVICE"
    return 0
  fi

  mapfile -t devices < <(adb devices | awk '$2 == "device" {print $1}')

  case "${#devices[@]}" in
    0)
      echo "Error: No adb devices found in 'device' state."
      echo "Connect a device or set IRIS_ADB_CONNECT / IRIS_ADB_DEVICE."
      return 1
      ;;
    1)
      ADB_CMD=(adb -s "${devices[0]}")
      echo "Using device: ${devices[0]}"
      ;;
    *)
      echo "Error: Multiple adb devices found. Set IRIS_ADB_DEVICE explicitly."
      return 1
      ;;
  esac
}

run_adb() {
  "${ADB_CMD[@]}" "$@"
}

run_root_shell() {
  local cmd="$1"
  if [[ "$IRIS_USE_ADB_ROOT" == "1" ]]; then
    run_adb shell "sh -c '$cmd'"
  else
    run_adb shell "su 0 sh -c '$cmd'"
  fi
}

run_root_shell_allow_failure() {
  local cmd="$1"
  if ! run_root_shell "$cmd" >/dev/null 2>&1; then
    return 0
  fi
}

bridge_socket_marker() {
  printf '%s' "$IRIS_BRIDGE_SOCKET_NAME"
}

wait_for_boot_completed() {
  local deadline now boot_completed
  deadline=$((SECONDS + IRIS_BOOT_TIMEOUT_SECONDS))

  run_adb wait-for-device >/dev/null 2>&1 || true

  while :; do
    boot_completed="$(run_adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' | head -n 1)"
    if [[ "$boot_completed" == "1" ]]; then
      return 0
    fi

    now=$SECONDS
    if (( now >= deadline )); then
      echo "Timed out waiting for Android boot completion."
      return 1
    fi

    sleep 1
  done
}

ensure_root_transport() {
  if [[ "$IRIS_USE_ADB_ROOT" != "1" ]]; then
    return 0
  fi

  run_adb root >/dev/null 2>&1 || true
  sleep 2
  run_adb wait-for-device >/dev/null 2>&1 || true
}

require_android_13() {
  local release
  release="$(run_adb shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' | head -n 1)"
  if [[ "$release" != "13" ]]; then
    echo "Expected Android 13, got: ${release:-unknown}"
    return 1
  fi
}

bootstrap_zygisk_next() {
  local cmd

  require_android_13
  cmd="/system/etc/init/magisk/magisk --auto-selinux --setup-sbin /system/etc/init/magisk /sbin >/dev/null 2>&1 || true; "
  cmd+="/sbin/magisk --sqlite \"update settings set value=0 where key='bootloop'\" >/dev/null 2>&1 || true; "
  cmd+="rm -f /data/adb/modules/zygisksu/disable /data/adb/modules/zygisk_lsposed/disable /data/adb/modules/zn_magisk_compat/disable; "
  cmd+="rm -rf /data/adb/zygisksu && mkdir -p /data/adb/zygisksu && chmod 0777 /data/adb/zygisksu; "
  cmd+="/sbin/magisk --auto-selinux --post-fs-data >/dev/null 2>&1 || true; "
  cmd+="/sbin/magisk --auto-selinux --service >/dev/null 2>&1 || true; "
  cmd+="cd /data/adb/modules/zygisksu && sh ./post-fs-data.sh >/data/local/tmp/zygisk_next_bootstrap.log 2>&1 &"
  if [[ "$IRIS_ZN_ZYGOTE_RESTART" == "1" ]]; then
    cmd+=" sleep 1; setprop ctl.restart zygote"
  fi

  run_root_shell "$cmd"

  sleep 2
  if [[ "$IRIS_ZN_ZYGOTE_RESTART" == "1" ]]; then
    sleep "$IRIS_ZN_ZYGOTE_RESTART_DELAY_SECONDS"
  fi
  run_root_shell_allow_failure "/sbin/magisk --auto-selinux --boot-complete"
  run_root_shell "test -d /sbin/.magisk && test -S /sbin/.magisk/device/socket"
}

is_bridge_runtime_ready() {
  local processes
  processes="$(run_adb shell "ps -A | grep -E 'lspd|com.kakao.talk' || true" 2>/dev/null | tr -d '\r' || true)"

  grep -Fq "lspd" <<<"$processes" &&
    grep -Fq "com.kakao.talk" <<<"$processes" &&
    run_adb shell "grep -F '$(bridge_socket_marker)' /proc/net/unix >/dev/null 2>&1"
}

wait_for_zygisk_ready() {
  local deadline dump processes recent
  deadline=$((SECONDS + IRIS_ZN_READY_TIMEOUT_SECONDS))

  while (( SECONDS < deadline )); do
    dump="$(run_root_shell "/data/adb/modules/zygisksu/bin/zygiskd64 dump-zn -sa" 2>&1 || true)"
    processes="$(run_root_shell "ps -A | grep -E 'magiskd|zn-nsdaemon-zygote64|zygote64' || true" 2>&1 || true)"
    recent="$(run_adb shell "logcat -d -b all -v threadtime | grep -E 'inject success for|loaded 64bit zygisk module zygisk_lsposed|loaded 1 64bit zygisk module|daemon launched' | tail -n 40" 2>&1 || true)"

    if grep -Fq "magiskd" <<<"$processes" && grep -Fq "zn-nsdaemon-zygote64" <<<"$processes"; then
      if ! grep -Eq "Could not connect|Last injection failed|Module files corrupted" <<<"$dump" && grep -Eq 'loaded 64bit zygisk module zygisk_lsposed|loaded 1 64bit zygisk module' <<<"$recent"; then
        dump="${dump}"$'\n\n'"Processes:"$'\n'"${processes}"$'\n\n'"Recent Events:"$'\n'"${recent}"
        printf '%s\n' "$dump"
        return 0
      fi
    fi

    if is_bridge_runtime_ready; then
      dump="${dump}"$'\n\n'"Processes:"$'\n'"${processes}"$'\n\n'"Recent Events:"$'\n'"${recent}"$'\n\n'"Fallback: bridge runtime ready"
      printf '%s\n' "$dump"
      return 0
    fi
    sleep 2
  done

  return 1
}

print_recent_logs() {
  echo
  echo "Recent Zygisk / LSPosed log lines:"
  run_adb shell "logcat -d -b all -v threadtime | grep -E 'Magisk|zn-daemon64|zygisk|LSPosed|lspd' | tail -n 120" || true
}

main() {
  local dump

  check_linux_host
  check_adb_installed
  select_adb_device
  wait_for_boot_completed
  ensure_root_transport
  bootstrap_zygisk_next

  if ! dump="$(wait_for_zygisk_ready)"; then
    echo "Timed out waiting for Zygisk Next readiness."
    print_recent_logs
    return 1
  fi

  echo
  echo "Current Zygisk Next status:"
  printf '%s\n' "$dump"
  print_recent_logs
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
