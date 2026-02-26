# Sprint 003 Merge Notes

## Claude Draft Strengths

1. Clear round-trip framing with concrete use cases (share, backup/restore, archive/reload, iterate)
2. Correctly identifies all three core files needing changes
3. Explicit preservation of `originalPrompt` and Sprint 002 integration
4. Practical DoD items and manual round-trip validation targets
5. Zip structure already sensible; `pipeline-meta.json` field list complete

## Codex Draft Strengths

1. Recommends `RunStore.getById()` as a first-class primitive — useful for conflict checks and export
2. Refactors shared reconstruction logic into a private helper in `PipelineRegistry` — prevents drift between startup and import hydration paths
3. Consistent `completed|failed|cancelled|paused` terminal state throughout
4. Explicit `onConflict=skip` query param alongside `overwrite` default
5. `logs/` zip prefix maps more naturally to intent phrasing and filesystem structure
6. Explicit zip path-traversal safety tasks in implementation plan
7. Deterministic logsRoot target path: `logs/<safePipelineName>-<runId>` (not just basename)

## Valid Critiques Accepted

1. **Export metadata from DB, not PipelineEntry**: `PipelineEntry` lacks `createdAt`, `pipelineLog`, `archived` as first-class fields. Export calls `RunStore.getById()` for the canonical row.
2. **`registerRestored()` must consume `StoredRun`, not `PipelineEntry`**: Reconstruction depends on DB-shaped data and checkpoint parsing. Refactor `loadFromDB()` reconstruction logic into a private helper; both startup and import reuse it.
3. **Consistently include `paused` as terminal state**: Exportable states = `completed|failed|cancelled|paused` everywhere in architecture, tasks, and DoD.
4. **Raw zip transport only**: "multipart or raw zip" ambiguity removed. Lock to raw `application/zip` body.
5. **Stream imports, don't buffer**: `ZipInputStream` from request body, extract to temp dir. Avoids OOM on large artifact bundles.
6. **Explicit zip path traversal tasks**: Added as concrete implementation checklist items, not just a security note.
7. **`onConflict=skip` parameter**: Adds operator-safe option; `overwrite` remains default (per user interview).
8. **`logs/` zip prefix**: Changed from `artifacts/` to `logs/` in zip structure for consistency with intent and local filesystem layout.
9. **`insertOrReplaceImported()` naming**: Clearer than `insertFull()` — communicates semantics (upsert, not generic insert).

## Critiques Rejected

- **Import implying resumability**: Claude draft already stated "Does NOT start execution (terminal state only)". This was a misread of the open question; no change needed.

## Interview Refinements Applied

- Full artifacts + metadata (not metadata-only) — confirmed by user
- Overwrite on ID collision (default) with skip as optional — per user answer + Codex suggestion

## Final Decisions

| Decision | Choice | Reason |
|----------|--------|--------|
| Zip artifact prefix | `logs/` | Matches intent phrasing; less confusing than `artifacts/` |
| Method name (RunStore) | `insertOrReplaceImported(StoredRun)` | Clearer semantics than `insertFull` |
| Method name (Registry) | `upsertImported(run: StoredRun)` | Consistent with Codex naming; takes DB-shaped input |
| Registry helper | Extract private `hydrateEntry(run: StoredRun)` | Shared by `loadFromDB()` and `upsertImported()` |
| Export data source | `RunStore.getById()` + merge with in-memory status if needed | DB is canonical for `createdAt`, `pipelineLog`, `archived` |
| Import transport | Raw `application/zip` or `application/octet-stream` body | Simpler; matches server pattern |
| Import memory strategy | Stream via `ZipInputStream` to temp dir | Avoids OOM; aligns with Codex recommendation |
| Conflict modes | `onConflict=overwrite` (default), `onConflict=skip` | Per user + Codex; `skip` is low-cost safety option |
| logsRoot on import | `logs/<fileName-without-ext>-<runId>` | Deterministic, ID-anchored, avoids collisions |
| Terminal states | `completed\|failed\|cancelled\|paused` | Consistent across all phases |
| Zip safety | Explicit task: reject absolute paths and `..` traversal | Security hardening |
