# Critique: SPRINT-003-CLAUDE-DRAFT

## Overall Assessment

Claude's draft is strong on scope clarity and generally matches the intent. It has a good phase breakdown and mostly targets the correct files. The main gaps are around architectural fidelity to current code paths, a few requirement mismatches, and some implementation details that would create avoidable risk.

## High-Priority Issues

1. **Requirement mismatch: import should not imply resumability**
- Open Question #1 says including artifacts enables Resume after import.
- Sprint intent explicitly says imported runs should appear as terminal snapshots and must not auto-re-execute.
- Recommendation: remove Resume implication and state artifacts are for observability parity (logs/stages/checkpoint display), not execution continuation.

2. **`PipelineRegistry.registerRestored(entry: PipelineEntry)` is underspecified vs current model**
- Current reconstruction logic depends on DB-shaped data (`StoredRun`) and checkpoint parsing, not an arbitrary incoming `PipelineEntry.state`.
- Draft text says “reconstructs PipelineState from entry.state data,” which is incompatible with how state is actually rebuilt today.
- Recommendation: make restore API consume `StoredRun` (or equivalent metadata struct) and reuse `loadFromDB()` reconstruction helper logic directly.

3. **Export metadata source is not robust**
- Draft suggests building `pipeline-meta.json` from `PipelineEntry` + `state.pipelineLog`.
- `PipelineEntry` does not contain `createdAt`, `pipelineLog`, or `archived` as first-class fields; those are persisted in `RunStore`.
- Recommendation: fetch canonical metadata from DB (`RunStore.getById`) during export, then merge in runtime-only data only if needed.

4. **Terminal-state handling is inconsistent with intent**
- Intent includes `paused` as exportable terminal state.
- Definition of Done bullet says export valid for completed/failed/cancelled only.
- Recommendation: consistently include `paused` in endpoint validation and DoD.

## Medium-Priority Issues

1. **Import transport ambiguity**
- Architecture says “multipart/form-data or raw zip body,” implementation later assumes raw bytes.
- Recommendation: pick one for Sprint 003. Raw `application/zip` is simpler and aligns with existing lightweight server patterns.

2. **Memory strategy risks large imports**
- Phase 4 says read full request body into byte array.
- This can fail on large artifact bundles.
- Recommendation: stream from request body with `ZipInputStream` into temp files.

3. **Collision behavior on logs directory is weakly defined**
- Draft derives `logs/<logsRootBasename>`, which can collide across imports.
- Recommendation: use deterministic, ID-based target path (`logs/<safePipelineName>-<runId>`) and tie overwrite/delete behavior to `onConflict` semantics.

4. **Risk table mitigation is incomplete for path traversal**
- Security notes mention sanitization, but plan phases do not explicitly list zip-entry normalization checks.
- Recommendation: add explicit task: reject absolute paths and `..` traversal before extraction.

## Low-Priority Notes

1. `insertFull()` naming is fine, but `insertOrReplaceImported(StoredRun)` better communicates scope.
2. Export zip root folder name (`artifacts/`) is acceptable, but `logs/` maps more directly to intent phrasing.
3. “overwrite (default)” is reasonable; consider adding explicit `onConflict=skip` in this sprint for safer operators.

## What Claude’s Draft Does Well

1. Clear round-trip framing and user value.
2. Correctly identifies the three core files needing changes.
3. Explicitly calls out preserving `originalPrompt` and integration with Sprint 002 iteration flow.
4. Includes practical DoD items and manual validation targets.

## Recommended Revision Direction

1. Base export metadata on `RunStore` canonical row, not only in-memory registry fields.
2. Refactor `PipelineRegistry` to share one reconstruction helper used by both startup `loadFromDB()` and import upsert.
3. Lock transport to raw zip request bodies and stream extraction safely.
4. Normalize terminal-state rules (`completed|failed|cancelled|paused`) across architecture, tasks, and DoD.
5. Add explicit zip safety and conflict-mode semantics (`overwrite`/`skip`) as first-class tasks.
