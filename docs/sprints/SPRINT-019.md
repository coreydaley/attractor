# Sprint 019: Rename "Pipeline" → "Project"

## Overview

Since the beginning, the codebase has used "pipeline" as its primary domain noun. In practice,
users create named, persistent **projects** with stages, logs, family trees, and settings — a
richer concept than a transient pipeline. Renaming to "project" brings the UI vocabulary in line
with how the tool is actually used.

The rename spans all five surfaces of the system: user-visible text in the SPA (UI labels,
headings, placeholders), the REST API paths (`/api/v1/pipelines` → `/api/v1/projects`), the CLI
subcommand (`attractor pipeline` → `attractor project`), Kotlin identifiers (class names, file
names, event types, data-class fields), and the database schema (table + column renames applied
via an idempotent migration at store init).

This sprint is purely mechanical — no logic changes. Implementation proceeds
deepest-dependency-first (events → state → registry → runner → db → router → server → CLI →
tests → docs) to keep the codebase compilable after each phase.

## Use Cases

1. **UI says "project"**: A user opens the dashboard and sees "Run Project", project badges, and
   project headings — never "pipeline".
2. **REST API hard cut**: A client POSTs to `/api/v1/projects` to create a run; the old
   `/api/v1/pipelines` path returns 404 with no aliases.
3. **CLI rename**: `attractor project run my.dot` works; `attractor pipeline ...` no longer exists
   and does not appear in help text.
4. **Existing DB migration**: A user who already has data starts the new binary; the store
   detects the legacy `pipeline_runs` table and renames it and its columns idempotently. Their
   data is preserved.
5. **SSE streams renamed**: Both the all-runs broadcast (`data.projects` array) and the per-run
   snapshot (`{"project": ...}`) use the new noun; the browser client is updated atomically.

## Architecture

```
Dependency order (deepest first):

  events/PipelineEvent.kt        → events/ProjectEvent.kt
  web/PipelineState.kt           → web/ProjectState.kt
  web/PipelineRegistry.kt        → web/ProjectRegistry.kt
  web/PipelineRunner.kt          → web/ProjectRunner.kt
  db/RunStore.kt                 (StoredRun.pipelineLog → projectLog)
  db/SqliteRunStore.kt           (table + column renames + migration DDL)
  db/JdbcRunStore.kt             (column name updates)
  web/RestApiRouter.kt           (path segments, SSE envelope key, JSON field names)
  web/WebMonitorServer.kt        (JS/CSS/HTML — bulk rename, SSE consumer)
  cli/commands/PipelineCommands.kt → cli/commands/ProjectCommands.kt
  cli/Main.kt                    (import + call site)
  test files                     (7+ files — update type refs, paths, assertions)
  docs/api/rest-v1.md            (API documentation)
  docs/api/openapi.json + .yaml  (OpenAPI spec)
```

### SSE Contracts (Two Separate Paths)

```
All-runs broadcast (WebMonitorServer → browser applyUpdate):
  BEFORE: { "pipelines": [ { id, state, ... }, ... ] }
  AFTER:  { "projects":  [ { id, state, ... }, ... ] }
  Client: applyUpdate() checks data.projects (was data.pipelines)

Per-run SSE snapshot (RestApiRouter → /api/v1/events/{id}):
  BEFORE: { "pipeline": { ...PipelineEntry JSON... } }
  AFTER:  { "project":  { ...ProjectEntry JSON... } }
  Client: any parser of this stream checks data.project (was data.pipeline)
```

Both server serialization and browser parsing must change in the same commit.

### DB Migration Strategy

`SqliteRunStore.init()` uses the existing try/catch idempotency pattern:

```kotlin
// Migration: rename pipeline_runs → project_runs (idempotent)
try { conn.createStatement().execute("ALTER TABLE pipeline_runs RENAME TO project_runs") }
catch (_: Exception) {}
// Migration: rename pipeline_log → project_log
try { conn.createStatement().execute("ALTER TABLE project_runs RENAME COLUMN pipeline_log TO project_log") }
catch (_: Exception) {}
// Migration: rename pipeline_family_id → project_family_id
try { conn.createStatement().execute("ALTER TABLE project_runs RENAME COLUMN pipeline_family_id TO project_family_id") }
catch (_: Exception) {}
// Migration: rebuild family index under new name
try {
    conn.createStatement().execute("DROP INDEX IF EXISTS idx_pipeline_runs_family_created")
    conn.createStatement().execute("CREATE INDEX IF NOT EXISTS idx_project_runs_family_created ON project_runs(project_family_id, created_at)")
} catch (_: Exception) {}
```

Dedicated migration tests cover three paths: fresh DB, legacy DB (created with old schema),
and already-migrated DB (idempotency check).

## Implementation Plan

### Phase 1: Kotlin source renames (~25%)

**Files:**
- `src/main/kotlin/attractor/events/PipelineEvent.kt` → `ProjectEvent.kt` — Rename + Modify
- `src/main/kotlin/attractor/web/PipelineState.kt` → `ProjectState.kt` — Rename + Modify
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` → `ProjectRegistry.kt` — Rename + Modify
- `src/main/kotlin/attractor/web/PipelineRunner.kt` → `ProjectRunner.kt` — Rename + Modify
- `src/main/kotlin/attractor/db/RunStore.kt` — Modify

**Tasks:**
- [ ] Rename `PipelineEvent.kt` → `ProjectEvent.kt`; rename `sealed class PipelineEvent` →
  `ProjectEvent`; rename all nested data classes: `PipelineStarted` → `ProjectStarted`,
  `PipelineCompleted` → `ProjectCompleted`, `PipelineFailed` → `ProjectFailed`,
  `PipelineCancelled` → `ProjectCancelled`, `PipelinePaused` → `ProjectPaused`;
  rename `PipelineEventBus` → `ProjectEventBus`; rename `PipelineEventObserver` →
  `ProjectEventObserver`
- [ ] Rename `PipelineState.kt` → `ProjectState.kt`; rename class `PipelineState` →
  `ProjectState`; update all `PipelineEvent.*` imports → `ProjectEvent.*`
- [ ] Rename `PipelineRegistry.kt` → `ProjectRegistry.kt`; rename `PipelineRegistry` →
  `ProjectRegistry`; rename `PipelineEntry` → `ProjectEntry`; update imports
- [ ] Rename `PipelineRunner.kt` → `ProjectRunner.kt`; rename `object PipelineRunner` →
  `ProjectRunner`; update all internal event references
- [ ] In `RunStore.kt`: rename `StoredRun.pipelineLog: String` → `projectLog: String`
- [ ] Update ALL import sites across the codebase for renamed types (compile errors guide this)

---

### Phase 2: Database rename + migration (~15%)

**Files:**
- `src/main/kotlin/attractor/db/SqliteRunStore.kt` — Modify
- `src/main/kotlin/attractor/db/JdbcRunStore.kt` — Modify

**Tasks:**
- [ ] Add migration block in `SqliteRunStore.init()` (before `CREATE TABLE IF NOT EXISTS`)
  to rename table, columns, and index as shown in Architecture section above
- [ ] Update `CREATE TABLE` DDL: `pipeline_runs` → `project_runs`, `pipeline_log` →
  `project_log`, `pipeline_family_id` → `project_family_id`
- [ ] Update `CREATE INDEX` DDL: `idx_pipeline_runs_family_created` →
  `idx_project_runs_family_created`, referencing `project_family_id`
- [ ] Update all SQL strings: `FROM pipeline_runs` → `FROM project_runs`,
  `pipeline_log` → `project_log`, `pipeline_family_id` → `project_family_id`
- [ ] Update column reads: `rs.getString("pipeline_log")` → `rs.getString("project_log")`,
  `rs.getString("pipeline_family_id")` → `rs.getString("project_family_id")`
- [ ] Update field assignments: `pipelineLog = ...` → `projectLog = ...`,
  `familyId = rs.getString("pipeline_family_id")` → `rs.getString("project_family_id")`
- [ ] Update `JdbcRunStore.kt` with matching column name changes

---

### Phase 3: REST API rename (~15%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] Replace all path segment strings: `"pipelines"` → `"projects"` in route matching
- [ ] Replace per-run SSE snapshot envelope: `"""{"pipeline":...}"""` → `"""{"project":...}"""`
- [ ] Replace JSON response field `pipelineId` → `projectId` (if present in response JSON)
- [ ] Update all type references: `PipelineRegistry` → `ProjectRegistry`,
  `PipelineEntry` → `ProjectEntry`, `PipelineRunner` → `ProjectRunner`,
  `PipelineState` → `ProjectState`, `PipelineEvent.*` → `ProjectEvent.*`
- [ ] Confirm old paths (`/api/v1/pipelines`) fall through to 404 — no aliases added

---

### Phase 4: WebMonitorServer.kt — SPA rename (~25%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (~386 occurrences)

**Tasks:**
- [ ] Update Kotlin constructor/field types: `PipelineRegistry` → `ProjectRegistry`, etc.
- [ ] JS variable: `var pipelines = {}` → `var projects = {}`; all `pipelines[key]` →
  `projects[key]` throughout `applyUpdate()`, `renderDashboard()`, et al.
- [ ] JS SSE consumer: `data.pipelines` → `data.projects` in `applyUpdate()`
- [ ] JS function names: `dashPipelineData` → `dashProjectData`; any other `*Pipeline*`
  function names
- [ ] All user-visible strings: "pipeline" → "project", "Pipeline" → "Project",
  "pipelines" → "projects", "Pipelines" → "Projects" (labels, headings, placeholders,
  tooltips, help copy, doc page text)
- [ ] CSS class names containing "pipeline" (if any) → "project"
- [ ] localStorage key `attractor-closed-tabs` — **leave unchanged** (no "pipeline" in it)
- [ ] Update `WebMonitorServer` constructor call in `Main.kt` (type changes)

---

### Phase 5: CLI rename (~5%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` → `ProjectCommands.kt` — Rename + Modify
- `src/main/kotlin/attractor/cli/Main.kt` — Modify

**Tasks:**
- [ ] Rename file `PipelineCommands.kt` → `ProjectCommands.kt`
- [ ] Rename class/object `PipelineCommands` → `ProjectCommands`
- [ ] Rename subcommand string `"pipeline"` → `"project"` (and all sub-subcommands)
- [ ] Update help strings: "pipeline" → "project" in all usage/description text
- [ ] Update `Main.kt`: import and reference `PipelineCommands` → `ProjectCommands`

---

### Phase 6: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/web/PipelineStateTest.kt` → `ProjectStateTest.kt` — Rename + Modify
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` — Modify
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` — Modify
- `src/test/kotlin/attractor/web/RestApiSseTest.kt` — Modify
- `src/test/kotlin/attractor/cli/commands/PipelineCommandsTest.kt` → `ProjectCommandsTest.kt` — Rename + Modify
- `src/test/kotlin/attractor/cli/commands/ArtifactCommandsTest.kt` — Modify
- `src/test/kotlin/attractor/cli/commands/EventsCommandTest.kt` — Modify
- `src/test/kotlin/attractor/cli/ApiClientTest.kt` — Modify
- `src/test/kotlin/attractor/cli/MainTest.kt` — Modify
- `src/test/kotlin/attractor/db/SqliteRunStoreTest.kt` — Modify (add migration tests)

**Tasks:**
- [ ] Rename `PipelineStateTest.kt` → `ProjectStateTest.kt`; update type refs
- [ ] Rename `PipelineCommandsTest.kt` → `ProjectCommandsTest.kt`; update subcommand strings
- [ ] In `WebMonitorServerBrowserApiTest.kt`: update `PipelineRegistry` → `ProjectRegistry`
  call site; update any string assertions containing literal "/api/v1/pipelines"
- [ ] Add test: `GET /api/v1/projects` returns 200
- [ ] Add test: `GET /api/v1/pipelines` returns 404
- [ ] In `RestApiRouterTest.kt`: update all `/api/v1/pipelines` path strings → `/api/v1/projects`
- [ ] In `RestApiSseTest.kt`: update SSE payload field expectations
  (`data.pipelines` → `data.projects`, `{"pipeline":` → `{"project":`)
- [ ] In `ArtifactCommandsTest.kt`: update `/api/v1/pipelines/{id}/...` → `/api/v1/projects/{id}/...`
- [ ] In `EventsCommandTest.kt`: update any "pipeline" string assertions
- [ ] In `ApiClientTest.kt`: update `/api/v1/pipelines` → `/api/v1/projects`; update error
  message assertions
- [ ] In `MainTest.kt`: update `"pipeline"` → `"project"` in command invocations and assertions
- [ ] In `SqliteRunStoreTest.kt`: add migration test — create DB with old `pipeline_runs` schema,
  insert a row, open store, verify row is accessible via new `project_runs` schema

---

### Phase 7: Docs (~5%)

**Files:**
- `docs/api/rest-v1.md` — Modify
- `docs/api/openapi.json` — Modify
- `docs/api/openapi.yaml` — Modify

**Tasks:**
- [ ] Replace all `/api/v1/pipelines` → `/api/v1/projects` in `rest-v1.md`
- [ ] Replace `pipelineId` → `projectId` and "pipeline" → "project" in prose and examples
- [ ] Update `openapi.json` + `openapi.yaml`: paths, operationIds, descriptions, schema field names
- [ ] Run `grep -ri "pipeline" src/ docs/api/` — verify only intentional occurrences remain
  (migration DDL strings and comments are exempt)

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `events/PipelineEvent.kt` | Rename → `ProjectEvent.kt` | Rename sealed class + all nested event types |
| `web/PipelineState.kt` | Rename → `ProjectState.kt` | Rename state class |
| `web/PipelineRegistry.kt` | Rename → `ProjectRegistry.kt` | Rename registry + `PipelineEntry` → `ProjectEntry` |
| `web/PipelineRunner.kt` | Rename → `ProjectRunner.kt` | Rename runner object |
| `db/RunStore.kt` | Modify | `pipelineLog` → `projectLog` in `StoredRun` |
| `db/SqliteRunStore.kt` | Modify | DB DDL rename + idempotent migration |
| `db/JdbcRunStore.kt` | Modify | Column name string updates |
| `web/RestApiRouter.kt` | Modify | Path segments, SSE envelope key, JSON field names |
| `web/WebMonitorServer.kt` | Modify | SPA strings, JS vars/fns, SSE consumer, Kotlin type refs |
| `cli/commands/PipelineCommands.kt` | Rename → `ProjectCommands.kt` | CLI subcommand rename |
| `cli/Main.kt` | Modify | Import + type reference update |
| `web/PipelineStateTest.kt` | Rename → `ProjectStateTest.kt` | Type ref updates |
| `web/WebMonitorServerBrowserApiTest.kt` | Modify | Type refs + add 404/200 route tests |
| `web/RestApiRouterTest.kt` | Modify | Path string updates |
| `web/RestApiSseTest.kt` | Modify | SSE payload field updates |
| `cli/commands/PipelineCommandsTest.kt` | Rename → `ProjectCommandsTest.kt` | Command string updates |
| `cli/commands/ArtifactCommandsTest.kt` | Modify | Path string updates |
| `cli/commands/EventsCommandTest.kt` | Modify | String assertion updates |
| `cli/ApiClientTest.kt` | Modify | Path + error message updates |
| `cli/MainTest.kt` | Modify | Command invocation updates |
| `db/SqliteRunStoreTest.kt` | Modify | Add migration test |
| `docs/api/rest-v1.md` | Modify | API doc path updates |
| `docs/api/openapi.json` | Modify | OpenAPI spec rename |
| `docs/api/openapi.yaml` | Modify | OpenAPI spec rename |

## Definition of Done

### Naming Consistency
- [ ] Zero occurrences of `pipeline`/`Pipeline` remain in Kotlin source files except:
  - Migration DDL strings that detect the legacy `pipeline_runs` table (intentional)
  - Comments describing the migration itself
- [ ] Zero occurrences of "pipeline" in user-visible JS strings, HTML text, and CSS class names
  in `WebMonitorServer.kt`
- [ ] `PipelineEvent`, `PipelineState`, `PipelineRegistry`, `PipelineRunner`,
  `PipelineEntry`, `PipelineCommands` symbols no longer exist

### API/CLI Contracts
- [ ] `GET /api/v1/projects` returns 200
- [ ] `GET /api/v1/pipelines` returns 404
- [ ] CLI `attractor project ...` works; `attractor pipeline ...` is not present
- [ ] All-runs SSE payload uses `data.projects` array (was `data.pipelines`)
- [ ] Per-run SSE snapshot uses `{"project": ...}` envelope (was `{"pipeline": ...}`)

### Data and Migration Safety
- [ ] SQLite schema uses `project_runs`, `project_log`, `project_family_id`,
  `idx_project_runs_family_created`
- [ ] Migration is idempotent: fresh DB creates new schema directly; legacy DB is migrated safely;
  already-migrated DB is a no-op
- [ ] Existing run/family data survives migration and remains queryable

### Quality
- [ ] All existing tests pass after update; no regressions
- [ ] 2 new API route tests added (200 + 404)
- [ ] 1 DB migration test added (legacy schema → new schema, data preserved)
- [ ] Build passes with no compiler warnings
- [ ] Build command:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- [ ] No new Gradle dependencies

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missed "pipeline" occurrence in a string | Medium | Low | Post-impl grep sweep; CI check |
| SSE client/server key mismatch (one side renamed, other not) | Low | High | Both sides changed in same Phase 4 edit; SSE test validates |
| DB column rename fails on SQLite < 3.35 | Low | Medium | `ALTER TABLE RENAME COLUMN` requires SQLite 3.35 (2021); sqlite-jdbc bundles 3.45+; try/catch means graceful no-op if somehow older |
| JdbcRunStore (MySQL/PostgreSQL) migration | Low | Low | Those dialects support RENAME COLUMN; try/catch idiom same as SQLite |
| Partial rename leaves mixed state | Medium | High | Compile-first approach — type renames cause compile errors that surface all sites; fixes must be complete to build |
| API hard cut surprises existing automation | High | Medium | Documented breaking change; old paths return 404 |

## Security Considerations

- No new routes, no new user-controlled input paths.
- DB migration uses hardcoded DDL strings (no user input).
- `esc()` / `JSON.stringify()` sanitization paths in the SPA are preserved as-is.
- Path rename is server-side only; no new injection surface.

## Dependencies

- Sprint 018 (completed) — this sprint builds on the stable codebase.
- No external dependencies; no new Gradle dependencies.

## Open Questions

1. **`attractor-closed-tabs` localStorage key**: Leave unchanged — no "pipeline" in it; user
   data preserved. ✓
2. **Export/import ZIP file naming** (`pipeline-meta.json` in artifact ZIPs): Not implemented in
   current codebase; no action needed in this sprint.
3. **`pipelineLog` field in `StoredRun`**: Renaming to `projectLog` will cause compile errors at
   all call sites, which is the desired effect — compile errors guide the full rename.
