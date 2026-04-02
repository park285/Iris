# AGENTS.md

Cross-runtime working canon for Iris. Keep this file short, direct, and operational.

## Identity

Iris is a headless Android bot framework that runs as a root `app_process` daemon inside Redroid.
It polls KakaoTalk SQLite DBs, parses commands, dispatches outbound webhooks, and accepts `/reply` requests for Kakao delivery.

## Local Canon

Use `/home/kapu/gemini/Iris` as the canonical local source tree.
Verify locally before syncing to the host or device.
Create a timestamped `.backup-*` directory before replacing remote APKs or critical scripts.

## Modules

- `app/`: DB polling, command ingress, webhook dispatch, HTTP admission
- `bridge/`: LSPosed bridge inside KakaoTalk
- `imagebridge-protocol/`: shared UDS protocol and constants
- `tools/iris-common/`: shared auth, models, transport helpers
- `tools/iris-daemon/`: host-side watchdog, config sync, process lifecycle
- `tools/iris-ctl/`: Rust TUI control client

## Skill Routing

When Codex Iris skills are available, route specialized work to the owning skill.

- `iris-runtime-config`: `iris.env`, feature flags, daemon/systemd, env-backed drift
- `iris-app-config`: device `config.json`, route tables, app-managed persisted config
- `iris-instance-ops`: host access, backups, APK/script sync, Redroid/ADB work
- `iris-api-auth`: signed HTTP API calls and auth debugging

Keep this file for repository-wide rules. Keep operational runbooks and env ownership in the skills.

## Workflow

1. Read the smallest set of files that define the behavior you are changing.
2. State the target behavior, output shape, and success condition explicitly.
3. Use runtime status, bridge status, and delivery status as separate signals when debugging.

## Verification

Run the smallest relevant check first. Expand only when the change crosses modules or release boundaries.

```bash
./scripts/verify-all.sh
```

## Runtime Facts

- `/reply` `202` means queue admission succeeded. Use reply status and pipeline logs to confirm Kakao handoff.
- Use `.claude/rules/` for module-level runtime and verification detail.

## Escalation

Continue directly for normal repository work involving hidden APIs, hooks, SQLite inspection, webhook auth, routing, and compatibility tracing.

Pause and confirm before:

- live secret access or rotation
- destructive data mutation
- production deploys
- remote artifact replacement
- breaking contract changes

## Reference

Use `docs/agent-reference.md` for architecture and runtime notes.
Use `.claude/rules/` for current module-specific conventions.
