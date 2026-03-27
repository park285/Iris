# Prompt Hierarchy External Review Brief

## Goal

This document summarizes the prompt-hierarchy refactor across the active repositories under `/home/kapu/gemini` so an external reviewer can evaluate:

- whether the final structure is coherent;
- whether responsibilities are split cleanly across global and project layers;
- whether the new prompt behavior matches the intended execution bias and interaction contract;
- whether any remaining consolidation, rollback, or simplification is advisable.

Use this brief as the current-state review entrypoint.

For historical line-by-line comparison, see:
- [2026-03-26-prompt-hierarchy-before-after.md](/home/kapu/gemini/Iris/docs/reviews/2026-03-26-prompt-hierarchy-before-after.md)

That packet is useful for diff-style review, but parts of it predate the final hardening pass. Treat the files listed in **Current Source of Truth** below as authoritative.

## Official Basis

The refactor was guided by these official documents:

- Anthropic memory docs  
  https://code.claude.com/docs/en/memory
- Anthropic prompt guidance  
  https://docs.anthropic.com/en/docs/build-with-claude/prompt-engineering/be-clear-and-direct
- Anthropic skill best practices  
  https://platform.claude.com/docs/en/agents-and-tools/agent-skills/best-practices
- OpenAI Codex `AGENTS.md` guide  
  https://developers.openai.com/codex/guides/agents-md/
- OpenAI prompt engineering  
  https://developers.openai.com/api/docs/guides/prompt-engineering/
- OpenAI reasoning best practices  
  https://developers.openai.com/api/docs/guides/reasoning-best-practices/

## Design Intent

The final hierarchy follows these principles:

1. Keep global behavior in global prompt files.
2. Keep project-wide rules in project `AGENTS.md`.
3. Keep `CLAUDE.md` thin and Claude-specific.
4. Keep long explanatory material out of the main prompt surface.
5. Prefer direct, positive, action-oriented language.
6. Default to execution where safe and authorized.
7. Treat technical vocabulary as neutral unless the requested outcome or authorization context makes it harmful.

## Current Source of Truth

### Global layer

- Claude global interaction contract: [`.claude/CLAUDE.md`](/home/kapu/.claude/CLAUDE.md)
- Codex global execution and interaction policy: [`.codex/AGENTS.md`](/home/kapu/.codex/AGENTS.md)

### Project layer

- Iris
  - [CLAUDE.md](/home/kapu/gemini/Iris/CLAUDE.md)
  - [AGENTS.md](/home/kapu/gemini/Iris/AGENTS.md)
  - [agent-reference.md](/home/kapu/gemini/Iris/docs/agent-reference.md)
- chat-bot-go-kakao
  - [CLAUDE.md](/home/kapu/gemini/chat-bot-go-kakao/CLAUDE.md)
  - [AGENTS.md](/home/kapu/gemini/chat-bot-go-kakao/AGENTS.md)
- hololive-bot
  - [CLAUDE.md](/home/kapu/gemini/hololive-bot/CLAUDE.md)
  - [AGENTS.md](/home/kapu/gemini/hololive-bot/AGENTS.md)
- llm
  - [CLAUDE.md](/home/kapu/gemini/llm/CLAUDE.md)
  - [AGENTS.md](/home/kapu/gemini/llm/AGENTS.md)
- settlement-go
  - [CLAUDE.md](/home/kapu/gemini/settlement-go/CLAUDE.md)
  - [AGENTS.md](/home/kapu/gemini/settlement-go/AGENTS.md)

### Active subtree canon files

- [hololive-bot/admin-dashboard/AGENTS.md](/home/kapu/gemini/hololive-bot/admin-dashboard/AGENTS.md)
- [hololive-bot/shared-go/AGENTS.md](/home/kapu/gemini/hololive-bot/shared-go/AGENTS.md)
- [hololive/hololive-kakao-bot-go/AGENTS.md](/home/kapu/gemini/hololive-bot/hololive/hololive-kakao-bot-go/AGENTS.md)
- [llm/admin-dashboard/AGENTS.md](/home/kapu/gemini/llm/admin-dashboard/AGENTS.md)
- [llm/game-bot-go/AGENTS.md](/home/kapu/gemini/llm/game-bot-go/AGENTS.md)
- [llm/game-bot-go/internal/common/AGENTS.md](/home/kapu/gemini/llm/game-bot-go/internal/common/AGENTS.md)
- [llm/mcp-llm-server-go/AGENTS.md](/home/kapu/gemini/llm/mcp-llm-server-go/AGENTS.md)
- [llm/shared-go/AGENTS.md](/home/kapu/gemini/llm/shared-go/AGENTS.md)

## What Changed

### 1. Global Claude prompt now owns the interaction contract

Main changes in [`.claude/CLAUDE.md`](/home/kapu/.claude/CLAUDE.md):

- user-facing output in Korean;
- `사용자님` address rule;
- compressed-by-default response rule;
- reduced emotional tone and anti-cheerleading rule;
- plain-Korean explanation requirement for important technical terms;
- action-first behavior for Claude responses;
- narrower clarification threshold;
- partial completion rule when only part of a request is blocked.

### 2. Global Codex prompt now owns execution bias and refusal threshold

Main changes in [`.codex/AGENTS.md`](/home/kapu/.codex/AGENTS.md):

- `Primary Objective`
- `Execution Bias`
- `Clarification Threshold`
- `Benign Dual-Use Technical Work`
- `Security-Adjacent Work`
- `Pre-Refusal Check`
- `Blocked-Request Fallback`
- Codex-side interaction contract so the same behavior does not depend on Claude-only files

### 3. Local `CLAUDE.md` files became thin wrappers

The project-level `CLAUDE.md` files now mostly do one or both of these:

- import project `AGENTS.md`
- add Claude-specific routing, tool notes, or policy deltas only

This replaced older monolithic `CLAUDE.md` files that mixed:

- build commands
- architecture
- domain explanation
- deployment notes
- coding standards

### 4. Local `AGENTS.md` files became compact project canons

The project-level `AGENTS.md` files now focus on:

- project identity
- working defaults
- verification commands
- repo-specific context
- narrow repository rules
- references to longer docs

This replaced broader, mixed-purpose files that often combined:

- policy
- architecture
- long technical reference
- implementation detail
- operational coordinates

### 5. Supporting material moved out of the main prompt surface

Example:
- Iris reference moved into [agent-reference.md](/home/kapu/gemini/Iris/docs/agent-reference.md)

The same principle was applied conceptually across other repositories even where a dedicated new reference file was not introduced.

### 6. Active subtree `AGENTS.md` files were normalized

Many subtree files under `hololive-bot` and `llm` previously used legacy structures such as:

- `INHERIT: ...`
- `L1` / `L2`
- large tables for context
- mixed standards + architecture + hotspot dumps

Those active subtree files were rewritten into compact canons with a simpler structure:

- `Project Identity`
- `Working Defaults`
- `Verification Commands`
- `Subtree Rules`
- `Reference`

## Intended Behavioral Effect

The intended model behavior after this refactor is:

- execute first when safe and authorized;
- ask fewer questions;
- avoid refusing based on technical words alone;
- treat security-adjacent maintenance work as normal engineering work by default;
- continue with the largest safe executable subset when one slice is blocked;
- keep Claude responses formal, compressed, and analytically neutral.

## Important Repository-Specific Hardening

### Iris

The Iris project canon explicitly marks these as normal engineering tools inside the repository:

- Frida instrumentation
- hidden-API reflection
- root `app_process`
- SQLite inspection
- webhook routing and replay
- token validation
- env fallback
- reverse engineering for compatibility and debugging

See:
- [Iris/AGENTS.md](/home/kapu/gemini/Iris/AGENTS.md)

This was done to reduce false-positive refusal or over-escalation when working in Iris.

## Scope Exclusions

The following path was intentionally excluded from active cleanup:

- `/home/kapu/gemini/llm/openclaw`

Reason:
- it was identified as a currently unused path during this session;
- it still contains a large legacy prompt surface;
- it was intentionally left out so the active-path cleanup would not spend effort on a dormant subtree.

## What Reviewers Should Evaluate

Please evaluate the final state against the following questions:

1. Is the global/project/subtree hierarchy now clear enough to maintain over time?
2. Is the split between wrapper files, canon files, and reference files clean?
3. Do the global Claude and Codex files express the same intent with reasonable cross-tool consistency?
4. Does the execution bias go too far, not far enough, or land in the right place?
5. Are any stop conditions still too broad?
6. Does the Iris local context go too far, or is it correctly scoped to repository-authorized maintenance?
7. Are any subtree files still too large or too reference-heavy for a canon file?
8. Should any additional reference files be created, or would that increase fragmentation more than it helps?

## Known Review Tensions

These tradeoffs were explicit during the refactor:

- cross-tool consistency vs tool-specific prompt files
- execution bias vs overly broad stop conditions
- compact canons vs reference sprawl
- local repository allow-context vs blanket allowlist risk
- active-path cleanup vs leaving dormant legacy paths untouched

## Suggested Review Output

If you want a structured review from an external reviewer, ask for:

1. findings ordered by priority;
2. explicit references to file paths and sections;
3. a recommendation on whether the hierarchy is now stable;
4. remaining simplification opportunities;
5. any dangerous over-permissiveness or still-overbroad refusal triggers.
