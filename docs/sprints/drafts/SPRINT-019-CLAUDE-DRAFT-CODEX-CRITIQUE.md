# Critique: SPRINT-019-CLAUDE-DRAFT

## Overall Assessment

Claude's draft is directionally strong: it captures the intended hard cut (`/api/v1/pipelines` -> `/api/v1/projects`), includes Kotlin symbol/file renames, and calls out DB migration and SSE coupling as primary risk areas.

The main gaps are implementation-safety and scope-completeness. Several assumptions in the draft do not match current code shape, and the migration strategy is too permissive for a high-impact schema rename.

## High-Priority Fixes

1. **DB migration plan is too failure-tolerant and can hide partial schema states**
- The draft proposes blanket `try/catch` around each `ALTER` step.
- In this repo, that pattern is used for additive columns, but table/column rename is riskier: swallowing errors can leave mixed identifiers and runtime SQL failures.
- Required correction:
  - Guard each step with explicit schema detection (`sqlite_master`, `PRAGMA table_info`) rather than exception-only control flow.
  - Run rename steps in a transaction where possible.
  - Add dedicated migration tests for: fresh DB, legacy DB, and already-migrated DB.

2. **SSE contract section is partially mis-modeled vs current implementation**
- Draft centers on renaming single-event envelope `{"pipeline": ...}` -> `{"project": ...}` and says browser `applyUpdate()` must be updated for that.
- Current dashboard `applyUpdate()` consumes `data.pipelines` arrays, not a `pipeline` envelope.
- Required correction:
  - Separate contracts clearly: dashboard all-runs stream (`pipelines` array) vs per-run stream (`pipeline` envelope).
  - Specify exactly which payload fields are renamed and where client parsing changes are required.
  - Add tests for both `/api/v1/events` and `/api/v1/events/{id}` payload compatibility after rename.

3. **File/test scope is incomplete for a hard API/CLI noun cut**
- Draft under-specifies impacted CLI/docs tests and related command files.
- Current tree also requires updates in `ArtifactCommands`, `EventsCommand`, CLI help examples in `Main.kt`, CLI tests (`PipelineCommandsTest`, `ArtifactCommandsTest`, `ApiClientTest`), and SSE tests (`RestApiSseTest`).
- Required correction:
  - Expand file scope to include all command groups and tests using `/api/v1/pipelines` or "pipeline" help text.
  - Add explicit CLI regression criteria for renamed command noun and paths.

## Medium-Priority Improvements

1. **DoD "zero pipeline occurrences in Kotlin source" is too absolute**
- Some occurrences may remain intentionally (migration comments, compatibility error strings, historical docs text).
- Better DoD: zero non-exempt occurrences, with an explicit allowlist for intentional compatibility/history strings.

2. **Use-case claim about logs path being already renamed is out-of-band**
- The statement "already done in a previous session" is not verifiable in this sprint artifact and does not help acceptance scope.
- Prefer neutral wording tied to current sprint deliverables.

3. **JdbcRunStore migration note should align with dialect abstraction**
- The draft discusses raw backend DDL support; in this codebase, schema SQL flows through `SqlDialect` helpers.
- Plan should call out dialect-level updates and tests rather than ad-hoc SQL assumptions.

## What’s Strong

- Good dependency-aware sequencing of major renames.
- Correct emphasis on API/CLI hard cut and no alias behavior.
- Includes OpenAPI + REST docs synchronization.
- Calls out localStorage key continuity (`attractor-closed-tabs`) correctly.

## Bottom Line

This draft is close, but it needs stronger migration safety and broader scope coverage to be execution-safe. Tightening schema-guard logic, correcting SSE contract assumptions, and explicitly covering CLI/test/documentation blast radius will make it implementation-ready.
