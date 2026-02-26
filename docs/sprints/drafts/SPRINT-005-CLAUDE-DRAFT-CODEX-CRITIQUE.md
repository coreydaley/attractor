# Critique: SPRINT-005-CLAUDE-DRAFT

## Overall Assessment

Strong draft with a clear architecture choice (`pipeline_family_id`) and good breakdown of DB/runner/API/UI work. It is close to implementable, but a few high-impact mismatches with the current codebase need correction before execution.

## High-Priority Findings

1. **`/api/rerun` behavior change is not fully planned end-to-end**
- The draft states re-run should create a new run in the same family, but implementation steps focus mostly on `/api/iterate`.
- Current code path for re-run is in-place (`PipelineRunner.resubmit(id, ...)`).
- Without explicit rerun endpoint + runner refactor steps, re-run will remain destructive/in-place and violate the lineage model.
- **Fix:** Add explicit tasks to replace rerun-in-place with new-run semantics (`rerunFrom(...)`), update response payload, and update frontend handler for rerun result.

2. **References to non-existent endpoints create plan drift**
- Draft calls out updating `POST /api/upload`, but current server uses `POST /api/run`.
- This will cause implementation confusion and missed updates.
- **Fix:** Replace `/api/upload` references with `/api/run` and list all actual affected endpoints (`/api/run`, `/api/rerun`, `/api/iterate`, `/api/import-run`, `/api/export-run`).

3. **Import/export lineage preservation is underspecified against current code behavior**
- Current `/api/import-run` starts a fresh run via `PipelineRunner.submit(...)`; it does not restore a stored run record.
- Draft says “parse familyId from metadata” but does not explicitly state whether import remains “start new run” or shifts to “restore historical run row.”
- **Fix:** Decide and document one model:
  - If import should preserve history exactly: use `insertOrReplaceImported(...)` + `upsertImported(...)`.
  - If import should start fresh execution: set family inheritance rules for new run and clarify metadata use.

4. **Iterate response contract change is incomplete in rollout plan**
- Draft changes `/api/iterate` response from `{id}` to `{newId}` and mentions JS updates, but regression coverage is thin.
- Existing UI flow assumes same-run semantics in multiple places.
- **Fix:** Add explicit acceptance checks for create/iterate mode transitions, selected tab behavior, SSE convergence, and no stale panel state after iterate.

## Medium-Priority Findings

1. **Scope expansion risk: artifact browser endpoints/UI**
- New `/api/run-artifacts` + `/api/run-artifact-file` and modal UI are substantial and not strictly required by sprint intent (which only requires access to artifacts, already partly covered by existing download endpoints).
- **Fix:** Either defer artifact browser to a follow-up sprint or mark it as optional stretch scope; keep Sprint 005 focused on lineage + history.

2. **Legacy-family handling is internally inconsistent in wording**
- Draft says legacy runs with blank family should hide history, but also introduces family query patterns that may need fallback (`family=id`) in app logic.
- **Fix:** Define one consistent rule across DB, API, and UI: blank family means standalone run; treat as family-of-one for logic, hide panel when member count < 2.

3. **No indexing strategy for family lookup**
- `getByFamilyId` will be used frequently in history views.
- **Fix:** Add migration for index: `CREATE INDEX IF NOT EXISTS idx_pipeline_runs_family_created ON pipeline_runs(pipeline_family_id, created_at)`.

## Suggested Edits Before Implementation

1. Add explicit `/api/rerun` new-run migration steps and frontend changes.
2. Correct endpoint naming to current code (`/api/run`, not `/api/upload`).
3. Resolve import model ambiguity and document exact DB/API flow.
4. Reduce artifact-browser scope to optional unless required for this sprint.
5. Add lineage query index and consistent legacy-family fallback language.

## Bottom Line

The draft is directionally correct and well structured. With the rerun endpoint gap, endpoint naming correction, and import model clarification fixed, it becomes execution-ready.
