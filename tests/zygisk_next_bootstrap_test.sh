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

commands=()
release_value=13

if [[ "$(sh_quote "abc' def")" != "'abc'\"'\"' def'" ]]; then
  echo "sh_quote did not escape single quotes as expected"
  exit 1
fi

run_adb() {
  commands+=("$*")

  case "$*" in
    "shell getprop ro.build.version.release")
      printf '%s\n' "$release_value"
      ;;
    "shell su 0 sh -c 'test -d /sbin/.magisk && test -S /sbin/.magisk/device/socket'")
      ;;
    "shell su 0 sh -c '/data/adb/modules/zygisksu/bin/zygiskd64 status'")
      printf 'ok\n'
      ;;
  esac
}

ADB_CMD=(adb -s 127.0.0.1:5555)
IRIS_ZN_ZYGOTE_RESTART=1

release_value=14
set +e
require_android_13 >/dev/null 2>&1
status=$?
set -e

if [[ $status -eq 0 ]]; then
  echo "require_android_13 unexpectedly passed for Android 14"
  exit 1
fi

release_value=13
commands=()

bootstrap_zygisk_next

expected=(
  "shell su 0 sh -c '/system/etc/init/magisk/magisk --auto-selinux --setup-sbin /system/etc/init/magisk /sbin >/dev/null 2>&1 || true; /sbin/magisk --sqlite \"update settings set value=0 where key='\"'\"'bootloop'\"'\"'\" >/dev/null 2>&1 || true; rm -f /data/adb/modules/zygisksu/disable /data/adb/modules/zygisk_lsposed/disable /data/adb/modules/zn_magisk_compat/disable; rm -rf /data/adb/zygisksu && mkdir -p /data/adb/zygisksu && chmod 0700 /data/adb/zygisksu; /sbin/magisk --auto-selinux --post-fs-data >/dev/null 2>&1 || true; /sbin/magisk --auto-selinux --service >/dev/null 2>&1 || true; cd /data/adb/modules/zygisksu && sh ./post-fs-data.sh >/data/local/tmp/zygisk_next_bootstrap.log 2>&1 & sleep 1; setprop ctl.restart zygote'"
  "shell su 0 sh -c '/sbin/magisk --auto-selinux --boot-complete'"
  "shell su 0 sh -c 'test -d /sbin/.magisk && test -S /sbin/.magisk/device/socket'"
)

if [[ "${#commands[@]}" -ne "${#expected[@]}" ]]; then
  echo "unexpected command count: got ${#commands[@]}, want ${#expected[@]}"
  printf 'commands:\n'
  printf '  %s\n' "${commands[@]}"
  exit 1
fi

for i in "${!expected[@]}"; do
  if [[ "${commands[$i]}" != "${expected[$i]}" ]]; then
    echo "command mismatch at index $i"
    echo "got : ${commands[$i]}"
    echo "want: ${expected[$i]}"
    exit 1
  fi
done

echo "zygisk next bootstrap test passed"
