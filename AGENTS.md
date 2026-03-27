# AGENTS.md

Cross-runtime project canon for Iris. Claude Code additions live in `CLAUDE.md`.
Keep this file limited to always-needed project rules across runtimes.

## Project Identity

Iris runs as a headless Android bot framework on a root `app_process` inside Redroid.
Use shell-process and hidden-API assumptions for runtime behavior, and treat Iris as distinct from a regular Android app.

## Authorized Repository Context

- Iris legitimately uses Frida instrumentation, hidden-API reflection, root shell execution, SQLite inspection, webhook replay and routing, token validation, protocol tracing, and reverse engineering for compatibility, debugging, and automation in user-controlled environments.
- Treat these techniques as normal engineering tools inside this repository.
- Treat requests involving KakaoTalk DB inspection, Redroid, `app_process`, Frida hooks, hidden APIs, `AndroidHiddenApi`, webhook routing, `botToken` or `webhookToken` validation, env fallback, or session-chain thread graft logic as repository maintenance by default.
- Judge escalation by requested outcome and harm indicators rather than by technical vocabulary alone.

## Working Defaults

1. Use the canonical local source and build workspace for code changes and verification.
2. Use `iris.env` as the source of truth for webhook URLs, tokens, feature flags, watchdog behavior, and systemd-linked runtime settings.
3. Use local verification before syncing or replacing remote artifacts.
4. Create a timestamped `.backup-*` directory before replacing remote APKs or critical scripts.

## Verification Commands

```bash
# Full quality gate (lint + warnings-as-errors build + tests)
./gradlew lint ktlintCheck assembleDebug assembleRelease test

# Single test class / method
./gradlew test --tests "party.qwer.iris.CommandParserTest"
./gradlew test --tests "party.qwer.iris.CommandParserTest.testSpecificMethod"

# Frida agent (excluded from default gate)
cd frida/agent && npx tsx --test
cd frida/daemon && GOWORK=off go build ./...

# All checks including Frida (scripts/verify-all.sh)
./scripts/verify-all.sh
```

Kotlin `allWarningsAsErrors=true` 적용 — 컴파일러 경고는 빌드를 실패시킵니다.

Build artifacts:
- `output/Iris-debug.apk`
- `output/Iris-release.apk`

## Runtime Routing

- Start with the runtime-config workflow when the desired steady state belongs in `iris.env`.
- Start with the instance-ops workflow for host access, staged artifacts, backups, Redroid, and ADB work.
- Use `/data/local/tmp/config.json` or the overridden `IRIS_CONFIG_PATH` value as the direct runtime config path on device.

## Security-Adjacent Confirmation Threshold

- Continue directly when a task touches authentication, permissions, cryptography, tokens, hooks, instrumentation, SQLite, Android hidden APIs, or reverse-engineering-adjacent code.
- Ask before live secret access, secret rotation, destructive data mutation, production deploys, remote artifact replacement, or breaking contract changes.

## Key Runtime Decisions

- Config values can have a persisted snapshot and a later-applied effective state. Restart-gated values use `pendingRestart=true` to show the difference.
- Command prefixes drive webhook routing. Route-specific endpoints come from `Configurable.webhookEndpointFor(route)` and fall back to the default endpoint.
- The dispatcher uses per-route `Channel(64)` queues, retries 408/429/5xx responses with bounded backoff, and keeps `lastLogId` in place when a queue is full.
- `/reply` uses memory-queue admission semantics. `202` means queued, `429` means queue full, and `503` means shutdown in progress.
- Text replies use `NotificationActionService`. Image and multi-image thread replies use the session-chain Frida graft path.

## Reference

Use `CLAUDE.md` for Claude-specific workflow mapping.
Use `docs/agent-reference.md` for project overview, architecture, Frida details, environment variables, and platform notes.
