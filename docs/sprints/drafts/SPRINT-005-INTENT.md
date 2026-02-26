# Sprint 005 Intent: Pipeline Versioning & Run History

## Seed

pipeline runs should be persisted and create a history that is tied to a version of the pipeline that has been persisted and should be able to be viewed including the results and the artifacts that were generated

## Context

The project is a Kotlin DOT-based pipeline runner (Attractor) with a web UI at port 7070. Each `pipeline_run` row in SQLite stores both the pipeline definition (dotSource) and execution data, so runs are self-contained but have no concept of "lineage." There is no "pipeline" entity — only runs.

The critical gap: the Sprint 002 "iterate" flow calls `registry.updateDotAndPrompt(id, dotSource, originalPrompt)` and `PipelineRunner.resubmit()`, which **replaces** the existing run's DOT in-place. When a user iterates on a pipeline multiple times, all history of prior DOT versions, prompts, and associated run results/artifacts is permanently lost.

## Recent Sprint Context

- **Sprint 002** (completed): Added the "Iterate" flow — Iterate button opens Create view with pre-filled DOT, user modifies and re-runs; same run ID is reused, DOT overwritten in DB. Also added `originalPrompt` column.
- **Sprint 003** (completed): Added export/import zip round-trip. ZIP contains `pipeline-meta.json` + `logs/` artifacts. Added `getById()`, `insertOrReplaceImported()` to RunStore; extracted `hydrateEntry()` shared helper.
- **Sprint 004** (completed): Added static Dashboard tab with live pipeline cards, status badges, elapsed timers. Pure client-side; no new server endpoints.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/db/RunStore.kt` — SQLite wrapper; `StoredRun` data class; all columns present. Will need new columns and potentially new tables.
- `src/main/kotlin/attractor/web/PipelineRegistry.kt` — in-memory registry; `PipelineEntry` data class; `updateDotAndPrompt()` currently overwrites in-place.
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — `resubmit()` used by iterate flow; must be adapted to create new runs.
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — all HTTP endpoints + embedded HTML/CSS/JS; `POST /api/iterate` is the key endpoint to change.
- `src/main/kotlin/attractor/web/PipelineState.kt` — per-pipeline state tracking.

Key patterns to follow:
- SQLite migrations wrapped in `runCatching {}` for backward compatibility
- `@Synchronized` on all RunStore methods
- Embedded HTML/JS/CSS in WebMonitorServer (no external files)
- Prepared statements only — no dynamic SQL
- SSE via `broadcastUpdate()` for live updates

## Constraints

- Must follow existing SQLite migration pattern (backward-compatible `ALTER TABLE` in `runCatching`)
- Must not break existing Re-run, Resume, Export, Import, Archive, Delete flows
- No new Gradle dependencies
- Build: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- All UI changes are embedded in `WebMonitorServer.kt`

## Success Criteria

1. **History persists**: Every time a pipeline is iterated, the previous version (dotSource, originalPrompt, run results, artifacts) is preserved — not overwritten
2. **New-run-per-iterate**: Each "Iterate → Run" creates a NEW run entry with a new ID; old run remains intact
3. **Lineage linkage**: Runs can be grouped/linked by a `pipeline_family_id` (a shared identifier across all iterations of the same pipeline)
4. **History view in UI**: The Monitor panel (or a dedicated sub-view) shows all runs in the same pipeline family, ordered chronologically, with DOT version, status, and creation time
5. **Results + artifacts accessible**: Each historical run links to its logs and the artifacts in its `logsRoot` directory
6. **Restore from history**: Users can click any historical version to enter iterate mode pre-filled with that version's DOT and prompt
7. **History survives restart**: `pipeline_family_id` linkage is stored in DB and restored on startup

## Verification Strategy

- Spec: This intent document defines the required behavior
- Testing approach: manual end-to-end — create pipeline, run it, iterate 2x, verify 3 separate run records all sharing `pipeline_family_id`, old runs' DOT sources intact
- Edge cases:
  - Pre-sprint runs (no `pipeline_family_id`): backwards-compatible, shown as standalone in history
  - Import (Sprint 003): imported run's `pipeline_family_id` is preserved from the zip metadata
  - Re-run (not Iterate): Re-run creates a new run entry but keeps the same DOT; should it share the same family? TBD.
  - Delete: deleting a run from a family should not cascade-delete the others

## Uncertainty Assessment

- **Correctness uncertainty**: Low — well-understood CRUD + UI extension
- **Scope uncertainty**: Medium — the Sprint 002 iterate behavior change (new run vs. overwrite) has UX implications; history UI scope can vary widely
- **Architecture uncertainty**: Medium — key decision: (1) `parent_run_id` self-referencing link vs. (2) separate `pipeline_families` table vs. (3) shared `pipeline_family_id` UUID column on runs

## Open Questions

1. **Architecture model**: Should each iteration create a new run with `parent_run_id` (self-referencing, simpler), a shared `pipeline_family_id` UUID, or a new `pipeline_families` table? The `pipeline_family_id` approach is simplest and doesn't require a new table.
2. **UI placement**: Version history as a collapsible section inside the existing pipeline panel, a new "History" accordion below the graph, or a separate "Pipelines" top-level tab that groups families?
3. **Iterate UX change**: The Sprint 002 iterate flow currently reuses the same tab (same run ID). If we create a new run on iterate, the UX changes — should the new run open in a new tab, or should we navigate to the new run's tab while the old one is preserved?
4. **Re-run vs Iterate**: Should the existing Re-run button also create a new family-linked run, or only Iterate?
5. **Artifact viewer scope**: Just list artifact filenames with download links, or render text/log files inline in the browser?
