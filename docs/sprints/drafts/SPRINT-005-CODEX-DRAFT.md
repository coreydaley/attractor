# Sprint 005: Pipeline Versioning & Run History

## Overview

Sprint 002 introduced iterate-in-place by overwriting `dot_source` and reusing the same run ID. That makes each edit destructive: prior DOT versions, prompt context, and run outputs are lost.

This sprint changes the model to **immutable runs with family lineage**. Every iterate action creates a new run record with a new run ID, while linking related runs through a shared `pipeline_family_id`. History becomes queryable and restart-safe because lineage is persisted in SQLite, not inferred from in-memory state.

The sprint also adds a UI history view so users can inspect prior versions, jump to any historical run’s logs/artifacts, and restore any version into iterate mode.

## Use Cases

1. **Versioned iterate flow**: User runs a pipeline, iterates twice, and sees three separate runs with preserved DOT/prompt/results.
2. **Family timeline**: User opens a run and sees all runs in its family ordered by creation time with status and timestamp.
3. **Restore old version**: User picks version 1 from history and re-enters iterate mode with that DOT and prompt pre-filled.
4. **Artifacts by version**: User downloads artifacts from version 2 while version 3 is running.
5. **Restart resilience**: Server restarts and family history remains intact in Monitor.

## Architecture

```text
pipeline_runs (SQLite)
├── id (existing run id, PK)
├── ...existing fields...
└── pipeline_family_id (new, nullable for legacy rows)

Family rules
- New run (`/api/run`): pipeline_family_id = run.id
- Iterate (`/api/iterate`): create NEW run id, pipeline_family_id = source run's family or source id fallback
- Re-run (`/api/rerun`): create NEW run id, same family linkage rule as iterate
- Import (`/api/import-run`): preserve pipeline_family_id from metadata when present

In-memory model
PipelineEntry
└── pipelineFamilyId (new field)

UI
Run detail panel
└── Version History section
   ├── list runs in same family (created_at ascending)
   ├── show version index, status badge, created time, run id
   ├── actions: View run, Iterate from this version
   └── links to existing stage logs / artifact download for selected run
```

## Implementation Plan

### Phase 1: Persist Lineage in RunStore (~25%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`

**Tasks:**
- [ ] Add `pipelineFamilyId: String` to `StoredRun` (default `""` for compatibility).
- [ ] Add `pipeline_family_id TEXT NOT NULL DEFAULT ''` to `CREATE TABLE` for new DBs.
- [ ] Add migration `ALTER TABLE pipeline_runs ADD COLUMN pipeline_family_id TEXT NOT NULL DEFAULT ''` wrapped in `runCatching {}`.
- [ ] Update `insert(...)` signature to accept `pipelineFamilyId` and persist it.
- [ ] Update all `SELECT` queries (`getAll()`, `getById()`) to hydrate `pipeline_family_id`.
- [ ] Update `insertOrReplaceImported(...)` to round-trip `pipeline_family_id`.
- [ ] Add helper query `getFamilyRuns(familyId: String): List<StoredRun>` ordered by `created_at ASC`.
- [ ] Add fallback behavior for legacy runs: treat blank family as standalone (`familyId = id`).

### Phase 2: Registry Model + Startup Hydration (~20%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Add `pipelineFamilyId` to `PipelineEntry`.
- [ ] Update `register(...)` to accept/persist family id.
- [ ] Ensure `hydrateEntry(...)` sets `pipelineFamilyId` using `run.pipelineFamilyId.ifBlank { run.id }`.
- [ ] Add query helper `getFamily(id: String): List<PipelineEntry>` (or equivalent server-facing accessor) that returns family-linked runs in chronological order.
- [ ] Keep existing archive/delete semantics per-run only (no cascading family actions).

### Phase 3: New-Run Iterate/Re-run Semantics in Runner (~20%)

**Files:**
- `src/main/kotlin/attractor/web/PipelineRunner.kt`
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Keep `submit(...)` creating new run IDs; set default family id = new run id.
- [ ] Replace in-place `resubmit(id, ...)` behavior with a new-run path:
  - source run looked up by id
  - allocate new run id
  - register/store new run with copied options and chosen DOT/prompt
  - family id inherited from source (fallback source id)
- [ ] Provide explicit runner APIs for clarity:
  - `rerunFrom(runId, ...)` (same DOT/prompt as source)
  - `iterateFrom(runId, dotSource, originalPrompt, ...)` (edited DOT/prompt)
- [ ] Preserve existing resume behavior as in-place for paused runs (resume is continuation, not a new version).

### Phase 4: Endpoint and Payload Updates (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Update `POST /api/iterate` to create a new run (not overwrite source row).
- [ ] Update `POST /api/rerun` to create a new run in same family.
- [ ] Return new run ID from iterate/rerun responses: `{ "id": "new-run-id", "sourceId": "old-id" }`.
- [ ] Add history endpoint for current UI: `GET /api/pipeline-family?id={runId}` returning family metadata and runs.
- [ ] Include `pipelineFamilyId` in SSE snapshot JSON (`allPipelinesJson()`) so client can render history without extra fetch where possible.
- [ ] Export/import metadata changes:
  - export `pipelineFamilyId` in `pipeline-meta.json`
  - import preserves it when present, defaults to imported run id when absent (legacy zips)

### Phase 5: Monitor UI History and Restore UX (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**Tasks:**
- [ ] Add a **Version History** section in run detail view.
- [ ] Render family runs with: version number, created time, status badge, run id short form, and pipeline name.
- [ ] Add row actions:
  - `View` (switch selected tab/run)
  - `Iterate from this` (opens create/iterate mode with that run’s DOT + prompt)
- [ ] After successful iterate/rerun, auto-navigate to the newly created run tab.
- [ ] Keep existing artifact/log actions run-scoped and unchanged.
- [ ] Ensure archived runs can still appear in family history if currently viewed run references them.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add lineage column, migrations, family queries, import/export persistence |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Carry family id in memory and expose family-oriented lookup |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Switch iterate/rerun from in-place to new-run creation |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Endpoint behavior, SSE payload, history endpoint, embedded UI history panel |

## Definition of Done

- [ ] `pipeline_family_id` exists in DB and is backfilled logically for legacy rows via fallback (`id`).
- [ ] Iterate creates a new run ID and preserves source run unchanged.
- [ ] Re-run creates a new run ID and links to same family.
- [ ] Family linkage persists across server restart and DB reload.
- [ ] Monitor shows version history for a run’s family in chronological order.
- [ ] Users can restore any historical version into iterate mode.
- [ ] Each historical run remains independently viewable with its own logs/artifacts.
- [ ] Export/import round-trip preserves `pipelineFamilyId` when present.
- [ ] Delete removes only selected run; sibling family runs remain.
- [ ] Existing cancel/pause/resume/archive/unarchive flows still work.
- [ ] Build passes:
  - `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Iterate/rerun ID semantics change breaks UI assumptions | Medium | High | Return explicit `new id` in API responses and update JS navigation paths |
| Legacy rows have blank family id | High | Medium | Uniform fallback rule `family = run.id` everywhere (DB hydration + API response) |
| Import from older zip lacks family id | Medium | Medium | Default imported run to its own family id |
| History rendering gets expensive with large run counts | Low | Medium | Family-scoped query/filters; avoid full-table client-side grouping |
| Accidental behavioral drift between rerun and iterate | Medium | Medium | Separate runner methods with explicit tests/manual checklist for each flow |

## Security Considerations

- No dynamic SQL; continue prepared statements for all lineage queries.
- Family IDs are opaque server-side values; never trusted for filesystem paths.
- History endpoints expose only existing run metadata already visible in monitor context.
- Import keeps existing zip safety behavior; only metadata field set expands.

## Dependencies

- Sprint 002 (iterate UI/endpoint exists and must be behavior-switched to new-run semantics)
- Sprint 003 (export/import path must include new lineage metadata)
- Sprint 004 (dashboard/tab navigation should remain stable when new runs are spawned more frequently)

## Open Questions

1. Should history list include archived runs by default or behind a toggle?
2. Should version labels be purely chronological (`v1`, `v2`, ...) or explicitly show relation (`iterated from run-...`)?
3. Do we need a separate `parent_run_id` now, or can we defer until non-linear branching UX is required?
