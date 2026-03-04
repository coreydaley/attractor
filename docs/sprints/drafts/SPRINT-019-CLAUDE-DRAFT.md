# Sprint 019: Rename "Pipeline" → "Project"

## Overview

Since the beginning, the codebase has used "pipeline" as its primary domain noun, carried over
from the DOT-graph / CI analogy. In practice, users create named, persistent **projects** with
stages, logs, family trees, and settings — a richer concept than a transient pipeline. Renaming to
"project" brings the UI vocabulary in line with how the tool is actually used.

The rename spans all four layers of the system: user-visible text in the SPA (UI labels, headings,
placeholders, log messages), the REST API paths (`/api/v1/pipelines` → `/api/v1/projects`), the
CLI subcommand (`attractor pipeline` → `attractor project`), and the Kotlin identifiers (class
names, file names, event types). The database schema is also updated via an idempotent migration
applied at store init.

This sprint is purely mechanical — no logic changes. The implementation is organised as a sequence
of surgical file edits, proceeding deepest-dependency-first (events → state → registry → runner →
store → router → server → CLI → tests → docs) to keep the codebase compilable after each phase.

## Use Cases

1. **UI says "project"**: A user opens the dashboard and sees "Run Project", project badges, and
   a "Projects" heading — never "pipeline".
2. **REST API**: A client POSTs to `/api/v1/projects` to create a run; the old
   `/api/v1/pipelines` path returns 404.
3. **CLI**: `attractor project run my.dot` works; `attractor pipeline ...` no longer exists.
4. **Logs**: The logs directory for a run called "brave-falcon" is `logs/brave-falcon` (already
   done in a previous session); the database persists it in the `project_runs` table.
5. **Existing DB migration**: A user who already has data in `pipeline_runs` starts the new
   binary; the store runs an idempotent migration and their data is preserved under the new schema.

## Architecture

```
Dependency order (deepest first):

  events/PipelineEvent.kt        → events/ProjectEvent.kt
  web/PipelineState.kt           → web/ProjectState.kt
  web/PipelineRegistry.kt        → web/ProjectRegistry.kt
  web/PipelineRunner.kt          → web/ProjectRunner.kt
  db/RunStore.kt                 (StoredRun.pipelineLog → projectLog)
  db/SqliteRunStore.kt           (table + column renames + migration)
  db/JdbcRunStore.kt             (column renames, if used)
  web/RestApiRouter.kt           (path segments, JSON field names)
  web/WebMonitorServer.kt        (JS/CSS/HTML — bulk rename)
  cli/commands/PipelineCommands.kt → cli/commands/ProjectCommands.kt
  cli/Main.kt                    (update import + call site)
  test files                     (update assertions, variable names)
  docs/api/rest-v1.md            (API documentation)
  docs/api/openapi.json + .yaml  (OpenAPI spec)
```

### DB Migration Strategy

`SqliteRunStore.init()` already applies idempotent `ALTER TABLE` migrations using try/catch.
The same pattern is used for the rename:

```
1. ALTER TABLE pipeline_runs RENAME TO project_runs            (if pipeline_runs exists)
2. ALTER TABLE project_runs RENAME COLUMN pipeline_log TO project_log      (SQLite ≥ 3.35)
3. ALTER TABLE project_runs RENAME COLUMN pipeline_family_id TO project_family_id
4. DROP INDEX IF EXISTS idx_pipeline_runs_family_created
5. CREATE INDEX IF NOT EXISTS idx_project_runs_family_created ...
```

Each step is wrapped in try/catch to be idempotent (safe on fresh DBs and already-migrated DBs).

### SSE field rename

`WebMonitorServer` serialises a per-entry JSON snapshot containing `"pipeline": {...}`. The
browser's `applyUpdate()` reads the incoming map using the key name. Both must change atomically
within the same commit:
- Server: `"""{"pipeline":...}"""` → `"""{"project":...}"""`
- Browser JS: `Object.keys(incoming)` and `pipelines[key]` → `projects[key]` etc.

## Implementation Plan

### Phase 1: Kotlin source renames (~30%)

**Files:**
- `src/main/kotlin/attractor/events/PipelineEvent.kt` → `ProjectEvent.kt` — rename file; rename
  `sealed class PipelineEvent` → `ProjectEvent`; rename all nested data classes
  (`PipelineStarted` → `ProjectStarted`, `PipelineCompleted` → `ProjectCompleted`,
  `PipelineFailed` → `ProjectFailed`, `PipelineCancelled` → `ProjectCancelled`,
  `PipelinePaused` → `ProjectPaused`); rename `PipelineEventBus` → `ProjectEventBus`;
  rename `PipelineEventObserver` → `ProjectEventObserver`
- `src/main/kotlin/attractor/web/PipelineState.kt` → `ProjectState.kt` — rename class
  `PipelineState` → `ProjectState`; update all imports of `PipelineEvent.*` → `ProjectEvent.*`
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` → `ProjectRegistry.kt` — rename class
  `PipelineRegistry` → `ProjectRegistry`; rename data class `PipelineEntry` → `ProjectEntry`;
  update all imports
- `src/main/kotlin/attractor/web/PipelineRunner.kt` → `ProjectRunner.kt` — rename object
  `PipelineRunner` → `ProjectRunner`; update all internal event references
- `src/main/kotlin/attractor/db/RunStore.kt` — rename field `pipelineLog: String` →
  `projectLog: String` in `StoredRun` data class

**Tasks:**
- [ ] Rename `PipelineEvent.kt` → `ProjectEvent.kt`; rename all symbols inside
- [ ] Rename `PipelineState.kt` → `ProjectState.kt`; update event references
- [ ] Rename `PipelineRegistry.kt` → `ProjectRegistry.kt`; rename `PipelineEntry` → `ProjectEntry`
- [ ] Rename `PipelineRunner.kt` → `ProjectRunner.kt`; update event references
- [ ] Rename `StoredRun.pipelineLog` → `projectLog` in `RunStore.kt`
- [ ] Update all import sites across the codebase for renamed types

---

### Phase 2: Database rename + migration (~15%)

**Files:**
- `src/main/kotlin/attractor/db/SqliteRunStore.kt` — Modify
- `src/main/kotlin/attractor/db/JdbcRunStore.kt` — Modify (if present)

**Tasks:**
- [ ] Add migration block in `SqliteRunStore.init()` to rename table and columns:
  ```kotlin
  // Migration: rename pipeline_runs → project_runs
  try { conn.createStatement().execute("ALTER TABLE pipeline_runs RENAME TO project_runs") }
  catch (_: Exception) {}
  // Migration: rename pipeline_log → project_log
  try { conn.createStatement().execute("ALTER TABLE project_runs RENAME COLUMN pipeline_log TO project_log") }
  catch (_: Exception) {}
  // Migration: rename pipeline_family_id → project_family_id
  try { conn.createStatement().execute("ALTER TABLE project_runs RENAME COLUMN pipeline_family_id TO project_family_id") }
  catch (_: Exception) {}
  // Migration: rebuild index under new name
  try {
    conn.createStatement().execute("DROP INDEX IF EXISTS idx_pipeline_runs_family_created")
    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_project_runs_family_created ON project_runs(project_family_id, created_at)")
  } catch (_: Exception) {}
  ```
- [ ] Update `CREATE TABLE` DDL: `pipeline_runs` → `project_runs`, `pipeline_log` → `project_log`,
  `pipeline_family_id` → `project_family_id`
- [ ] Update all SQL strings in `SqliteRunStore`: table name, column names
- [ ] Update `JdbcRunStore` (if it also references old column names)
- [ ] Update `StoredRun` field read: `rs.getString("pipeline_log")` → `rs.getString("project_log")`;
  `rs.getString("pipeline_family_id")` → `rs.getString("project_family_id")`
- [ ] Update field assignment: `pipelineLog = ...` → `projectLog = ...`

---

### Phase 3: REST API rename (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] Replace all path segment strings: `"pipelines"` → `"projects"` (route matching)
- [ ] Replace SSE snapshot key: `"""{"pipeline":...}"""` → `"""{"project":...}"""`
- [ ] Replace all JSON field names that contain "pipeline": `pipelineId` → `projectId`, etc.
- [ ] Replace `PipelineRegistry` references → `ProjectRegistry`, `PipelineEntry` → `ProjectEntry`
- [ ] Replace `PipelineRunner` call sites → `ProjectRunner`
- [ ] Replace `PipelineState` references → `ProjectState`
- [ ] Replace `PipelineEvent.*` references → `ProjectEvent.*`
- [ ] Confirm old paths `/api/v1/pipelines` now fall through to 404 (no aliases added)

---

### Phase 4: WebMonitorServer.kt — SPA rename (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (~386 occurrences)

**Tasks:**
- [ ] Update constructor parameter type: `registry: PipelineRegistry` → `registry: ProjectRegistry`
- [ ] Update all Kotlin-side usages of renamed types
- [ ] JS function names: `dashPipelineData` → `dashProjectData`; `initPipelines` → `initProjects`
  (if present); any other `*Pipeline*` function names
- [ ] JS variable names: `var pipelines = {}` → `var projects = {}`; `pipelineId` → `projectId`
- [ ] SSE incoming-key consumer: `Object.keys(incoming)` iterates values; the key name in the
  object coming from the server changes from `"pipeline"` to `"project"` — update client
  destructuring accordingly
- [ ] All user-visible strings: "pipeline" → "project", "Pipeline" → "Project",
  "pipelines" → "projects", "Pipelines" → "Projects"
- [ ] CSS class names containing "pipeline" (if any) → "project"
- [ ] HTML attributes, aria-labels, titles, placeholders, button text, help copy
- [ ] localStorage key `attractor-closed-tabs` — **leave unchanged** (no "pipeline" in it)
- [ ] Update `WebMonitorServer(port, registry: ProjectRegistry, store)` call site in `Main.kt`

---

### Phase 5: CLI rename (~5%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` → `ProjectCommands.kt` — Rename
- `src/main/kotlin/attractor/cli/Main.kt` — Modify

**Tasks:**
- [ ] Rename file `PipelineCommands.kt` → `ProjectCommands.kt`
- [ ] Rename class/object `PipelineCommands` → `ProjectCommands`
- [ ] Rename subcommand string `"pipeline"` → `"project"` (and all sub-subcommands)
- [ ] Update help strings: "pipeline" → "project" in usage/description text
- [ ] Update `Main.kt` import and reference: `PipelineCommands` → `ProjectCommands`

---

### Phase 6: Tests and docs (~10%)

**Files:**
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Modify
- Other test files referencing renamed types — Modify
- `docs/api/rest-v1.md` — Modify
- `docs/api/openapi.json` — Modify
- `docs/api/openapi.yaml` — Modify

**Tasks:**
- [ ] Update `WebMonitorServerBrowserApiTest`: `reg.register(testRunId, ...)` call site (type is
  now `ProjectRegistry`); update any string assertions that contain literal "pipeline" paths
- [ ] Add assertion: `GET /api/v1/projects` returns 200
- [ ] Add assertion: `GET /api/v1/pipelines` returns 404
- [ ] Update `docs/api/rest-v1.md`: all `/api/v1/pipelines` → `/api/v1/projects`; field names
- [ ] Update `openapi.json` + `openapi.yaml`: paths, operationIds, descriptions
- [ ] Run `grep -ri pipeline src/` — expect zero occurrences in source files (verify no stragglers)
- [ ] Build and test pass cleanly

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `events/PipelineEvent.kt` | Rename → `ProjectEvent.kt` | Rename sealed class + all nested types |
| `web/PipelineState.kt` | Rename → `ProjectState.kt` | Rename class, update event refs |
| `web/PipelineRegistry.kt` | Rename → `ProjectRegistry.kt` | Rename class + `PipelineEntry` → `ProjectEntry` |
| `web/PipelineRunner.kt` | Rename → `ProjectRunner.kt` | Rename object, update event refs |
| `db/RunStore.kt` | Modify | `pipelineLog` → `projectLog` in `StoredRun` |
| `db/SqliteRunStore.kt` | Modify | DB DDL rename + idempotent migration |
| `db/JdbcRunStore.kt` | Modify | Column name update |
| `web/RestApiRouter.kt` | Modify | Path segments + JSON field names |
| `web/WebMonitorServer.kt` | Modify | SPA strings, JS vars/fns, Kotlin type refs |
| `cli/commands/PipelineCommands.kt` | Rename → `ProjectCommands.kt` | CLI subcommand |
| `cli/Main.kt` | Modify | Update import + type reference |
| `WebMonitorServerBrowserApiTest.kt` | Modify | Update assertions + add path tests |
| Other test files (as needed) | Modify | Update renamed type refs |
| `docs/api/rest-v1.md` | Modify | API doc path updates |
| `docs/api/openapi.json` | Modify | OpenAPI spec rename |
| `docs/api/openapi.yaml` | Modify | OpenAPI spec rename |

## Definition of Done

- [ ] Zero occurrences of `pipeline`/`Pipeline` remain in Kotlin source files (except in comments
  that describe the migration itself)
- [ ] Zero occurrences of "pipeline" in user-visible JS/HTML/CSS strings in `WebMonitorServer.kt`
- [ ] `/api/v1/projects` returns 200; `/api/v1/pipelines` returns 404
- [ ] `attractor project run ...` works in CLI; `attractor pipeline` no longer exists
- [ ] DB migration runs idempotently on both fresh and existing databases
- [ ] All existing tests pass; no regressions
- [ ] `GET /api/v1/projects` markup-presence test added and passes
- [ ] `GET /api/v1/pipelines` 404 test added and passes
- [ ] Build passes with no compiler warnings
- [ ] `docs/api/rest-v1.md`, `openapi.json`, `openapi.yaml` updated

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missed occurrence of "pipeline" in a string | Medium | Low | Post-impl grep sweep; CI check |
| SSE client/server key mismatch (one side renamed, other not) | Low | High | Both sides changed in same Phase 4 edit; test verifies dashboard loads |
| DB column rename fails on older SQLite | Low | Medium | `ALTER TABLE RENAME COLUMN` requires SQLite 3.35 (2021); sqlite-jdbc bundles 3.45+; add version check or fallback |
| JdbcRunStore (MySQL/PostgreSQL) migration | Low | Medium | Those dialects support RENAME COLUMN; wrap in try/catch same as SQLite migrations |
| LocalStorage data loss for `attractor-closed-tabs` | None | High | Key has no "pipeline" in it; left unchanged |
| Import cycle after file renames | Low | Medium | Kotlin rename refactoring; verify package declarations match new file names |

## Security Considerations

- No new routes, no new user-controlled input paths.
- DB migration uses hardcoded DDL strings (no user input).
- Path rename is server-side only; no injection surface introduced.

## Dependencies

- Sprint 018 (completed) — this sprint builds on top of the stable codebase.
- No external dependencies.

## Open Questions

1. **`JdbcRunStore` MySQL/PostgreSQL**: Does `ALTER TABLE project_runs RENAME COLUMN pipeline_log TO project_log` work on both MySQL and PostgreSQL? MySQL supports it since 8.0; PostgreSQL since 9.x. Both are fine. Use same try/catch idiom.
2. **Stale `pipelineLog` field on `StoredRun`**: After rename to `projectLog`, any existing callers of `run.pipelineLog` will fail to compile — this surfaces all call sites automatically, which is good.
3. **`attractor-closed-tabs` localStorage key**: No change. The key contains no "pipeline" noun. Users' persisted closed-tabs data is preserved across the upgrade.
