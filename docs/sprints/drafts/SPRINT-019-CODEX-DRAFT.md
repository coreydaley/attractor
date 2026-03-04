# Sprint 019: Rename "Pipeline" to "Project" Across Product Surface

## Overview

The product currently uses "pipeline" as its dominant user-facing and internal domain noun. Based on current usage, "project" is a better fit: users create named long-lived runs with settings, logs, stages, and family history rather than treating each run as a transient pipeline primitive.

This sprint performs a deliberate, full-stack rename from `pipeline` to `project` across UI labels, REST API paths, CLI commands, Kotlin symbol/file names, and SQLite schema identifiers. The goal is semantic clarity without behavior change. This is a broad mechanical refactor with one structural change: a safe, idempotent database migration.

The change is intentionally a hard cut for API/CLI naming: `/api/v1/projects` replaces `/api/v1/pipelines`, and `attractor project ...` replaces `attractor pipeline ...`. Legacy names are not retained as aliases in this sprint.

## Use Cases

1. **Aligned language in UI**: A user sees "Project" terminology consistently in dashboard labels, docs, and interaction copy.
2. **Clear API model**: API clients use `/api/v1/projects` routes and receive `projectId` fields, matching product vocabulary.
3. **Clear CLI model**: Operators use `attractor project ...` commands and help text with no mixed terminology.
4. **Migration continuity**: Existing SQLite installs migrate in place from `pipeline_*` schema names to `project_*` names without data loss.
5. **No semantic regressions**: Execution behavior, sorting, stage progression, SSE updates, archive/delete actions, and docs rendering continue to work as before.

## Architecture

### Rename Surfaces

1. **Presentation surface (web UI + docs page + logs/help text)**
- Replace user-visible "pipeline" strings with "project".
- Rename front-end JS helpers/vars used in dashboard rendering to reduce future drift.

2. **API surface (REST v1 + OpenAPI + docs)**
- Change route segment `/api/v1/pipelines` to `/api/v1/projects`.
- Change JSON field `pipelineId` to `projectId` where applicable.
- Keep non-target terms (for example `familyId`) unchanged.

3. **CLI surface**
- Change top-level noun command from `pipeline` to `project`.
- Update all usage/help examples and command registration in CLI entrypoint.

4. **Code/model surface (Kotlin files/types)**
- Rename `Pipeline*` classes/files/types to `Project*` consistently.
- Keep stage terminology unchanged (`StageRecord`, stage endpoints under project resources, etc.).

5. **Persistence surface (SQLite schema + migration)**
- Rename `pipeline_runs` table to `project_runs`.
- Rename columns `pipeline_log` -> `project_log` and `pipeline_family_id` -> `project_family_id`.
- Rename index `idx_pipeline_runs_family_created` -> `idx_project_runs_family_created`.
- Apply migration idempotently at startup for both fresh and pre-existing DBs.

### Migration Strategy (SQLite)

Apply migration in deterministic guarded steps during store initialization:

1. Detect whether legacy table/columns/index exist.
2. If legacy identifiers exist:
- `ALTER TABLE pipeline_runs RENAME TO project_runs`
- `ALTER TABLE project_runs RENAME COLUMN pipeline_log TO project_log`
- `ALTER TABLE project_runs RENAME COLUMN pipeline_family_id TO project_family_id`
- Drop/recreate (or rename if supported) family index to new identifier.
3. If already migrated, no-op.
4. Fresh DB path creates only `project_*` schema.

This keeps migration rerunnable, safe on restart, and compatible with existing data.

### SSE Contract Update

The browser update path and server serialization must be changed atomically:

- Server emits `projectId`.
- Browser `applyUpdate()` and related mappers read `projectId`.

No compatibility dual-field period is included in this sprint.

## Implementation Plan

### Phase 1: Core Kotlin renames and wiring (~25%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt` -> `src/main/kotlin/attractor/web/ProjectRunner.kt` (Rename + Modify)
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` -> `src/main/kotlin/attractor/web/ProjectRegistry.kt` (Rename + Modify)
- `src/main/kotlin/attractor/web/PipelineState.kt` -> `src/main/kotlin/attractor/web/ProjectState.kt` (Rename + Modify)
- `src/main/kotlin/attractor/events/PipelineEvent.kt` -> `src/main/kotlin/attractor/events/ProjectEvent.kt` (Rename + Modify)
- `src/main/kotlin/attractor/Main.kt` (Modify)
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (Modify)
- `src/main/kotlin/attractor/web/RestApiRouter.kt` (Modify)

**Tasks:**
- [ ] Rename core files/types from `Pipeline*` to `Project*` and update imports/call sites.
- [ ] Rename event hierarchy (`PipelineEvent` + subtypes) to `ProjectEvent` equivalents.
- [ ] Update constructor signatures and references (`ProjectRegistry`, `ProjectState`, etc.).
- [ ] Keep behavior identical; only naming and references change.

### Phase 2: REST API hard cut + docs/spec sync (~20%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` (Modify)
- `docs/api/openapi.yaml` (Modify)
- `docs/api/openapi.json` (Modify)
- `docs/api/rest-v1.md` (Modify)

**Tasks:**
- [ ] Change REST route group from `/api/v1/pipelines` to `/api/v1/projects`.
- [ ] Rename response/request fields where needed (`pipelineId` -> `projectId`).
- [ ] Ensure old `/api/v1/pipelines` paths are not routed (404 behavior).
- [ ] Update OpenAPI/spec docs and examples to match new route/field names.

### Phase 3: CLI command rename (~10%)

**Files:**
- `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` -> `src/main/kotlin/attractor/cli/commands/ProjectCommands.kt` (Rename + Modify)
- `src/main/kotlin/attractor/cli/Main.kt` (Modify)
- `docs/api/rest-v1.md` (Modify, CLI snippets if present)
- Built-in docs source in `WebMonitorServer.kt` (Modify)

**Tasks:**
- [ ] Rename command object and subcommand noun from `pipeline` to `project`.
- [ ] Update command registration and help/usage strings.
- [ ] Remove `attractor pipeline ...` path from CLI surface.

### Phase 4: Web UI, JS, CSS, SSE payload field rename (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (Modify)

**Tasks:**
- [ ] Rename JS symbols (`dashPipelineData`, `initPipelines`, `pipelines` map, etc.) to `project` names.
- [ ] Update UI strings/headings/tooltips/placeholders from "pipeline" to "project".
- [ ] Rename SSE JSON field usage from `pipelineId` to `projectId` in both emit and consume paths.
- [ ] Keep `attractor-closed-tabs` localStorage key unchanged for continuity.

### Phase 5: DB schema migration + store query updates (~15%)

**Files:**
- `src/main/kotlin/attractor/db/SqliteRunStore.kt` (Modify)
- `src/main/kotlin/attractor/db/JdbcRunStore.kt` (Modify, if SQL literals/aliases reference renamed identifiers)
- `src/main/kotlin/attractor/db/RunStore.kt` (Modify, only if model field names need propagation)

**Tasks:**
- [ ] Implement idempotent SQLite migration for table/columns/index rename.
- [ ] Update all SQL reads/writes to `project_runs` and `project_*` columns.
- [ ] Validate startup path on fresh DB and migrated DB.
- [ ] Preserve family/run semantics and ordering.

### Phase 6: Test suite and regression coverage (~10%)

**Files:**
- `src/test/kotlin/attractor/web/RestApiRouterTest.kt` (Modify)
- `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` (Modify)
- `src/test/kotlin/attractor/web/DocsEndpointTest.kt` (Modify)
- `src/test/kotlin/attractor/web/PipelineStateTest.kt` -> `src/test/kotlin/attractor/web/ProjectStateTest.kt` (Rename + Modify)
- Additional DB migration test file(s) under `src/test/kotlin/attractor/db/` (Create/Modify)

**Tasks:**
- [ ] Update existing tests for renamed types/routes/strings.
- [ ] Add explicit test that `/api/v1/projects` works and `/api/v1/pipelines` returns 404.
- [ ] Add migration test covering legacy schema -> renamed schema with preserved data.
- [ ] Keep markup-presence coverage for recent Sprint 016-018 symbols intact.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Rename/Modify | Convert core runner type naming to `ProjectRunner` |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Rename/Modify | Convert registry naming and call sites to `ProjectRegistry` |
| `src/main/kotlin/attractor/web/PipelineState.kt` | Rename/Modify | Convert state model naming to `ProjectState` |
| `src/main/kotlin/attractor/events/PipelineEvent.kt` | Rename/Modify | Rename event model and subtypes to `Project*` |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Rename REST resource paths and payload identifiers |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Rename UI copy, JS symbols, and SSE `projectId` field usage |
| `src/main/kotlin/attractor/cli/commands/PipelineCommands.kt` | Rename/Modify | Rename CLI noun and command object to `project` |
| `src/main/kotlin/attractor/cli/Main.kt` | Modify | Register renamed CLI command group |
| `src/main/kotlin/attractor/db/SqliteRunStore.kt` | Modify | Implement idempotent schema migration and SQL identifier rename |
| `docs/api/openapi.yaml` | Modify | Update API paths and schema fields to project terminology |
| `docs/api/openapi.json` | Modify | Keep JSON OpenAPI spec synchronized with YAML |
| `docs/api/rest-v1.md` | Modify | Update REST documentation/examples to `/projects` language |
| `src/test/kotlin/attractor/web/RestApiRouterTest.kt` | Modify | Verify new project routes and old route 404 behavior |
| `src/test/kotlin/attractor/web/WebMonitorServerBrowserApiTest.kt` | Modify | Verify dashboard/browser markup and JS markers after rename |
| `src/test/kotlin/attractor/web/DocsEndpointTest.kt` | Modify | Verify built-in docs expose project terms and commands |
| `src/test/kotlin/attractor/db/*` | Create/Modify | Validate migration safety from legacy SQLite schema |

## Definition of Done

### Naming Consistency
- [ ] User-visible "pipeline" terminology is removed from dashboard, docs page, API examples, and CLI help in favor of "project".
- [ ] `Pipeline*` Kotlin files/types are renamed to `Project*` (except intentionally unchanged domain terms like stage concepts).
- [ ] Case-insensitive grep over source/docs for residual `pipeline` only returns intentional exceptions approved in-sprint.

### API/CLI Contracts
- [ ] `GET /api/v1/projects` and all corresponding project endpoints work as prior pipeline endpoints did.
- [ ] Legacy `/api/v1/pipelines` endpoints return 404 (hard cut).
- [ ] CLI `attractor project ...` works and `attractor pipeline ...` is not present.
- [ ] SSE payload and client parsing use `projectId` consistently.

### Data and Migration Safety
- [ ] SQLite schema uses `project_runs`, `project_log`, `project_family_id`, and `idx_project_runs_family_created`.
- [ ] Migration is idempotent and safe for repeated startup.
- [ ] Existing run/family data survives migration and remains queryable.

### Quality
- [ ] Existing tests pass after rename updates.
- [ ] New tests cover route hard-cut and DB migration path.
- [ ] Build passes with no compiler warnings.
- [ ] No new Gradle dependencies added.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Partial rename leaves mixed `pipeline`/`project` behavior in one surface | High | High | Work in ordered phases with grep gates after each phase and final global sweep |
| SSE contract drift (server/client field mismatch) breaks live updates | Medium | High | Change serializer and client parser in same commit scope and add focused browser test markers |
| SQLite migration failure on existing user DB | Medium | High | Implement guarded/idempotent migration with explicit legacy+fresh tests |
| API/CLI hard cut surprises existing automation | High | Medium | Document breaking change explicitly in sprint docs and release notes artifacts |
| OpenAPI docs diverge from implementation after mass rename | Medium | Medium | Update router + specs + docs together and add route assertions in tests |

## Security Considerations

- No new external network integrations or permissions are introduced.
- Existing artifact path traversal protections and endpoint authorization posture remain unchanged.
- Migration logic must avoid dynamic SQL from untrusted inputs; only static schema DDL is used.
- Renames must preserve existing escaping/sanitization (`esc(...)`) paths in dashboard rendering.

## Dependencies

- Sprint 016: dashboard tab persistence and close-tab behavior remain intact while symbols are renamed.
- Sprint 017: dashboard card/list shared rendering path symbols are renamed consistently.
- Sprint 018: completion-state rendering behavior must remain unchanged after symbol rename.

## Open Questions

1. Should `pipeline-meta.json` inside export/import ZIP be renamed to `project-meta.json` now, or deferred for compatibility?
2. Should artifact file naming conventions (`pipeline-<id>.zip`) be hard-cut renamed in this sprint or staged separately?
3. Are there any intentional retained uses of "pipeline" in external-facing narrative docs (for example historical/project-origin context) that should remain?
