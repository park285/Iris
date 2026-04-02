# Closeout Packet — Iris (2026-04-02)

## Reviewed Revision

| Field | Value |
|-------|-------|
| Commit | 18a5f9bffc7450096f76e22aa0ad06a1a6e63587 |
| Branch | main |
| Dirty files | 110 |
| Generated at | 2026-04-02T00:23:00Z |

## What This Packet Contains

Evidence-based reviewer packet for six related change areas: auth contract hardening, reply admission ownership, ingress backlog restructure, H2c transport defaults, route boundary separation, and tools decomposition. Includes patch, source snapshot, JUnit results (734 Kotlin tests; 107 XML reports + 2 binary result files), Cargo results (120 Rust tests), gap analysis, and design rationale.

## Package Layout

```
closeout-test-20260402/
├── README.md                          this file
├── repo-README.md                     repository README snapshot
├── docs/
│   ├── executive-closeout.md          verdict table + residual risks
│   ├── evidence-index.md              traceability matrix + coverage detail
│   └── detailed-rationale.md          per-item design decisions + evidence anchors
├── artifacts/
│   ├── metadata/
│   │   └── revision.txt               commit SHA, tree hash, stash ref
│   ├── patches/
│   │   └── working-tree.patch         full diff (769 KB)
│   └── test-results/
│       ├── verification-summary.md    pass/fail counts
│       ├── gradle-output.txt          Gradle stdout
│       ├── cargo-output.txt           Cargo test stdout
│       └── junit/                     107 JUnit XML files + 2 Gradle binary result files
├── tests/
│   └── iris_auth_vectors.json         cross-language auth contract vectors
├── app/src/main/java/party/qwer/iris/ source snapshot (changed packages)
├── app/src/test/java/party/qwer/iris/ test snapshot (changed test files)
└── tools/                             Rust tools source snapshot
```

## Closure Status Summary

| Status | Count |
|--------|-------|
| Closed | 4 |
| Partial | 2 |
| Unverified | 0 |
| Open | 0 |

## Reading Order

1. **README.md** — this file (scope and layout)
2. **docs/executive-closeout.md** — verdict per item, verification summary, residual risks
3. **docs/evidence-index.md** — traceability matrix, per-item coverage detail, artifact locations
4. **docs/detailed-rationale.md** — design decisions and code context per item
5. **artifacts/patches/working-tree.patch** — full diff for line-level review
6. **app/src/** and **tools/** — source snapshot for context

## Verification Reproduction

```bash
# Kotlin unit tests (from repo root)
./gradlew :app:testDebugUnitTest

# Rust unit tests
cargo test --manifest-path tools/Cargo.toml

# Results location after Kotlin run
ls app/build/test-results/testDebugUnitTest/

# Expected: 734 tests, 0 failures (Kotlin) + 120 tests, 0 failures (Rust)
```
