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
  :app:assembleDebug \
  :app:assembleRelease \
  :app:ktlintCheck \
  :bridge:ktlintCheck \
  :bridge:assembleDebug \
  :bridge:assembleRelease \
  :app:lint \
  :bridge:lint \
  :app:testDebugUnitTest \
  :bridge:testDebugUnitTest

run_step "Rust format" cargo fmt --manifest-path "$repo_root/tools/Cargo.toml" --all -- --check
run_step "Rust lint" cargo clippy --manifest-path "$repo_root/tools/Cargo.toml" --workspace --all-targets -- -D warnings
run_step "Rust tests" cargo test --manifest-path "$repo_root/tools/Cargo.toml" --workspace
run_step "Rust audit" cargo audit --file "$repo_root/tools/Cargo.lock"
run_step "Rust deny" cargo deny --manifest-path "$repo_root/tools/Cargo.toml" check

run_step "Bridge architecture guardrails" "$repo_root/scripts/check-bridge-boundaries.sh"

run_step "Shell integration tests" bash -lc "cd '$repo_root' && bash ./tests/iris_control_device_selection_test.sh && bash ./tests/zygisk_next_bootstrap_test.sh"

echo "[verify-all] all checks passed"
