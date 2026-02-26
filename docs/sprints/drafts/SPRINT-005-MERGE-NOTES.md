# Sprint 005 Merge Notes

## Claude Draft Strengths
- Clear justification of `pipeline_family_id` (flat) over `parent_run_id` (linked list) approach — correct choice
- Detailed CSS/JS specifications for Version History panel and Artifact modal
- New artifact-serving endpoints (`/api/run-artifacts`, `/api/run-artifact-file`) with security path-traversal enforcement — directly addresses the seed prompt's "view artifacts" requirement
- Thorough Definition of Done covering all integration points
- Strong security considerations (canonicalFile path enforcement)

## Codex Draft Strengths
- Catches the `/api/upload` → `/api/run` endpoint naming discrepancy (Claude's draft had the wrong name)
- Recommends DB index `CREATE INDEX IF NOT EXISTS idx_pipeline_runs_family_created ON pipeline_runs(pipeline_family_id, created_at)` — good addition
- Concise architecture with explicit `ifBlank { run.id }` fallback for legacy rows in `hydrateEntry()`
- Flags import/export model underspecification — valid concern
- Notes `pipelineFamilyId` should be in SSE snapshot JSON (`allPipelinesJson()`)

## Valid Critiques Accepted

1. **Endpoint naming**: Replace all `/api/upload` references with `/api/run` in the final sprint (actual endpoint confirmed via code inspection)
2. **DB index**: Add `CREATE INDEX IF NOT EXISTS idx_pipeline_runs_family_created ON pipeline_runs(pipeline_family_id, created_at)` to migrations
3. **Legacy family fallback consistency**: Define one rule everywhere: if `familyId.isBlank()` → treat as `run.id` in `hydrateEntry()` and API response; hide Version History panel when < 2 family members
4. **Import model clarity**: Current `/api/import-run` uses `insertOrReplaceImported()` + `upsertImported()` (Sprint 003 pattern) — it DOES preserve all DB fields including the new `familyId`. The model is: import preserves `familyId` from `pipeline-meta.json`; if absent, the imported run bootstraps as its own family.
5. **Iterate response contract**: Explicitly enumerate all frontend locations where `{id}` must change to `{newId}` (JS `runIterated()` function)

## Critiques Rejected (with reasoning)

1. **Re-run behavior change**: Codex proposed re-run should also create a new family-linked run. **Rejected** — user explicitly confirmed during interview that Re-run should keep the same ID (existing behavior unchanged). Only Iterate creates new runs.

2. **Defer artifact browser**: Codex suggested the artifact browser (`/api/run-artifacts`, `/api/run-artifact-file`) is scope expansion. **Rejected** — the seed prompt explicitly says "should be able to be viewed including the results and the artifacts that were generated." The artifact browser directly fulfills this requirement. It stays in scope.

## Interview Refinements Applied

- Re-run: NO behavior change — `resubmit()` stays, uses same run ID. Remove all re-run family-change tasks from Phase 2.
- Iterate: new run per iterate, old run preserved. User confirmed.
- Versioning model: `pipeline_family_id` flat grouping. User confirmed.

## Final Decisions

1. `pipeline_family_id` flat approach confirmed; no `parent_run_id`
2. `POST /api/run` is the upload/submit endpoint (not `/api/upload`)
3. Re-run keeps same ID; only Iterate creates new family members
4. Artifact browser stays in scope (core requirement from seed)
5. DB index added for family lookup performance
6. Legacy runs (blank `familyId`): treated as family-of-one; Version History panel hidden
7. Import: preserves `familyId` from metadata via existing `insertOrReplaceImported()` path
8. Artifact browser is an optional stretch goal only if time is tight — mark clearly
