#!/usr/bin/env bash
set -euo pipefail

PACKET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 "${PACKET_DIR}/scripts/verify_closeout_packet.py" "${PACKET_DIR}"

rewrite_local_properties_if_needed() {
  local local_properties="$PACKET_DIR/local.properties"
  local configured_path=""

  if [[ -f "$local_properties" ]]; then
    configured_path="$(awk -F= '/^sdk\.dir=/{print substr($0, index($0,$2))}' "$local_properties" | tail -n 1)"
    if [[ -n "$configured_path" && -d "$configured_path" ]]; then
      return 0
    fi
  fi

  local sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
  if [[ -z "$sdk_root" || ! -d "$sdk_root" ]]; then
    echo "Android SDK path is unavailable for replay; set ANDROID_SDK_ROOT or ANDROID_HOME" >&2
    exit 1
  fi

  printf 'sdk.dir=%s\n' "$sdk_root" >"$local_properties"
}

pushd "${PACKET_DIR}" >/dev/null
rewrite_local_properties_if_needed
export GRADLE_USER_HOME="${PACKET_DIR}/.gradle-user-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.vfs.watch=false -Dkotlin.compiler.execution.strategy=in-process"
./gradlew --stop >/dev/null 2>&1 || true
./scripts/verify-all.sh
popd >/dev/null
