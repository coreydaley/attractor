# Sprint 026: Agent + Model Selection UI with Dynamic Model Catalog

## Overview

Today, Attractor implicitly chooses a provider + model via `ModelSelection.selectModel()` using a hardcoded priority order and baked-in defaults. Users cannot pick the specific agent/model per request in the Create or Iterate flows, and the model list is compiled into the server binary (`ModelCatalog.kt`), so it only updates when we ship a new release.

This sprint adds an explicit **Agent** + **Model** selection UI to the Create and Iterate views, and threads the selection through both graph generation (DotGenerator) and project execution (codergen nodes). It also replaces “static in-code model lists” with a **dynamic catalog**: when a Direct API provider is enabled/configured, the server can fetch that provider’s current model listing and store it in SQLite. The Settings page gains a “Model Catalog” section with per-provider “Refresh models” buttons, backed by a new API endpoint.

The outcome is a more predictable, controllable workflow: a user can deliberately run the same project on a specific model (or compare two models) without toggling providers in Settings, while still preserving the current “auto-select” behavior when no overrides are chosen.

## Use Cases

1. **Pick a model before generation**: On Create, a user selects “Anthropic → claude-sonnet-4-6” (or similar) before generating DOT, ensuring the generator uses that model.

2. **Change models while iterating**: On Iterate, a user switches from a “fast/cheap” model to a “strong” model for a tricky step without changing global provider toggles.

3. **Reproducible runs**: A user reruns the same graph with the same explicit agent/model to reproduce behavior (or to compare outcomes against another model).

4. **Live model availability**: When providers deprecate or add models, the app can refresh model lists from provider APIs rather than relying on releases.

5. **Safe fallback**: If no explicit selection is made (or the selected model is invalid/unavailable), Attractor falls back to existing `ModelSelection.selectModel()` behavior.

## Architecture

### Data flow

```
Settings (enabled providers, execution mode)
  + optional provider model-cache in DB (JSON strings)
        |
        v
GET /api/agents  ------------------------->  SPA (Create / Iterate dropdowns)
        |                                         |
        |                                         v
        |                                  localStorage persistence
        |
        v
POST /api/dot/generate (or iterate) with agentId/modelId (optional)
        |
        v
DotGenerator -> ModelSelection.selectModel(config, agentId?, modelId?)

Run Project (RunOptions.agentId/modelId optional)
        |
        v
ProjectRunner -> Engine -> CodergenHandler/LlmCodergenBackend
        |
        v
ModelSelection.selectModel(config, agentId?, modelId?) for every LLM call
```

### Model catalog strategy (dynamic, DB-backed)

- Add a small server-side “agent catalog” model (e.g. `AgentInfo` + `ModelInfo`) built at request time.
- For **Direct API** providers that are enabled/configured, allow fetching live model lists:
  - New `ModelFetcher.kt` knows how to query each provider’s model listing endpoint (where available) using the already-configured API key.
  - Store results in SQLite via existing settings mechanism (`store.saveSetting(key, value)`), as JSON strings keyed by provider (e.g. `models_anthropic_json`).
  - `/api/agents` merges DB-cached results with `ModelCatalog.kt` as a fallback when the cache is empty or fetch failed.
- For **CLI subprocess** mode:
  - Do not attempt remote fetches (no reliable auth story); always use fallback lists from `ModelCatalog.kt`.
- For **Custom (OpenAI-compatible)** provider:
  - Do not provide a per-request model list. The model is configured in Settings; the dropdown should show a single disabled option: “Use `custom_api_model` setting”.

### API surface

- `GET /api/agents`
  - Returns all agents relevant to the currently selected execution mode, with models for each agent.
  - The frontend filters agent visibility to only “enabled” providers (matching Settings), and filters model dropdown to the selected agent.
- `POST /api/settings/fetch-models`
  - Accepts a provider id (e.g. `anthropic`, `openai`, `gemini`) and refreshes cached model JSON in SQLite.
  - Returns a status payload (count, lastUpdated, error message if any).
  - Must be non-fatal in UI: failures should be surfaced as a toast/banner without breaking Settings save flows.

### UI requirements (WebMonitorServer.kt SPA)

- Create view:
  - Agent dropdown (enabled agents only; depends on execution mode).
  - Model dropdown filtered to selected agent.
  - Both persist to `localStorage` and restore on load if still valid/enabled.
- Iterate view:
  - Same dropdowns; selection included in iterate request and run request.
- Settings view:
  - “Model Catalog” section:
    - For each Direct API provider section, a “Refresh models” button.
    - Show “last refreshed” time and model count (from cached JSON).
    - (Optional) “Clear cache” link to revert to fallback lists.

## Implementation Plan

### Phase 1: Server-side selection plumbing (~25%)

**Files:**
- `src/main/kotlin/attractor/llm/LlmExecutionConfig.kt` — Modify
- `src/main/kotlin/attractor/llm/ModelSelection.kt` — Modify
- `src/main/kotlin/attractor/web/ProjectRunner.kt` — Modify
- `src/main/kotlin/attractor/web/DotGenerator.kt` — Modify
- `src/main/kotlin/attractor/handlers/CodergenHandler.kt` — Modify
- `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` — Modify

**Tasks:**
- [ ] Add optional `agentId: String?` and `modelId: String?` to the “per-request” execution config (and/or `RunOptions`) in a backward-compatible way.
- [ ] Extend `ModelSelection.selectModel(...)` to accept optional overrides:
  - [ ] If both agentId + modelId are provided and valid for current execution mode, use them.
  - [ ] If only agentId is provided, pick that agent’s default model (from catalog / fallback).
  - [ ] If modelId is invalid/unavailable, fall back to existing auto-selection logic (and log a warning).
- [ ] Thread overrides into:
  - [ ] Dot generation and iteration (`DotGenerator` entrypoints)
  - [ ] Project execution (`ProjectRunner` -> codergen handler stack)
- [ ] Ensure rerun / legacy runs behave identically when overrides are null/blank.

---

### Phase 2: Dynamic catalog + fetch endpoint (~35%)

**Files:**
- `src/main/kotlin/attractor/llm/ModelCatalog.kt` — Modify (fallback only)
- `src/main/kotlin/attractor/llm/ModelFetcher.kt` — Create
- `src/main/kotlin/attractor/llm/AgentCatalog.kt` — Create (or similar)
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify
- `src/main/kotlin/attractor/db/RunStore.kt` / `src/main/kotlin/attractor/db/SqliteRunStore.kt` — Modify (only if needed to support “lastUpdated” metadata cleanly)

**Tasks:**
- [ ] Define a stable agent/model catalog structure returned by `GET /api/agents` (ids, labels, models).
- [ ] Implement DB-backed model cache:
  - [ ] Settings keys per provider for cached JSON + last refresh timestamp (e.g. `models_anthropic_json`, `models_anthropic_refreshed_at`).
  - [ ] Parse cached JSON safely; ignore invalid cache and fall back to `ModelCatalog.kt`.
- [ ] Implement `ModelFetcher`:
  - [ ] Anthropic: fetch live models where supported by API.
  - [ ] OpenAI: fetch live models where supported by API.
  - [ ] Gemini: fetch live models where supported by API.
  - [ ] Each fetch must be timeout-bounded and fail gracefully.
- [ ] Add `POST /api/settings/fetch-models`:
  - [ ] Validates provider id + checks provider enabled/configured.
  - [ ] Calls `ModelFetcher`, stores JSON + timestamps, returns status payload.
- [ ] Add `GET /api/agents`:
  - [ ] Builds agent list based on execution mode.
  - [ ] Includes only models relevant to each agent and uses cached lists when available.

---

### Phase 3: SPA UI additions (~30%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Create view:
  - [ ] Add Agent + Model selectors near other generation inputs (matching existing UI patterns).
  - [ ] Load agents via `GET /api/agents`.
  - [ ] Persist selection in `localStorage` (include execution mode in the key to avoid cross-mode confusion).
- [ ] Iterate view:
  - [ ] Add the same selectors; include `agentId`/`modelId` in iterate requests and run requests.
- [ ] Settings view:
  - [ ] Add “Model Catalog” section under provider toggles.
  - [ ] Add per-provider “Refresh models” buttons (Direct API mode only).
  - [ ] Show last refreshed + model count from the cached values.
- [ ] UX edge cases:
  - [ ] If selected agent becomes disabled, reset selection to blank (auto).
  - [ ] If selected model is no longer present, reset model to blank (agent default/auto) and show a non-blocking warning.

---

### Phase 4: Tests + documentation (~10%)

**Files:**
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — Modify
- `src/test/kotlin/attractor/llm/AgentCatalogTest.kt` — Create
- `docs/site/content/web-app.md` (or relevant page) — Modify
- `docs/site/content/rest-api.md` (or relevant page) — Modify

**Tasks:**
- [ ] Add unit tests for explicit override selection paths:
  - [ ] valid agent+model
  - [ ] valid agent with blank model (default)
  - [ ] invalid model falls back to auto-selection
- [ ] Add tests for agent catalog construction (cache parsing + fallback).
- [ ] Update docs site to describe:
  - [ ] Create/Iterate agent+model selectors
  - [ ] Settings “Refresh models” behavior and limitations (CLI mode uses fallback lists)
  - [ ] `/api/agents` and `/api/settings/fetch-models` endpoints

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/llm/ModelSelection.kt` | Modify | Accept agent/model overrides; validate against catalog; preserve auto-select fallback |
| `src/main/kotlin/attractor/llm/AgentCatalog.kt` | Create | Build agent/model list from DB cache + fallback lists |
| `src/main/kotlin/attractor/llm/ModelFetcher.kt` | Create | Fetch live model lists for Direct API providers; timeout + error handling |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Add `/api/agents` and `/api/settings/fetch-models` endpoints |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Add Create/Iterate selectors + Settings refresh UI |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Pass selection through generation/iteration requests |
| `src/main/kotlin/attractor/web/ProjectRunner.kt` | Modify | Carry agent/model selection through execution via `RunOptions` |
| `src/main/kotlin/attractor/handlers/*.kt` | Modify | Ensure codergen execution uses per-run overrides |
| `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` | Modify | Cover override behavior and fallback correctness |
| `src/test/kotlin/attractor/llm/AgentCatalogTest.kt` | Create | Validate cache parsing and `/api/agents` data construction |
| `docs/site/content/*.md` | Modify | Document UI + settings + new API endpoints |

## Definition of Done

- [ ] Create and Iterate pages show Agent + Model selectors populated from `/api/agents`.
- [ ] Agent dropdown shows only enabled providers (respecting execution mode).
- [ ] Model dropdown filters to selected agent and reflects refreshed model caches when present.
- [ ] Selected agent/model is used for BOTH DOT generation and project execution codergen.
- [ ] If no selection is made, behavior matches current auto-selection logic.
- [ ] Settings page supports per-provider “Refresh models” (Direct API) and shows status.
- [ ] Unit tests added for override paths and catalog construction.
- [ ] `make test` passes.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Provider model listing APIs differ / change | Medium | Medium | Keep fetchers small, timeout-bounded; fall back to `ModelCatalog.kt` when fetch fails |
| Threading selection through handler stack misses a call site | Medium | High | Trace all LLM invocations (DotGenerator + codergen); add logging that prints final provider/model per call |
| UI complexity in `WebMonitorServer.kt` | Medium | Medium | Follow existing SPA patterns; keep selectors isolated; add small helper functions for state + localStorage |
| Cache schema drift / invalid JSON in settings | Low | Medium | Parse defensively; ignore invalid cache; keep “clear cache” option |

## Security Considerations

- Ensure model fetch requests do not log API keys or full auth headers.
- Apply strict timeouts and size limits when reading provider responses to avoid resource exhaustion.
- Only allow `fetch-models` for known providers; do not allow arbitrary URLs.
- Treat cached JSON as untrusted input: validate structure before using.

## Dependencies

- None required, but aligns with existing Settings architecture (`store.getSetting` / `store.saveSetting`) and the SPA patterns in `WebMonitorServer.kt`.

## Open Questions

1. What exact provider ids should we standardize on for the UI/API (`anthropic`, `openai`, `gemini`, `copilot`, `custom`) to match existing config keys?
2. Where should “last refreshed” metadata live: separate settings keys, or a small structured JSON blob per provider?
3. Should overrides be stored per-project (persisted with the run) or treated purely as per-request UI state (localStorage only)?
4. If a user picks a model that later disappears, should we silently fall back or present a blocking warning before run?
