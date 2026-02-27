# Critique: SPRINT-010-CLAUDE-DRAFT

## Overall Assessment

The draft is ambitious and largely aligned with the sprint intent, but it has a few high-impact implementation mismatches with the current codebase and one major coverage gap against the stated success criteria. With one tightening pass, it can become an executable sprint plan.

## High-Priority Findings

1. **`PipelineRunner` integration plan is not implementable as written**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:210`, `:232-239`, `:258-259`, `:299`, `:313-314`, `:339`, `:434`  
The draft treats `PipelineRunner` like an injected instance (`runner.submit(...)`, `runner.resubmit(...)`, `runner.resumePipeline(...)`) and proposes passing a `runner` field through `WebMonitorServer`. In this codebase, `PipelineRunner` is an `object` with static-style methods and call signatures that require `registry`, `store`, and `onUpdate` callbacks.  
**Fix:** Plan around direct `PipelineRunner.<method>(..., registry, store) { broadcastUpdate() }` calls (or add a deliberate adapter layer), not constructor injection of a runner instance.

2. **“WebMonitorServer unchanged” contradicts the SSE and router coupling work**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:56-61`, `:18-21`, `:537-555`, `:592-593`  
The architecture claims unchanged internal logic and only one added context, but later phases require exposing/changing SSE internals (`sseClients`, `broadcastUpdate`, extra client lists). That is non-trivial server refactoring.  
**Fix:** Acknowledge `WebMonitorServer.kt` as a primary modification target from the start and scope explicit extraction/refactor tasks to keep risk controlled.

3. **Endpoint contract diverges from intent mapping (`run` vs `rerun`) and adds unplanned surface**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:78`, `:296`, `:603`, `:105`, `:478-481`, `:627`  
Intent maps existing `/api/rerun` to `POST /api/v1/pipelines/{id}/rerun`, but the draft uses `POST /api/v1/pipelines/{id}/run`. It also adds `GET /api/v1/dot/fix/stream`, which is not in the intent’s endpoint map.  
**Fix:** Use `.../{id}/rerun` for parity and keep endpoint additions strictly tied to intent unless explicitly justified as a scope extension.

4. **Success criterion requires a complete API spec document, but no doc file is planned**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:200-593` (files/tasks), and Sprint intent success criteria item 1  
The implementation plan creates code/tests but not the required standalone API specification artifact.  
**Fix:** Add a dedicated deliverable such as `docs/api/rest-v1.md` with request/response schemas and error codes.

5. **Test plan is too narrow for “all endpoints have integration tests”**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:559-584`, `:645`  
The plan lists 14 test cases and omits major endpoint families (artifacts download/file path checks, import/export, SSE routes, family/stages, most lifecycle actions, stream routes). This does not meet the sprint’s stated verification bar.  
**Fix:** Expand to endpoint-family integration coverage with both happy-path and key failure-path tests, especially SSE and artifact security cases.

## Medium-Priority Findings

1. **Import format description is inconsistent with current behavior**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:66`, `:161-162`  
The draft calls import “multipart”/“same as existing,” but current `/api/import-run` reads raw ZIP request bytes via `ZipInputStream` (not multipart form parsing).  
**Fix:** Specify raw `application/zip` body for import unless multipart support is intentionally added.

2. **SSE single-pipeline filtering approach is underspecified for current broadcast model**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:544-555`  
Current SSE broadcasting emits full snapshot payloads; there is no per-event pipeline-id channel. Filtering server-side by pipeline ID needs either payload parsing/rebuild on each broadcast or a new event-distribution seam.  
**Fix:** Add an explicit design choice: either filtered per-client payload generation (with performance guardrails) or event-level fanout plumbing changes.

3. **`ModelCatalog` uncertainty is stale**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:521-523`, `:657`  
`ModelCatalog` already exposes `listModels(provider: String? = null)`. The risk entry suggests this might not exist.  
**Fix:** Remove that uncertainty and define the exact response shape derived from `ModelInfo`.

4. **“All JSON bodies size-limited via existing executor config” is not accurate**  
Reference: `docs/sprints/drafts/SPRINT-010-CLAUDE-DRAFT.md:664`  
Thread-pool configuration does not enforce request body size limits.  
**Fix:** Either add explicit body-size checks in request parsing helpers or remove this claim.

## Suggested Edits Before Implementation

1. Rework route handler call sites to the actual `PipelineRunner`/`PipelineRegistry` signatures used today.
2. Update architecture text to explicitly include `WebMonitorServer` refactoring scope (SSE + shared helpers), not “unchanged internals.”
3. Align endpoint names exactly with intent (`rerun`) and trim unplanned additions (`dot/fix/stream`) unless intentionally approved.
4. Add a concrete API spec document deliverable (`docs/api/rest-v1.md`) to satisfy success criteria.
5. Expand integration tests to cover all endpoint families, including SSE and artifact/import/export security cases.
6. Correct import request format documentation and remove stale `ModelCatalog` uncertainty.

## Bottom Line

This draft has a strong structure and good REST instincts, but it currently mixes several non-trivial contract mismatches with incomplete verification scope. Once the implementation assumptions are corrected and endpoint/test scope is tightened to the intent, it should be sprint-ready.
