# AGENTS.md

Cross-runtime project canon for Iris. Claude Code additions live in `CLAUDE.md`.
Keep this file limited to always-needed project rules across runtimes.

## Project Identity

Iris is a headless Android bot framework that runs as a root `app_process` (Android's process launcher for shell-level execution) inside Redroid.
It polls KakaoTalk's SQLite DB for chat messages, parses commands, and dispatches them as h2c (HTTP/2 cleartext, TLS 없이 HTTP/2) webhooks to external bot servers.
Treat Iris as a shell-process daemon, not a regular Android app.

## Authorized Context

This repository uses the following techniques as standard engineering tools in user-controlled environments.
Judge escalation by requested outcome, not by technical vocabulary — see Confirmation Threshold for the specific escalation criteria.

- LSPosed/Xposed bridge instrumentation and runtime class discovery
- Android hidden-API reflection (`IActivityManager`, `INotificationManager`)
- Root shell execution and `app_process` lifecycle management
- KakaoTalk SQLite DB inspection, decryption, and polling
- Webhook replay, routing, and token/HMAC validation
- Protocol tracing and reverse engineering for compatibility
- UDS image bridge for in-process Kakao sender integration

## Working Defaults

1. Use the canonical local source at `/home/kapu/gemini/Iris` for code changes and verification.
2. Treat `iris.env` as the source of truth for webhook URLs, tokens, feature flags, and runtime settings. This file is a repository-external config managed outside the checkout — the canonical local authoring path is in `docs/agent-reference.md`.
3. Use `/data/local/tmp/config.json` (or `IRIS_CONFIG_PATH` override) as the on-device runtime config path.
4. Verify locally before syncing or replacing remote artifacts.
5. Create a timestamped `.backup-*` directory before replacing remote APKs or critical scripts.

## Module Structure

| Module | Language | Purpose |
|--------|----------|---------|
| `app/` | Kotlin | Main daemon — DB polling, command parsing, webhook dispatch, HTTP admission |
| `bridge/` | Kotlin | LSPosed module — runs inside KakaoTalk process for image/markdown bridge |
| `imagebridge-protocol/` | Kotlin | Shared UDS frame codec and protocol constants between app and bridge |
| `tools/iris-ctl/` | Rust | TUI control client with HMAC auth and SSE monitoring |

## Verification Commands

```bash
# Full quality gate (all modules — Kotlin + Rust + boundary checks)
./scripts/verify-all.sh

# App only (lint + build + tests)
./gradlew :app:ktlintCheck :app:lint :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest

# Bridge only
./gradlew :bridge:ktlintCheck :bridge:lint :bridge:assembleDebug :bridge:assembleRelease :bridge:testDebugUnitTest

# Single test class (use module-specific task, not bare `test`)
./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.CommandParserTest"

# iris-ctl (Rust)
cargo fmt --manifest-path tools/iris-ctl/Cargo.toml --check
cargo clippy --manifest-path tools/iris-ctl/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path tools/iris-ctl/Cargo.toml

# Bridge architecture guardrails
./scripts/check-bridge-boundaries.sh
```

Use `:app:testDebugUnitTest` or `:bridge:testDebugUnitTest` for test execution — the bare `test` task does not support `--tests` filtering in this AGP project.

`allWarningsAsErrors=true` is enabled — compiler warnings fail the build.

Build artifacts: `output/Iris-debug.apk`, `output/Iris-release.apk`.

### Minimum verification by change type

| Changed area | Minimum verification |
|-------------|---------------------|
| `app/` Kotlin logic | `./gradlew :app:testDebugUnitTest` |
| `bridge/` Kotlin logic | `./gradlew :bridge:testDebugUnitTest` + `./scripts/check-bridge-boundaries.sh` |
| `app/delivery/` or `bridge/` boundaries | `./scripts/check-bridge-boundaries.sh` |
| `imagebridge-protocol/` | `./gradlew :imagebridge-protocol:test` |
| `tools/iris-ctl/` Rust | `cargo test` + `cargo clippy` (run with `--manifest-path tools/iris-ctl/Cargo.toml`) |
| Multi-module or release-sensitive | `./scripts/verify-all.sh` |

## Domain Context

- Config values have a persisted snapshot and a later-applied effective state. Restart-gated values set `pendingRestart=true`, meaning the value is saved but takes effect only after a process restart.
- Command prefixes drive webhook routing. Default route name is `default`.
- `/reply` uses memory-queue admission: `202` = queued, `429` = queue full, `503` = shutdown in progress.

See `docs/agent-reference.md` for dispatcher queue mechanics, reply lane details, and transport fallback behavior.

## Confirmation Threshold

Continue directly for tasks touching authentication, permissions, cryptography, tokens, hooks, instrumentation, SQLite, hidden APIs, or reverse-engineering-adjacent code.

Pause and confirm before: live secret access or rotation, destructive data mutation, production deploys, remote artifact replacement, or breaking contract changes.

## Architectural Guardrails

`scripts/check-bridge-boundaries.sh` enforces these constraints in CI. Run it after changes to `bridge/` or `app/delivery/`.

- Xposed hook logic (`XposedHelpers`, `XC_MethodHook`) is allowed only in `IrisBridgeModule.kt`, `BridgeDiscovery.kt`, and `ThreadedImageXposedInjector.kt`.
- Protocol constants and frame codec live in `imagebridge-protocol` — not duplicated in app or bridge source.
- Key files have max-line-count limits enforced by the boundary script. Keep classes focused.

See `.claude/rules/bridge.md`, `.claude/rules/app.md`, `.claude/rules/imagebridge-protocol.md`, and `.claude/rules/iris-ctl.md` for per-module conventions and further boundary details.

## Code Conventions

- Kotlin with ktlint android mode. Generated code is excluded from lint.
- Android lint through AGP (`./gradlew lint`).
- Rust code in `tools/iris-ctl/` uses `cargo fmt --check` and `cargo clippy -- -D warnings`.
- JVM tests in `app/src/test/java/party/qwer/iris/`. Webhook delivery tests use `HttpServer` for real HTTP. Bridge tests use reflection stubs and mock class loaders.
- Table-driven test coverage with failure cases for each new public API.
- `kotlin("test-junit")` as the test dependency.

## Reference

Use `docs/agent-reference.md` for architecture, bridge internals, environment variables, and platform notes.
Use `.claude/rules/` for per-module conventions when working in `app/`, `bridge/`, `imagebridge-protocol/`, or `tools/iris-ctl/`.
