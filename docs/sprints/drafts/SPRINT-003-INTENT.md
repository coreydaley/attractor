# Sprint 003 Intent: Pipeline Run Export / Import

## Seed

"I want to be able to export pipelines as zip files and import pipeline runs as zip files, the zip file should include everything needed to create the pipeline in the database and work just like it had been created in the UI to begin with."

## Context

The web dashboard can already download artifacts (work files produced during execution) via `/api/download-artifacts`, which zips the `logsRoot` directory. But that zip contains only the execution artifacts — not the DB metadata needed to restore the pipeline entry. There is no import path at all.

The goal is a complete round-trip: export a run to a zip, move that zip to another machine (or reload after wiping the DB), import it, and see the run appear in the Monitor view exactly as it was — same status, logs, stage list, DOT source, original prompt, and run metadata.

## Recent Sprint Context

- **Sprint 001 (completed)**: Fixed SSE delivery latency and polling cadence. Foundation for reliable real-time updates.
- **Sprint 002 (completed)**: Added pipeline iteration — Iterate button, `/api/iterate/stream`, `/api/iterate`, `originalPrompt` storage through the full DB/registry/JSON stack.

## Relevant Codebase Areas

| File | Relevance |
|------|-----------|
| `src/main/kotlin/attractor/db/RunStore.kt` | `StoredRun` data class has all DB fields; `insert()` and `getAll()` are the persistence layer |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | `PipelineEntry` + `loadFromDB()` — reconstruction logic already exists for startup |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Has `download-artifacts` using `ZipOutputStream`; will host new export/import endpoints |
| `src/main/kotlin/attractor/state/Checkpoint.kt` | `checkpoint.json` lives in `logsRoot`; included in export |
| `logs/<graph>-<id>/` | `logsRoot` directory structure: `checkpoint.json`, `manifest.json`, stage subdirs with `live.log` and output files |

Key observations:
- The existing `download-artifacts` zip is **output-artifacts only** (no DB metadata). Export needs to bundle both.
- `RunStore.insert()` already has all the fields needed; just needs an `INSERT OR REPLACE` variant for import.
- `PipelineRegistry.loadFromDB()` is the reconstruction path used on startup — import can reuse the same `PipelineEntry`-building logic.
- The `logsRoot` path in the DB is machine-local (e.g. `logs/HelloWorldGo-run-123/`). On import the path must be re-established.

## Constraints

- No new Gradle dependencies
- Must integrate with existing `RunStore`, `PipelineRegistry`, `PipelineRunner` patterns
- Build: Java 21 + native Gradle 8.7
- Imported runs should appear in Monitor exactly as if they had run locally
- Running pipelines should not be exportable (only terminal states: completed/failed/cancelled/paused)

## Success Criteria

1. Export button appears on terminal-state pipeline panels; clicking downloads a `.zip`
2. The zip contains a `pipeline-meta.json` (all DB fields) plus the full `logsRoot` directory tree
3. An import UI (Upload modal or new drag-target) accepts a `.zip` file
4. After import, the run appears in the Monitor with correct status, stages, logs, DOT, and metadata
5. Re-importing the same zip (same run ID) is handled gracefully (overwrite or skip)
6. Imported runs are not automatically re-executed — they appear as their terminal state

## Verification Strategy

- Manual round-trip: export a completed run → delete it → import zip → verify run appears with correct data
- Import of a zip from a different machine (different absolute `logsRoot`) works correctly
- Import of a zip for a run whose ID already exists: graceful handling
- Zip with missing/corrupt `pipeline-meta.json`: returns a clear error

## Uncertainty Assessment

- **Correctness uncertainty**: Low — the data model is fully understood; it's read/write of known fields
- **Scope uncertainty**: Medium — "work just like it had been created in the UI" needs clarification on whether artifacts (logsRoot files) are required in the zip
- **Architecture uncertainty**: Low — follows existing download-artifacts ZIP pattern + loadFromDB reconstruction

## Open Questions

1. Should the export zip include only the DB metadata (`pipeline-meta.json`) or also the full `logsRoot` artifacts? The seed says "everything needed" — artifacts give access to stage outputs and checkpoint, but are potentially large.
2. When importing a run whose ID already exists, should we overwrite/replace, skip with an error, or generate a fresh ID?
3. Should exported runs be importable while a server is running (hot import) or only at startup?
