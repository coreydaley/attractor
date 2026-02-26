# Sprint 003: Pipeline Run Export / Import

## Overview

Attractor can currently download run artifacts (`/api/download-artifacts`), but that output is only the
`logsRoot` file tree. It does not include DB-backed run metadata (`id`, `dotSource`, status,
flags, timestamps, archived/originalPrompt), so it cannot recreate a run in another environment or
after DB loss.

This sprint adds a complete run portability loop:
1. Export a terminal-state run into one zip that includes both metadata and artifacts.
2. Import that zip into a running server.
3. Rehydrate DB + in-memory registry so the run appears in Monitor exactly like a locally-created run
   (same ID, status, stage list reconstruction from checkpoint, logs, DOT, metadata).

The design intentionally reuses existing architecture:
- `RunStore` remains the system of record for persisted runs.
- `PipelineRegistry.loadFromDB()` remains the reconstruction model to mirror startup behavior.
- `WebMonitorServer` remains the integration point for HTTP endpoints and dashboard UI controls.
- `Checkpoint.load(logsRoot)` continues to drive stage reconstruction when available.

## Use Cases

1. **Cross-machine migration**: Export completed run on machine A, import on machine B, inspect run in Monitor with logs and stage state intact.
2. **Backup and restore**: Delete local DB entry/artifacts, import zip, recover run history.
3. **Forensic sharing**: Send run bundle to teammate; they can inspect full execution context (DOT, prompt, logs, checkpoint, manifest).
4. **Conflict handling**: Re-import same run ID and choose deterministic behavior (`overwrite` or `skip`) without corrupting state.
5. **Safety on active runs**: Running/idle runs cannot be exported; only terminal-state snapshots are allowed.

## Architecture

### Export/Import Model

```text
EXPORT
Monitor UI (terminal run) -> GET /api/export-run?id=<runId>
    -> WebMonitorServer resolves PipelineEntry
    -> validates terminal status
    -> writes zip stream:
         pipeline-meta.json               (all StoredRun-equivalent fields)
         logs/<relative tree from logsRoot>

IMPORT
Create/Monitor UI -> POST /api/import-run?onConflict=overwrite|skip  (body: application/zip)
    -> WebMonitorServer unzips into temp dir
    -> parse pipeline-meta.json
    -> validate required fields + terminal status
    -> materialize logs to new local logsRoot (machine-safe path)
    -> RunStore.upsertImported(...)  (INSERT OR REPLACE)
    -> PipelineRegistry.upsertImportedEntry(...) (in-memory alignment)
    -> broadcastUpdate()
```

### Zip Format

```text
<export-name>.zip
├── pipeline-meta.json
└── logs/
    ├── checkpoint.json
    ├── manifest.json
    └── <stage-node-id>/
        ├── live.log
        └── ... stage outputs ...
```

`pipeline-meta.json` includes all data needed to reconstruct `StoredRun`/`PipelineEntry`:
- `id`
- `fileName`
- `dotSource`
- `status` (must be terminal)
- `simulate`
- `autoApprove`
- `createdAt`
- `pipelineLog`
- `archived`
- `originalPrompt`
- `logsRoot` (treated as informational only; remapped on import)

### Path Remapping Rule

Imported bundles must not trust absolute/foreign `logsRoot` values. Import computes a local root:
- target path pattern: `logs/<safeGraphName>-<runId>`
- extracted logs are placed there
- DB row uses this new local path

This preserves existing expectations of `Checkpoint.load(run.logsRoot)` and stage-log endpoints while
remaining machine-portable.

### Conflict Policy

Support explicit import conflict mode:
- `onConflict=overwrite` (default): existing run ID is replaced in DB and memory.
- `onConflict=skip`: no mutation for existing ID; endpoint returns a clear response indicating skipped.

No ID regeneration in this sprint; preserving ID is necessary for deterministic run identity.

## Implementation Plan

### Phase 1: Persistence + Registry Import Primitives (~25%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt`
- `src/main/kotlin/attractor/web/PipelineRegistry.kt`

**Tasks:**
- [ ] Add `RunStore.insertOrReplaceImported(run: StoredRun)` using `INSERT OR REPLACE` across all persisted columns.
- [ ] Add `RunStore.getById(id: String): StoredRun?` for conflict checks and endpoint diagnostics.
- [ ] Add `PipelineRegistry.upsertImported(run: StoredRun)` that:
  - creates fresh `PipelineState`
  - applies crash-recovery rule only for literal `running` rows (convert display to `failed`)
  - restores log lines from `pipelineLog`
  - reconstructs stages from checkpoint + parsed DOT (same logic as `loadFromDB()`)
  - replaces existing entry/order slot deterministically for same ID
- [ ] Refactor shared reconstruction logic from `loadFromDB()` into a private helper to avoid drift between startup hydration and import hydration.

### Phase 2: Backend Export/Import Endpoints + Zip Handling (~45%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt`

**Tasks:**
- [ ] Add `GET /api/export-run?id=<pipelineId>` endpoint:
  - validates run exists and status is terminal (`completed|failed|cancelled|paused`)
  - validates `logsRoot` exists
  - streams zip with `pipeline-meta.json` + `logs/` subtree
  - returns 409 for non-terminal run, 404 for missing run/artifacts
- [ ] Add `POST /api/import-run` endpoint:
  - accepts raw `application/zip` request body
  - supports `onConflict` query param (`overwrite` default, `skip`)
  - extracts to temp dir, validates required zip members
  - parses `pipeline-meta.json` via existing kotlinx JSON stack
  - remaps logs path under local `logs/`
  - writes DB via `insertOrReplaceImported`
  - calls `registry.upsertImported(...)`
  - calls `broadcastUpdate()`
- [ ] Add zip safety checks:
  - reject path traversal (`..`, absolute paths)
  - reject missing/corrupt `pipeline-meta.json`
  - reject non-terminal statuses at import
- [ ] Keep `/api/download-artifacts` unchanged for artifact-only use; export/import is a new explicit pathway.

### Phase 3: Dashboard UI Controls for Export/Import (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (embedded HTML/CSS/JS)

**Tasks:**
- [ ] Add `Export Run` button in monitor action row (visible only on terminal states).
- [ ] Add Import control in Create or Monitor header (`<input type="file" accept=".zip">` + trigger button).
- [ ] Implement `exportRun()` JS helper to download `/api/export-run?id=...`.
- [ ] Implement `importRunZip(file)` JS helper:
  - POST raw zip bytes to `/api/import-run?onConflict=overwrite`
  - show success/error in existing status UI surface
  - trigger state refresh (`kickPoll()` fallback; SSE will also converge)
- [ ] Keep Archive/Delete actions unchanged; imported runs obey current archive semantics.

### Phase 4: Validation + Error Semantics + Docs (~10%)

**Files:**
- `docs/sprints/SPRINT-003.md` (future finalized sprint doc)
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` (response messages)

**Tasks:**
- [ ] Standardize JSON errors for import/export (`{"error":"..."}`) matching existing API style.
- [ ] Add concise server logs around export/import actions for diagnostics.
- [ ] Document import result payload shape (`imported|skipped`, `id`, `logsRoot`) for UI handling.
- [ ] Manual test script for round-trip and conflict cases.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add import-safe upsert and lookup helpers |
| `src/main/kotlin/attractor/web/PipelineRegistry.kt` | Modify | Add imported-run upsert path reusing DB reconstruction semantics |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add `/api/export-run`, `/api/import-run`, zip handling, and UI controls |
| `docs/sprints/SPRINT-003.md` | Create (later) | Finalized sprint plan after draft review |

## Definition of Done

- [ ] Terminal run panel shows `Export Run` action.
- [ ] Exported zip contains `pipeline-meta.json` and full `logs/` tree.
- [ ] Import accepts valid zip and materializes run into DB + in-memory registry without restart.
- [ ] Imported run appears in Monitor with correct status, stage list, logs, DOT source, prompt, and metadata.
- [ ] Re-import same ID with `onConflict=overwrite` replaces prior record deterministically.
- [ ] Re-import same ID with `onConflict=skip` returns non-error skip response and leaves existing data unchanged.
- [ ] Import rejects malformed/corrupt zips with clear error response.
- [ ] Import rejects non-terminal statuses.
- [ ] No new Gradle dependencies.
- [ ] Build succeeds on Java 21 / Gradle 8.7.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Zip path traversal during extraction | Medium | High | Normalize and validate each zip entry path before writing |
| Divergence between `loadFromDB()` and import reconstruction | Medium | Medium | Extract shared helper in `PipelineRegistry` and reuse in both flows |
| Large artifact bundles cause memory pressure | Low | Medium | Stream zip output/input; avoid loading entire archive into memory |
| Imported `logsRoot` collisions on disk | Low | Medium | Deterministic target path + overwrite semantics tied to conflict mode |
| Corrupt checkpoint prevents stage reconstruction | Medium | Low | Preserve existing fallback behavior: run still imports, stages may remain minimal |

## Security Considerations

- Import is a file-ingest endpoint: enforce path normalization and reject unsafe zip entries.
- Continue using JSON parsing and prepared SQL statements; no dynamic SQL.
- Keep CORS policy consistent with existing local dashboard model.
- Do not execute anything from imported artifacts; treat them as inert files.

## Dependencies

- Sprint 001 (completed): reliable SSE + polling convergence makes hot-import visibility immediate.
- Sprint 002 (completed): `originalPrompt` already persisted; export metadata includes it by default.

## Open Questions

1. Should import default conflict mode be `overwrite` (operator convenience) or `skip` (safer by default)?
2. Do we want a hard maximum zip size for initial implementation, or defer to filesystem limits?
3. Should `download-artifacts` eventually be deprecated in favor of `export-run`, or kept as lightweight artifact-only download?
