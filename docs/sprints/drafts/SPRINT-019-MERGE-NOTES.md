# Sprint 019 Merge Notes

## Claude Draft Strengths
- Correct dependency-ordered sequencing (events → state → registry → runner → store → router → server → CLI → tests)
- Correct identification of DB migration strategy and all three rename targets (table, columns, index)
- Correct `StoredRun.pipelineLog` → `projectLog` field rename
- Good identification of localStorage key continuity (`attractor-closed-tabs` unchanged)
- Hard API cut with no aliases correctly specified
- Comprehensive files summary table

## Codex Draft Strengths
- Cleaner phase organisation (Kotlin core → API → CLI → Web UI → DB → Tests)
- Explicit "Open Questions" about artifact zip file naming and export meta files
- Broader test scope: specifically calls out `RestApiSseTest`, `PipelineCommandsTest`, `ArtifactCommandsTest`, `ApiClientTest`, `MainTest`, `DocsEndpointTest`
- Adds migration safety concern (schema detection vs. blanket try/catch)

## Valid Critiques Accepted

### 1. SSE contract mis-modeled (High priority)
Codex correctly notes that `applyUpdate()` uses `data.pipelines` array (the all-runs broadcast),
not a `{"pipeline": ...}` envelope. Investigation confirms two separate SSE contracts:
- `WebMonitorServer` all-runs broadcast: `data.pipelines` array → rename to `data.projects`
- `RestApiRouter` per-run SSE snapshot: `"""{"pipeline": ${...}}"""` → rename to `"""{"project": ...}"""`
Both must be renamed atomically with the client-side parser.

### 2. Expanded test file scope (High priority)
Codex correctly identified test files not in Claude's draft that contain "pipeline" references:
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` → rename + update
- `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` → update paths
- `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` → update
- `src/test/kotlin/attractor/cli/ApiClientTest.kt` → update `/api/v1/pipelines` paths
- `src/test/kotlin/attractor/cli/MainTest.kt` → update "pipeline" string assertions
- `src/test/kotlin/attractor/web/RestApiSseTest.kt` → update SSE payload expectations
- `src/test/kotlin/attractor/web/PipelineStateTest.kt` → rename file + update type refs

### 3. DoD wording: "zero pipeline occurrences" too absolute
Allow explicit exceptions for migration comments, historical sprint references, and DB
migration DDL string literals (which must say "pipeline" to detect the legacy schema).

### 4. "Logs path already renamed" claim
Removed from use cases — that was a prior session change, not a sprint deliverable.

## Critiques Rejected (with reasoning)

### DB migration: PRAGMA detection vs. try/catch
Codex suggests using `sqlite_master` / `PRAGMA table_info` for idempotency detection. This is
technically safer, but the codebase *already uses* blanket try/catch for all its existing column
migrations (`ALTER TABLE pipeline_runs ADD COLUMN ...`). Switching only the rename migration to
a different pattern would be inconsistent. **Decision**: keep the existing try/catch pattern,
but add dedicated migration tests (fresh DB and legacy DB) to verify correctness. The test
coverage provides the safety net; the style remains consistent.

### "Open Questions" about artifact zip file naming
Codex asks whether `pipeline-meta.json` inside export ZIPs should be renamed. Scope check:
no such file exists in the current codebase (export/import is a potential future feature). No
action needed in this sprint.

### JdbcRunStore "dialect abstraction" critique
Codex suggests routing schema SQL through `SqlDialect` helpers. `SqlDialect` is used for
dialect-specific query differences, not migration DDL. The migration code lives directly in
`SqliteRunStore` by design. `JdbcRunStore` uses identical patterns to `SqliteRunStore` for column
reads; updating SQL string literals there is straightforward and consistent.

## Interview Refinements Applied
- All four rename layers confirmed: UI labels, REST API paths (hard cut, 404 for old), CLI
  commands, Kotlin class/file names
- localStorage key `attractor-closed-tabs` unchanged
- DB schema rename included in scope

## Final Decisions
1. Phase order: Kotlin core → DB → REST API → Web UI (SPA) → CLI → Tests → Docs
2. Migration: try/catch pattern (consistent with codebase) + dedicated migration test
3. SSE: both `data.pipelines` (broadcast) and `{"pipeline": ...}` (per-run) renamed
4. Test scope expanded to include all 7 additional test files identified by Codex
5. DoD updated to allow intentional "pipeline" occurrences in migration DDL strings only
