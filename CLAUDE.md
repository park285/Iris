# CLAUDE.md

Claude Code entrypoint for Iris.
Keep this file limited to Claude-specific workflow mapping and policy deltas.

@AGENTS.md

## Workflow Mapping

- Use `iris-runtime-config` for the runtime-config workflow.
- Use `iris-instance-ops` for the instance-ops workflow.

## Dependency Version Policy

Use live authoritative sources before changing `gradle/libs.versions.toml`.
Use this source priority:

1. official library documentation or official release source
2. authoritative package or registry index
3. direct registry or API lookup when a machine-readable source is available

Use environment tools such as `context7 MCP`, web search, or direct registry fetch to follow that source order.

Record the verified version in `gradle/libs.versions.toml` when you update dependencies.
