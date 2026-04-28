#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"

run_step() {
  local label="$1"
  shift
  echo "[verify-all] $label"
  "$@"
}

run_shell_integration_tests() {
  local test_script
  local shell_tests=(
    tests/iris_control_device_selection_test.sh
    tests/iris_control_env_loading_test.sh
    tests/iris_control_install_checksum_test.sh
    tests/iris_control_native_install_test.sh
    tests/iris_control_shell_quote_test.sh
    tests/zygisk_next_bootstrap_test.sh
    tests/closeout_packet_scripts_test.sh
  )

  for test_script in "${shell_tests[@]}"; do
    bash "$repo_root/$test_script"
  done
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

run_step "Native Rust format" cargo fmt --manifest-path "$repo_root/native/Cargo.toml" --all -- --check
run_step "Native Rust lint" cargo clippy --manifest-path "$repo_root/native/Cargo.toml" --workspace --all-targets -- -D warnings
run_step "Native Rust tests" cargo test --manifest-path "$repo_root/native/Cargo.toml" --workspace

mkdir -p \
  "$repo_root/tools/target/debug/deps" \
  "$repo_root/tools/target/debug/build" \
  "$repo_root/tools/target/miri"

run_step "Rust format" cargo fmt --manifest-path "$repo_root/tools/Cargo.toml" --all -- --check
run_step "Rust lint" cargo clippy --manifest-path "$repo_root/tools/Cargo.toml" --workspace --all-targets -- -D warnings
run_step "Rust tests" cargo test --manifest-path "$repo_root/tools/Cargo.toml" --workspace
run_step "Rust audit" cargo audit --file "$repo_root/tools/Cargo.lock"
run_step "Rust deny" cargo deny --manifest-path "$repo_root/tools/Cargo.toml" check
run_step "Rust miri" env MIRIFLAGS="-Zmiri-disable-isolation" cargo +nightly miri test --manifest-path "$repo_root/tools/Cargo.toml" --workspace

run_step "Bridge architecture guardrails" "$repo_root/scripts/check-bridge-boundaries.sh"

run_step "Shell integration tests" run_shell_integration_tests

echo "[verify-all] all checks passed"
