# Sprint 019 Intent: Rename "Pipeline" to "Project"

## Seed

> I think that we should rename "pipelines" to "projects" as it seems to make more sense in this usage. Does that seem feasible?

## Context

The codebase has used "pipeline" as its primary domain noun since inception, but the user believes
"project" better describes how the tool is actually used: a user submits a DOT graph, it runs as
a named, persistent project with stages, logs, family trees, and settings — not merely a transient
pipeline. This is a large-scale identifier rename across all four layers of the system.

### Orientation Summary

- **Scale**: 48 source files contain the word "pipeline" (case-insensitive); `WebMonitorServer.kt`
  alone has 386 occurrences; `RestApiRouter.kt` has 126; `SqliteRunStore.kt` has ~40.
- **Four rename layers confirmed by interview**:
  1. **UI labels** — all user-facing strings: badges, headings, nav labels, placeholders,
     documentation copy, log messages
  2. **REST API paths** — `/api/v1/pipelines` → `/api/v1/projects` (hard cut; old paths return 404)
  3. **CLI commands** — `attractor pipeline ...` → `attractor project ...`
  4. **Kotlin class/file names** — `Pipeline*` → `Project*` throughout
- **DB schema**: `pipeline_runs` table; columns `pipeline_log`, `pipeline_family_id`; index
  `idx_pipeline_runs_family_created`. **Included in scope** — rename via ALTER TABLE / migration
  DDL applied at startup (schema versioning or conditional column-add pattern already used in
  `SqliteRunStore`).
- **No new Gradle dependencies** (project policy).
- The SSE stream field `pipelineId` used by browser JS must be renamed to `projectId`
  simultaneously with the server-side serialization.

## Recent Sprint Context

- **Sprint 016**: Closeable pipeline tabs with `attractor-closed-tabs` localStorage key. Introduced
  `tab-close`, `saveClosedTabs`, tab lifecycle cleanup. (localStorage key name will change to
  `attractor-closed-tabs` → keep as-is or rename to `attractor-closed-project-tabs`; see Open
  Questions.)
- **Sprint 017**: Dashboard layout toggle (card/list). Introduced `dashPipelineData()`,
  `buildDashCards()`, `buildDashList()`, `setDashLayout()`, `attractor-dashboard-layout` key.
  All of these function/variable names contain "pipeline" and will be renamed.
- **Sprint 018**: Completion state clarity. Added `flashDashCard`, `prevStatuses`, `effectiveDone`,
  `completedPrefix`, `dash-card-flash`. No "pipeline" strings in the new symbols.

## Relevant Codebase Areas

### Files to rename (Kotlin)

| Current name | New name |
|---|---|
| `PipelineRunner.kt` | `ProjectRunner.kt` |
| `PipelineRegistry.kt` | `ProjectRegistry.kt` |
| `PipelineState.kt` | `ProjectState.kt` |
| `events/PipelineEvent.kt` | `events/ProjectEvent.kt` |
| `cli/commands/PipelineCommands.kt` | `cli/commands/ProjectCommands.kt` |

### Key classes / types to rename

- `PipelineRunner` object → `ProjectRunner`
- `PipelineRegistry` class → `ProjectRegistry`
- `PipelineState` class → `ProjectState`
- `StageRecord` — no change (stage noun unchanged)
- `PipelineEntry` data class → `ProjectEntry`
- `PipelineEvent` sealed class → `ProjectEvent` (and all sub-events: `PipelineStarted` →
  `ProjectStarted`, `PipelineCompleted` → `ProjectCompleted`, etc.)
- `PipelineEventObserver` / `PipelineEventBus` → `ProjectEventObserver` / `ProjectEventBus`
- `RunOptions` — no change
- `WebMonitorServer(port, registry: PipelineRegistry, store)` → constructor type updated

### CLI (`cli/commands/PipelineCommands.kt`)
- Command object name `PipelineCommands` → `ProjectCommands`
- Subcommand strings: `"pipeline"` → `"project"`, `"pipeline run"` → `"project run"`, etc.
- Referenced in `Main.kt` — update call sites

### REST API (`RestApiRouter.kt`)
- All path segments: `"pipelines"` → `"projects"`
- JSON response fields: `pipelineId` → `projectId`, `familyId` preserved (no "pipeline" in it)
- OpenAPI spec files: `docs/api/openapi.json` and `openapi.yaml` — update all `pipeline` → `project`
- `docs/api/rest-v1.md` documentation — update all references

### Database (`SqliteRunStore.kt`)
- Table: `pipeline_runs` → `project_runs`
- Columns: `pipeline_log` → `project_log`, `pipeline_family_id` → `project_family_id`
- Index: `idx_pipeline_runs_family_created` → `idx_project_runs_family_created`
- Migration: use `ALTER TABLE RENAME` (SQLite supports since 3.25.0) plus column migration if
  needed, or recreate table; apply idempotently at store init.

### WebMonitorServer.kt (JS/CSS/HTML)
- JS function names: `dashPipelineData` → `dashProjectData`, `initPipelines` → `initProjects`, etc.
- JS variable names: `pipelines` map → `projects`, `pipelineId` → `projectId`
- CSS class names: `.dash-pipeline-*` (if any) → `.dash-project-*`
- HTML/text: "pipeline" → "project" in all visible strings, placeholders, titles, tooltips
- SSE field: `pipelineId` in JSON → `projectId`
- localStorage key `attractor-closed-tabs` — keep unchanged (no "pipeline" in it; user data
  preserved). All other localStorage keys that include "pipeline" (none found) would be renamed.

### Test files
- `WebMonitorServerBrowserApiTest.kt` — update string assertions referencing "pipeline" strings
  that will change; update `testRunId` variable name if desired
- Any other test files referencing `PipelineRegistry`, `PipelineState`, etc.

## Constraints

- Must follow project conventions (no new Gradle deps, Kotest FunSpec, JVM 21)
- DB migration must be idempotent (safe to run on both fresh and existing databases)
- REST API rename is a **breaking change** — old paths (`/api/v1/pipelines`) return 404; no aliases
- `attractor-closed-tabs` localStorage key name is unchanged (no "pipeline" in it)
- All 5 pre-existing Sprint 016–018 markup-presence tests must continue to pass
- Build command: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`

## Success Criteria

1. The word "pipeline" no longer appears anywhere in user-visible text (UI, API responses, CLI help)
2. Kotlin class/file names use `Project*` exclusively; no `Pipeline*` symbols remain
3. `/api/v1/projects` endpoints return the same data as `/api/v1/pipelines` did; old paths 404
4. CLI `attractor project run ...` works; `attractor pipeline ...` no longer exists
5. DB schema uses `project_runs` table and `project_*` columns; migration is safe on existing DBs
6. All existing tests pass; new tests cover the API path rename and DB migration
7. No Kotlin compiler warnings; clean build

## Verification Strategy

- Markup-presence tests: assert `/api/v1/projects` returns 200; assert old `/api/v1/pipelines` path
  returns 404
- Markup-presence tests: assert `GET /` body does NOT contain "pipeline" in JS function/variable
  names or CSS class names (or test specific renamed symbols)
- Build passes with no warnings
- Grep for `pipeline` in source tree post-implementation to catch stragglers (case-insensitive)

## Uncertainty Assessment

- **Correctness uncertainty: Low** — mechanical rename; logic unchanged
- **Scope uncertainty: High** — 48 files, ~600+ occurrences; easy to miss edge cases (SSE field
  names, openapi spec, CLI help strings, error messages)
- **Architecture uncertainty: Low** — no new patterns; DB migration is the only structural change
- **DB migration risk: Medium** — SQLite's ALTER TABLE RENAME is well-supported but test coverage
  for migration path (existing DB) needs care

## Open Questions

1. **DB migration approach**: Should we use `ALTER TABLE RENAME TO` (clean, SQLite ≥ 3.25) or
   recreate the table to also rename columns? Column renames via `ALTER TABLE ... RENAME COLUMN`
   require SQLite ≥ 3.35 (2021). Since the app targets JVM + bundled sqlite-jdbc, the SQLite
   version is controlled. Use `ALTER TABLE RENAME COLUMN` for minimal disruption, then rename table.
2. **localStorage key for closed tabs**: `attractor-closed-tabs` has no "pipeline" in it — leave
   as-is to preserve user data across the upgrade. ✓
3. **SSE field rename (`pipelineId` → `projectId`)**: The browser's `applyUpdate()` reads `pipelineId`
   from SSE JSON. Both server serialization and client deserialization must change atomically.
4. **Event sub-type naming**: Keep event class names descriptive (`ProjectStarted`,
   `ProjectCompleted`, etc.) matching the new `ProjectEvent` parent.
