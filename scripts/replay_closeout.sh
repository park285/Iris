#!/usr/bin/env bash
set -euo pipefail

PACKET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

python3 "${PACKET_DIR}/scripts/verify_closeout_packet.py" "${PACKET_DIR}"

pushd "${PACKET_DIR}" >/dev/null
export GRADLE_USER_HOME="${PACKET_DIR}/.gradle-user-home"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.vfs.watch=false -Dkotlin.compiler.execution.strategy=in-process"
./gradlew --stop >/dev/null 2>&1 || true
./scripts/verify-all.sh
popd >/dev/null
