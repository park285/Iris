#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

run_step() {
  local label="$1"
  shift
  echo "[verify-all] $label"
  "$@"
}

cd "$repo_root"

run_step "Gradle lint/format checks/tests" ./gradlew \
  :imagebridge-protocol:ktlintCheck \
  :imagebridge-protocol:test \
  :app:ktlintCheck \
  :bridge:ktlintCheck \
  :app:lint \
  :bridge:lint \
  :app:testDebugUnitTest \
  :bridge:testDebugUnitTest

run_step "Bridge architecture guardrails" "$repo_root/scripts/check-bridge-boundaries.sh"

run_step "Frida agent checks" bash -lc "cd '$repo_root/frida/agent' && npm run check"

run_step "Frida daemon checks" bash -lc "cd '$repo_root/frida/daemon' && make test vet"

if [[ "${IRIS_INCLUDE_GO_LINT:-0}" == "1" ]]; then
  run_step "Frida daemon lint" bash -lc "cd '$repo_root/frida/daemon' && make lint"
fi

run_step "Shell integration tests" bash -lc "cd '$repo_root' && bash ./tests/iris_control_device_selection_test.sh && bash ./tests/zygisk_next_bootstrap_test.sh"

echo "[verify-all] all checks passed"
