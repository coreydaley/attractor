# Critique: SPRINT-026-CLAUDE-DRAFT

## Overall Assessment

This is a well-structured, implementation-ready sprint draft for “agent + model selection” end-to-end propagation, with strong specificity around API request bodies, call chains, and the (very real) `WebMonitorServer.kt` ergonomics. However, it substantially diverges from the **applied interview refinements** in `SPRINT-026-INTENT.md`, which change the model-catalog approach from “repo `agents.json` + Python + GitHub Action” to “server fetch + SQLite cache + Refresh button”.

## High-Priority Findings

1. **Catalog approach conflicts with the intent’s “MAJOR CHANGE” (must drop `agents.json`/Python/Action)**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:13-16`, `:45-47`, `:54-63`, `:125-142`, `:360-397`  
Reference intent: `docs/sprints/drafts/SPRINT-026-INTENT.md:75-87`  
The Claude draft is built around `agents.json` + `scripts/fetch_models.py` + `.github/workflows/update-agents.yml`. The intent explicitly says this approach was dropped and replaced with server-side per-provider model fetching stored in SQLite, plus a Settings “Refresh models” button and a `/api/settings/fetch-models` endpoint.  
**Fix:** Remove/replace Phases 1, 9, and 10 with:
   - `ModelFetcher.kt` (Direct API only)
   - DB-backed cached model lists (per-provider keys + timestamps)
   - `POST /api/settings/fetch-models` endpoint
   - `/api/agents` built from DB cache + `ModelCatalog.kt` fallback

2. **The proposed Python script requires provider API keys, which contradicts the original “no auth tokens required” constraint**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:367-377`  
Reference intent: `docs/sprints/drafts/SPRINT-026-INTENT.md:41-42`  
Even if the project still wanted a catalog file, the constraint explicitly calls out that the script must work without auth tokens. The draft’s script fetches `/v1/models` endpoints with keys and fails if it can’t contact providers.  
**Fix:** Align with refinements (server-side fetch using configured keys, optional) or explicitly revise constraints/success criteria if “no-auth model discovery” is not feasible.

3. **`/api/agents` behavior is internally inconsistent (filtered vs “returns full JSON as-is”)**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:18-21`, `:61-62`, `:185-190`  
The narrative says `/api/agents` returns a filtered catalog (“filtered by enabled providers”), but Phase 3 tasks say “Returns full catalog JSON as-is”. Both can work, but they lead to different responsibilities (server vs SPA filtering) and different edge-case handling.  
**Fix:** Pick one:
   - Server-filtered: `/api/agents` already respects enabled toggles + execution mode; UI becomes simpler/safer.
   - Client-filtered: endpoint returns the full catalog; UI filters strictly from `appSettings`.

## Medium-Priority Findings

1. **Missing “Refresh models” UX and endpoint from applied refinements**  
Reference: `docs/sprints/drafts/SPRINT-026-INTENT.md:75-82`  
The Claude draft adds Settings catalog URL/upload management (`agents_catalog_url`, `agents_catalog_json`) instead of the refined “per-provider Refresh models” buttons and a dedicated fetch endpoint.  
**Fix:** Replace the Settings “catalog URL/upload” section with:
   - A compact “Model Catalog” card showing cached counts + last refreshed
   - Per-provider “Refresh models” buttons (Direct API only)
   - Clear explanation that CLI subprocess mode uses fallback lists

2. **localStorage persistence is only an open question, but it’s a stated requirement**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:908-909` (Open Questions)  
Reference intent: `docs/sprints/drafts/SPRINT-026-INTENT.md:85-86`  
The intent requires Create to remember last-selected `agentId`/`modelId`. The draft does not include it in phases/tasks.  
**Fix:** Add explicit tasks in the Create/Iterate UI phase for saving/restoring selection, including invalidation when an agent becomes disabled.

3. **Custom agent dropdown behavior is undecided, but the intent specifies it**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:906-907`  
Reference intent: `docs/sprints/drafts/SPRINT-026-INTENT.md:83-84`  
The intent calls for a single disabled option (“Use custom_api_model setting”). The draft leaves this as an open question and models custom as `models: []`.  
**Fix:** Bake the requirement into UI tasks: when `agentId="custom"`, model select shows one disabled option; selection remains “custom + (blank modelId)”.

4. **Override error semantics likely differ from intent edge-case expectations**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:211-217`, `:409-412`  
Reference intent: `docs/sprints/drafts/SPRINT-026-INTENT.md:63-64`  
The draft chooses to throw `ConfigurationError` when an explicit agent is disabled or missing keys. The intent lists “provider disabled after selection” and “invalid model falls through to auto-select” as edge cases, suggesting a more graceful fallback path (or at least a non-crashing UX).  
**Fix:** Decide explicitly:
   - Either return 400 with a clear UI error and keep the selection UI intact, or
   - Warn + fall back to auto-selection (preserving “blank selection behaves as before”).

5. **Bundling `agents.json` into resources introduces build-sync complexity that disappears under the refined approach**  
Reference: `docs/sprints/drafts/SPRINT-026-CLAUDE-DRAFT.md:175-176`, `:914-918`  
If the sprint follows the refined “DB cached live lists + fallback ModelCatalog” approach, there’s no need to ship a repo-root JSON and copy it into `src/main/resources/`.

## Suggested Edits Before Implementation

1. Replace the `agents.json`/Python/Action plan with the refined DB-backed “Refresh models” approach (`ModelFetcher.kt` + `/api/settings/fetch-models` + `/api/agents` built from cache).  
2. Add localStorage persistence tasks (Create + Iterate) as non-optional requirements.  
3. Lock in Custom agent dropdown behavior as specified (“Use custom_api_model setting”).  
4. Clarify `/api/agents` responsibilities (server-filtered vs client-filtered) and align text + tasks.  
5. Decide and document how invalid/disabled overrides behave (fallback vs explicit error).

## Bottom Line

If the sprint were still pursuing a repo-based `agents.json` catalog, this draft is nearly executable as-is. But given the intent’s applied refinements, the highest-value edit is to swap out the catalog subsystem for the DB-cached live-fetch design, then keep the rest of the draft’s strong “plumbing + UI + tests” structure.
