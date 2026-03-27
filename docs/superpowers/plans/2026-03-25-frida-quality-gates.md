# Frida Quality Gates Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Strengthen `frida/agent` and `frida/daemon` quality gates so TypeScript blocks unsafe `any` outside narrow Frida interop boundaries and Go enforces lint/static analysis cleanly.

**Architecture:** Keep the changes local to the Frida agent and daemon directories. Use TypeScript compiler strictness plus ESLint for the agent, and `golangci-lint` plus race-safe tests for the Go daemon.

**Tech Stack:** TypeScript, ESLint, `typescript-eslint`, Go, `golangci-lint`

---

### Task 1: TypeScript quality gates

**Files:**
- Create: `frida/agent/eslint.config.mjs`
- Modify: `frida/agent/package.json`
- Modify: `frida/agent/package-lock.json`
- Modify: `frida/agent/tsconfig.json`
- Modify: `frida/agent/thread-image-discovery.ts`

- [ ] **Step 1: Add stricter TS compiler options and ESLint config**
- [ ] **Step 2: Keep `any` exceptions limited to Frida interop boundary files**
- [ ] **Step 3: Install lint dependencies and refresh lockfile**
- [ ] **Step 4: Run `npm run typecheck`, `npm run lint`, `npm test`**

### Task 2: Go daemon quality gates

**Files:**
- Create: `frida/daemon/.golangci.yml`
- Modify: `frida/daemon/internal/app/run.go`
- Modify: `frida/daemon/internal/app/run_test.go`
- Modify: `frida/daemon/internal/app/readiness.go`
- Modify: `frida/daemon/internal/app/readiness_test.go`

- [ ] **Step 1: Add `golangci-lint` config with strong but repo-fitting linters**
- [ ] **Step 2: Fix current lint failures without broad refactors**
- [ ] **Step 3: Make the retry test race-safe so `go test -race` can pass**
- [ ] **Step 4: Run `GOWORK=off go test ./...`, `GOWORK=off go test -race ./...`, `GOWORK=off go vet ./...`, `GOWORK=off golangci-lint run ./...`**
