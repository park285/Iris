#!/usr/bin/env bash
set -euo pipefail

home_dir="${HOME:?HOME must be set}"

# This manifest is intentionally strict: these mirrored Iris skill files should
# stay byte-identical across `.claude` and `.codex`. If one pair needs
# runtime-specific wording in the future, stop mirroring that pair and update
# this script instead of allowing silent drift.
pairs=(
  "${home_dir}/.claude/skills/iris-api-auth/SKILL.md:${home_dir}/.codex/skills/iris-api-auth/SKILL.md"
  "${home_dir}/.claude/skills/iris-instance-ops/agents/openai.yaml:${home_dir}/.codex/skills/iris-instance-ops/agents/openai.yaml"
  "${home_dir}/.claude/skills/iris-instance-ops/SKILL.md:${home_dir}/.codex/skills/iris-instance-ops/SKILL.md"
  "${home_dir}/.claude/skills/iris-instance-ops/references/current-setup.md:${home_dir}/.codex/skills/iris-instance-ops/references/current-setup.md"
  "${home_dir}/.claude/skills/iris-app-config/agents/openai.yaml:${home_dir}/.codex/skills/iris-app-config/agents/openai.yaml"
  "${home_dir}/.claude/skills/iris-app-config/SKILL.md:${home_dir}/.codex/skills/iris-app-config/SKILL.md"
  "${home_dir}/.claude/skills/iris-app-config/references/app-config.md:${home_dir}/.codex/skills/iris-app-config/references/app-config.md"
  "${home_dir}/.claude/skills/iris-runtime-config/agents/openai.yaml:${home_dir}/.codex/skills/iris-runtime-config/agents/openai.yaml"
  "${home_dir}/.claude/skills/iris-runtime-config/SKILL.md:${home_dir}/.codex/skills/iris-runtime-config/SKILL.md"
  "${home_dir}/.claude/skills/iris-runtime-config/references/runtime-config.md:${home_dir}/.codex/skills/iris-runtime-config/references/runtime-config.md"
)

status=0

for pair in "${pairs[@]}"; do
  left=${pair%%:*}
  right=${pair##*:}

  if [[ ! -f "$left" || ! -f "$right" ]]; then
    printf 'MISSING %s | %s\n' "$left" "$right" >&2
    status=1
    continue
  fi

  if diff -u "$left" "$right" >/dev/null; then
    printf 'OK %s == %s\n' "$left" "$right"
  else
    printf 'DRIFT %s != %s\n' "$left" "$right" >&2
    diff -u "$left" "$right" || true
    status=1
  fi
done

exit "$status"
