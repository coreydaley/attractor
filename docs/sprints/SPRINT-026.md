# Sprint 026: Agent & Model Selection UI with Dynamic Model Catalog

## Overview

Today, Attractor auto-selects a provider and model via a hardcoded priority list in
`ModelSelection.kt`. The user has no say: as long as Anthropic is enabled and a key is present,
every DOT generation and every project run uses Claude Sonnet regardless of what the user might
prefer. The model list is baked into the JVM binary and only updates when a new release ships.

This sprint fixes both problems. It introduces explicit **Agent + Model dropdowns** on the Create
and Iterate pages, filtered to currently enabled providers and the current execution mode. The
selected agent and model flow end-to-end: through DOT generation, through `RunOptions`, through
every `codergen` stage of the project run.

Rather than shipping a static catalog file, the app fetches live model lists directly from each
Direct API provider using the already-configured API key. Results are cached in SQLite and
surfaced via a new `/api/agents` endpoint. Each provider section in Settings gains a "Refresh
models" button so the list stays current without a code release. CLI subprocess mode always falls
back to the hardcoded `ModelCatalog.kt` lists (no reliable auth story for CLI tools).

Selecting "Auto-detect" for Agent or Model preserves the existing priority-order auto-selection
behavior, so no existing workflows break.

## Use Cases

1. **Pick a reasoning model for a complex project**: User enables Anthropic in Direct API mode,
   clicks "Refresh models", selects Agent "Anthropic" and Model "claude-opus-4-6" on the Create
   page. Both DOT generation and all project run stages use that model.

2. **Use a cheaper model for quick iteration**: On the Iterate view, user switches to
   "claude-haiku-4-5-20251001" to test a DOT change cheaply, then switches back to Sonnet for
   the final run.

3. **Compare models on the same project**: User runs a project twice — once with "gpt-4.1", once
   with "o3" — to compare outputs without changing global Settings.

4. **Live model availability**: Provider releases a new model. User clicks "Refresh models" next
   to Anthropic in Settings. The new model appears in the dropdown immediately.

5. **Auto-detect = existing behavior**: User leaves both dropdowns at "Auto-detect". Behavior is
   identical to pre-Sprint-026.

## Architecture

```
Settings: enabled providers, execution mode, API keys
          |
          v
ModelFetcher.kt  ─── Direct API mode only ──>  provider /models API
          |                                            |
          v                                            v
   SQLite DB:                                  models_{provider}_json
   models_{provider}_fetched_at                       |
          |                                            |
          └──────────────────┬─────────────────────────┘
                             v
                     AgentCatalog.kt
                  (DB cache + ModelCatalog fallback)
                             |
                             v
                   GET /api/agents   (server-filtered: enabled + current mode)
                             |
                             v
                     SPA dropdowns (Create / Iterate)
                          localStorage persistence (scoped by execution mode)
                             |
                  ┌──────────┴──────────────────────┐
                  v                                 v
    /api/generate/stream { agentId, modelId }  /api/run { agentId, modelId }
                  |                                 |
                  v                                 v
         DotGenerator(agentId, modelId)    RunOptions(agentId, modelId)
                  |                                 |
                  v                                 v
     ModelSelection.selectModel(...)       ProjectRunner → Engine
        (explicit override path)              → LlmCodergenBackend(runAgentId, runModelId)
                                                → node.llmModel.ifEmpty { runModelId }
                                                  node.llmProvider.ifEmpty { runAgentId }

Settings "Model Catalog" section:
  Per Direct API provider:
    "Refresh models" button → POST /api/settings/fetch-models { provider }
    Shows: "N models · fetched X ago"
  CLI mode providers:
    Shows: "Using built-in model list"
```

### DB keys for model cache

| Key | Value |
|-----|-------|
| `models_anthropic_json` | JSON array of `{id, displayName}` objects |
| `models_anthropic_fetched_at` | ISO-8601 timestamp |
| `models_openai_json` | JSON array |
| `models_openai_fetched_at` | ISO-8601 timestamp |
| `models_gemini_json` | JSON array |
| `models_gemini_fetched_at` | ISO-8601 timestamp |

### Provider model list endpoints

| Provider | Direct API endpoint | Auth header |
|----------|--------------------|----|
| Anthropic | `GET https://api.anthropic.com/v1/models` | `x-api-key: {key}` |
| OpenAI | `GET https://api.openai.com/v1/models` | `Authorization: Bearer {key}` |
| Gemini | `GET https://generativelanguage.googleapis.com/v1beta/models?key={key}` | query param |
| Copilot | No API — hardcoded: `[{id:"copilot", displayName:"Copilot (auto)"}]` | — |
| Custom | No API — disabled option: "Use custom_api_model setting" | — |

## Implementation Plan

### Phase 1: `ModelFetcher.kt` + `AgentCatalog.kt` (~15%)

**Files:**
- `src/main/kotlin/attractor/llm/ModelFetcher.kt` — Create
- `src/main/kotlin/attractor/llm/AgentCatalog.kt` — Create

**Tasks:**

**ModelFetcher.kt:**
- [ ] Implement `object ModelFetcher` with `fetchModels(provider: String, store: RunStore): FetchResult`:
  ```kotlin
  data class FetchResult(
      val models: List<AgentModelInfo>,
      val error: String? = null
  )
  data class AgentModelInfo(val id: String, val displayName: String)
  ```
- [ ] `fetchAnthropicModels(apiKey: String): List<AgentModelInfo>` — GET `https://api.anthropic.com/v1/models`, header `x-api-key: {apiKey}`, 10s timeout. Filter to `id` values containing "claude". Map `id` → displayName using heuristic (title-case id). On error, throw.
- [ ] `fetchOpenAIModels(apiKey: String): List<AgentModelInfo>` — GET `https://api.openai.com/v1/models`, header `Authorization: Bearer {apiKey}`, 10s timeout. Filter to ids starting with `gpt-`, `o1`, `o3`, `o4` (exclude embedding, whisper, tts, dall-e, etc.). On error, throw.
- [ ] `fetchGeminiModels(apiKey: String): List<AgentModelInfo>` — GET `https://generativelanguage.googleapis.com/v1beta/models?key={apiKey}`, 10s timeout. Filter to `name` values that contain "gemini" and support `generateContent`. Strip `models/` prefix from name. On error, throw.
- [ ] `fetchModels(provider, store)` dispatches to the right fetch method, stores JSON + timestamp in DB on success, returns `FetchResult`. On error, stores no DB update, returns `FetchResult(models=emptyList(), error=message)`.
- [ ] Use `java.net.HttpURLConnection` (no new dependencies).

**AgentCatalog.kt:**
- [ ] Define:
  ```kotlin
  data class AgentModelInfo(val id: String, val displayName: String)
  data class AgentEntry(
      val id: String,
      val displayName: String,
      val directApiModels: List<AgentModelInfo>?,  // null = not supported
      val cliModels: List<AgentModelInfo>?          // null = not supported
  )
  ```
- [ ] Implement `object AgentCatalog`:
  - `fun buildCatalog(store: RunStore, env: Map<String, String> = System.getenv()): List<AgentEntry>`
  - For each provider (anthropic, openai, gemini): try to read `models_{provider}_json` from DB → parse JSON → if empty/invalid, fall back to `ModelCatalog.listModels(provider)`.
  - Copilot: always returns `directApiModels = null`, `cliModels = listOf(AgentModelInfo("copilot", "Copilot (auto)"))`.
  - Custom: always returns `directApiModels = listOf()` (empty = UI shows "Use custom_api_model setting"), `cliModels = null`.
  - Parse JSON manually: the DB stores `[{"id":"...","displayName":"..."},...]` arrays.

---

### Phase 2: `/api/agents` + `/api/settings/fetch-models` endpoints (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**

**`GET /api/agents`:**
- [ ] Add `httpServer.createContext("/api/agents")` handler (GET only).
- [ ] Build full catalog via `AgentCatalog.buildCatalog(store)`.
- [ ] Read current execution mode from DB (`store.getSetting("execution_mode")`).
- [ ] Filter catalog to agents that are: (a) enabled in provider toggles, AND (b) have models for the current mode (`directApiModels != null` for API mode; `cliModels != null` for CLI mode).
- [ ] Serialize to JSON:
  ```json
  {
    "executionMode": "api",
    "agents": [
      {
        "id": "anthropic",
        "displayName": "Anthropic",
        "models": [{ "id": "claude-sonnet-4-6", "displayName": "Claude Sonnet 4.6" }, ...]
      }
    ]
  }
  ```
- [ ] Return 200 with `Content-Type: application/json`. On error: `{"agents":[]}` with 200 (never 500 — the UI must always get a valid response).

**`POST /api/settings/fetch-models`:**
- [ ] Add handler for `POST /api/settings/fetch-models`.
- [ ] Parse body: `provider` field (must be one of `anthropic`, `openai`, `gemini`).
- [ ] Validate: provider must be enabled and execution mode must be `api` (CLI mode can't fetch).
- [ ] Call `ModelFetcher.fetchModels(provider, store)`.
- [ ] If success: return `{"ok":true,"count":N,"fetchedAt":"..."}`.
- [ ] If error: return `{"ok":false,"error":"..."}` with 200 status (not 500 — the UI shows a non-blocking warning).

---

### Phase 3: `ModelSelection` + `RunOptions` + `DotGenerator` + `LlmCodergenBackend` (~15%)

**Files:**
- `src/main/kotlin/attractor/llm/ModelSelection.kt` — Modify
- `src/main/kotlin/attractor/web/ProjectRunner.kt` — Modify
- `src/main/kotlin/attractor/web/DotGenerator.kt` — Modify
- `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` — Modify

**Tasks:**

**ModelSelection.kt:**
- [ ] Add `agentId: String = ""` and `modelId: String = ""` params to `selectModel()`:
  ```kotlin
  fun selectModel(
      config: LlmExecutionConfig,
      env: Map<String, String> = System.getenv(),
      agentId: String = "",
      modelId: String = ""
  ): Pair<String, String>
  ```
- [ ] Override path (when `agentId` is non-blank):
  - Validate that the provider is enabled in `config`; if not, throw `ConfigurationError`.
  - In API mode: validate that the provider has an API key; if not, throw `ConfigurationError`.
  - Return `agentId to modelId.ifEmpty { defaultModelForAgent(agentId) }`.
- [ ] If only `modelId` is provided (agentId blank): auto-select agent as before, substitute `modelId` as the model.
- [ ] If stale/unknown `modelId` provided alongside a valid `agentId`: use it as-is (pass through to the adapter — don't validate model IDs server-side, provider API will reject bad ones).
- [ ] Add `private fun defaultModelForAgent(agentId: String): String` mapping to existing defaults.

**RunOptions.kt:**
- [ ] Add `agentId: String = ""` and `modelId: String = ""` to `RunOptions` data class.
- [ ] In `/api/run` body parsing (WebMonitorServer.kt): extract `agentId`/`modelId` and pass to `RunOptions(simulate, autoApprove, agentId, modelId)`.

**LlmCodergenBackend.kt:**
- [ ] Add `private val runAgentId: String = ""` and `private val runModelId: String = ""` to constructor.
- [ ] In `ProjectRunner.runProject()`, change:
  ```kotlin
  LlmCodergenBackend(client)
  ```
  to:
  ```kotlin
  LlmCodergenBackend(client, options.agentId, options.modelId)
  ```
- [ ] In `run()`, change resolution to:
  ```kotlin
  val model = node.llmModel.ifEmpty { runModelId }.ifEmpty { DEFAULT_MODEL }
  val provider = node.llmProvider.ifEmpty { runAgentId }.ifEmpty { null }
  ```

**DotGenerator.kt:**
- [ ] Add `agentId: String = ""` and `modelId: String = ""` to `generateStream()`, `generate()`, `fixStream()`, `describeStream()`.
- [ ] Replace `ModelSelection.selectModel(cfg)` with `ModelSelection.selectModel(cfg, agentId = agentId, modelId = modelId)` in each.
- [ ] `iterateStream()` delegates to `generateStream()` — pass through the params.

---

### Phase 4: API endpoint body parsing for `agentId`/`modelId` (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] `/api/generate`: extract `agentId`/`modelId` from body; pass to `dotGenerator.generate(prompt, agentId, modelId)`.
- [ ] `/api/generate/stream`: extract and pass to `dotGenerator.generateStream(prompt, agentId, modelId) { ... }`.
- [ ] `/api/describe-dot/stream`: extract and pass to `dotGenerator.describeStream(dotSource, agentId, modelId) { ... }`.
- [ ] `/api/fix-dot`: extract and pass to `dotGenerator.fixStream(...)`.
- [ ] `/api/run`: extract and pass to `RunOptions(simulate, autoApprove, agentId, modelId)`.
- [ ] `/api/iterate/stream`: extract and pass to `dotGenerator.iterateStream(baseDot, changes, agentId, modelId) { ... }`.
- [ ] `/api/iterate`: extract and pass to `sourceEntry.options.copy(agentId = agentId, modelId = modelId)`.

---

### Phase 5: Settings UI — "Model Catalog" section with Refresh buttons (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (HTML + JS)

**Tasks:**

**HTML** (in `viewSettings` card, after the Providers section):
- [ ] Add a "Model Catalog" setting section:
  ```html
  <!-- Model Catalog -->
  <div style="padding: 12px 0 4px 0;">
    <div class="setting-label" style="margin-bottom:4px;">Model Catalog</div>
    <div class="setting-desc" style="margin-bottom:12px;">
      Fetch available models from each provider. In CLI subprocess mode, built-in model lists are used.
    </div>
    <div id="modelCatalogRows"></div>
  </div>
  ```
- [ ] Render one row per provider via JS: provider name + model count + last fetched time + "Refresh" button (Direct API mode only) or "Built-in list" label (CLI mode).

**JS:**
- [ ] `renderModelCatalogRows()` — reads `appSettings` for each provider's `models_{p}_json` / `models_{p}_fetched_at`, renders a row per enabled provider.
- [ ] Each row: `<span>[N models · fetched X ago]</span> <button onclick="refreshModels('anthropic')">Refresh</button>` (or "Built-in list" if CLI mode).
- [ ] `refreshModels(provider)` — shows a spinner, calls `POST /api/settings/fetch-models {provider}`, on success re-renders the row with new count/time, on error shows a non-blocking inline error message (not an alert).
- [ ] When execution mode changes (`setExecutionMode()`): call `renderModelCatalogRows()` to update which rows show Refresh vs Built-in.
- [ ] Call `renderModelCatalogRows()` inside `loadSettings()`.

---

### Phase 6: Create/Iterate UI — Agent & Model dropdowns (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (HTML + JS)

**Tasks:**

**HTML** (in the Create view `create-section`, between the textarea and the options checkboxes row):
- [ ] Add Agent + Model selector row:
  ```html
  <div id="agentModelRow" style="display:flex; gap:12px; align-items:flex-end; margin-top:10px;">
    <div style="flex:1;">
      <label style="font-size:0.8rem; color:var(--text-muted); display:block; margin-bottom:4px;">Agent</label>
      <select id="agentSelect"
        style="width:100%; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem;"
        onchange="onAgentChange()">
        <option value="">Auto-detect</option>
      </select>
    </div>
    <div style="flex:1;">
      <label style="font-size:0.8rem; color:var(--text-muted); display:block; margin-bottom:4px;">Model</label>
      <select id="modelSelect"
        style="width:100%; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem;">
        <option value="">Auto-detect</option>
      </select>
    </div>
  </div>
  ```

**JS:**
- [ ] `var agentCatalogData = null;` — cached `/api/agents` response.
- [ ] `loadAgentCatalog()` — fetches `/api/agents`, stores in `agentCatalogData`, calls `populateAgentDropdown()`. Called inside `loadSettings()`.
- [ ] `populateAgentDropdown()` — clears options, adds "Auto-detect" first, then one `<option>` per agent from `agentCatalogData.agents`. Restores localStorage selection if the agent is still present.
- [ ] `onAgentChange()` — repopulates model dropdown. If "custom" selected: shows single disabled option "Use custom_api_model setting". Else: "Auto-detect" + one option per model. Restores localStorage model selection if still present.
- [ ] `getSelectedAgent()` / `getSelectedModel()` — return current dropdown values (empty string = auto-detect).
- [ ] `saveAgentModelToStorage()` — writes `attractor-agent-{mode}` and `attractor-model-{mode}` to localStorage (scoped by current execution mode).
- [ ] Call `saveAgentModelToStorage()` in `onAgentChange()` and when model dropdown changes.
- [ ] Update `triggerGenerate()` to include `agentId: getSelectedAgent(), modelId: getSelectedModel()` in the request body.
- [ ] Update `runGenerated()` to include `agentId: getSelectedAgent(), modelId: getSelectedModel()` in the `/api/run` request body.
- [ ] Update iterate stream call to include agent+model values.
- [ ] When `setExecutionMode()` is called: call `loadAgentCatalog()` (re-fetch with new mode) and reset dropdowns.
- [ ] When a provider is disabled in Settings: if that provider is currently selected in the agent dropdown, reset the dropdown to "Auto-detect" and clear localStorage.
- [ ] `resetCreatePage()`: reset both dropdowns to "Auto-detect" (clear value, not localStorage).

---

### Phase 7: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — Modify
- `src/test/kotlin/attractor/llm/AgentCatalogTest.kt` — Create

**Tasks:**

**ModelSelectionTest additions:**
- [ ] `"explicit agentId + modelId returns that pair (openai)"` — anthropic+openai enabled, OPENAI_API_KEY set, agentId="openai", modelId="gpt-4.1" → ("openai", "gpt-4.1").
- [ ] `"explicit agentId with disabled provider throws ConfigurationError"` — anthropic disabled, agentId="anthropic" → ConfigurationError.
- [ ] `"explicit agentId no key in API mode throws ConfigurationError"` — openai enabled, no OPENAI_API_KEY, agentId="openai" → ConfigurationError.
- [ ] `"blank modelId with explicit agentId uses default model"` — agentId="gemini", modelId="" → ("gemini", defaultModelForAgent("gemini")).
- [ ] `"modelId-only hint applied on top of auto-selected agent"` — anthropic enabled with key, modelId="claude-haiku-4-5-20251001" → ("anthropic", "claude-haiku-4-5-20251001").

**AgentCatalogTest:**
- [ ] `"buildCatalog returns anthropic models from DB when cache is present"` — store has `models_anthropic_json` = valid JSON; `buildCatalog()` returns anthropic entry with those models.
- [ ] `"buildCatalog falls back to ModelCatalog when DB key is absent"` — empty store; anthropic entry models match `ModelCatalog.listModels("anthropic")`.
- [ ] `"buildCatalog falls back to ModelCatalog when DB JSON is invalid"` — malformed JSON in DB; no exception; falls back to hardcoded list.
- [ ] `"copilot entry always has no directApiModels"` — copilot.directApiModels is null.
- [ ] `"custom entry directApiModels is empty list (not null)"` — custom.directApiModels is empty.

---

### Phase 8: Documentation (~5%)

**Files:**
- `docs-site/content/web-app.md` — Modify
- `README.md` — Modify

**Tasks:**
- [ ] **`web-app.md`**: Add "Agent & Model Selection" section:
  - Where the dropdowns appear (Create page, Iterate view)
  - "Auto-detect" behavior (falls through to existing priority order)
  - That selection applies to both graph generation and project execution
  - localStorage persistence across page reloads
- [ ] **`web-app.md`**: Add "Model Catalog (Settings)" subsection:
  - Per-provider Refresh buttons (Direct API mode only)
  - CLI subprocess mode always uses built-in model list
  - Custom agent uses `custom_api_model` setting
- [ ] **`README.md`**: Add brief mention of agent/model selection feature.

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/llm/ModelFetcher.kt` | Create | Queries provider model listing APIs; stores results in DB |
| `src/main/kotlin/attractor/llm/AgentCatalog.kt` | Create | Builds agent+model catalog from DB cache + ModelCatalog fallback |
| `src/main/kotlin/attractor/llm/ModelSelection.kt` | Modify | Accept explicit `agentId`/`modelId` override params |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Accept `agentId`/`modelId` in all generation methods |
| `src/main/kotlin/attractor/web/ProjectRunner.kt` | Modify | Add `agentId`/`modelId` to `RunOptions`; pass to backend |
| `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` | Modify | Add run-level `runAgentId`/`runModelId` constructor params |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/agents`, `/api/settings/fetch-models`, Settings UI, Create/Iterate dropdowns |
| `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` | Modify | New tests for explicit override behavior |
| `src/test/kotlin/attractor/llm/AgentCatalogTest.kt` | Create | Tests for catalog construction from DB cache + fallback |
| `docs-site/content/web-app.md` | Modify | Document agent/model selection UI and model catalog settings |
| `README.md` | Modify | Brief mention of agent/model selection |

## Definition of Done

### Catalog & Fetching
- [ ] `ModelFetcher` successfully fetches models from Anthropic, OpenAI, and Gemini Direct API
- [ ] Fetch results stored in DB as `models_{provider}_json` + `models_{provider}_fetched_at`
- [ ] `AgentCatalog.buildCatalog()` returns models from DB when cache is present
- [ ] `AgentCatalog.buildCatalog()` falls back to `ModelCatalog.kt` when DB is empty or JSON invalid
- [ ] Copilot entry has no `directApiModels` (CLI only)
- [ ] Custom entry has empty `directApiModels` list (UI shows "Use custom_api_model setting")

### API Endpoints
- [ ] `GET /api/agents` returns server-filtered catalog (enabled + mode-compatible agents only)
- [ ] `GET /api/agents` never returns 500 (always returns `{"agents":[]}` on error)
- [ ] `POST /api/settings/fetch-models` returns `{"ok":true,"count":N,"fetchedAt":"..."}` on success
- [ ] `POST /api/settings/fetch-models` returns `{"ok":false,"error":"..."}` (200 status) on failure
- [ ] `POST /api/settings/fetch-models` rejected for CLI mode providers

### Backend Propagation
- [ ] `ModelSelection.selectModel()` accepts `agentId`/`modelId` params
- [ ] Explicit `agentId` for disabled provider → `ConfigurationError`
- [ ] Explicit `agentId` without API key (API mode) → `ConfigurationError`
- [ ] `LlmCodergenBackend` uses `runModelId` when `node.llmModel` is blank
- [ ] `LlmCodergenBackend` uses `runAgentId` when `node.llmProvider` is blank
- [ ] `/api/run`, `/api/generate`, `/api/generate/stream`, `/api/iterate/stream`, `/api/iterate` all parse and forward `agentId`/`modelId`
- [ ] Blank/empty `agentId`/`modelId` → existing auto-selection behavior unchanged

### Settings UI
- [ ] "Model Catalog" section visible in Settings
- [ ] Per-provider row shows model count + "fetched X ago" when cache present
- [ ] Per-provider row shows "Built-in list" in CLI subprocess mode
- [ ] "Refresh" button calls `POST /api/settings/fetch-models` and updates row on success
- [ ] Non-blocking inline error displayed when refresh fails (no alert())

### Create/Iterate UI
- [ ] Agent dropdown populated from `/api/agents`; only enabled + mode-compatible providers shown
- [ ] Model dropdown filtered to selected agent's models
- [ ] "Auto-detect" is always the first option for both dropdowns
- [ ] Custom agent model dropdown shows single disabled option "Use custom_api_model setting"
- [ ] Selection persisted to localStorage scoped by execution mode
- [ ] localStorage selection restored on page load if agent still present
- [ ] Dropdown resets when provider is disabled or execution mode changes
- [ ] `triggerGenerate()` sends `agentId`/`modelId` to `/api/generate/stream`
- [ ] `runGenerated()` sends `agentId`/`modelId` to `/api/run`
- [ ] Iterate flow sends `agentId`/`modelId` to `/api/iterate/stream` + `/api/iterate`

### Tests
- [ ] All existing `ModelSelectionTest` tests pass
- [ ] New explicit-override tests pass
- [ ] `AgentCatalogTest` tests pass
- [ ] `make test` exits 0

### Docs
- [ ] `docs-site/content/web-app.md` has "Agent & Model Selection" section
- [ ] `README.md` updated

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Provider `/models` API changes or requires new auth scope | Medium | Medium | `ModelFetcher` catches all exceptions; returns error in `FetchResult`; UI shows non-blocking warning; falls back to `ModelCatalog.kt` |
| `AgentCatalog` blocks on slow HTTPS fetch during `/api/agents` call | Medium | Low | `/api/agents` does NOT live-fetch — it only reads DB cache. Fetching happens only when user clicks "Refresh". |
| Adding `agentId`/`modelId` to `RunOptions` breaks `resubmit()` | Low | High | Both fields default to `""` (auto); `resubmit()` reuses stored `options` unchanged |
| `LlmCodergenBackend` constructor change breaks existing usages | Low | High | Kotlin default params; existing call sites pass no extra args |
| Frontend dropdown populated before `/api/agents` responds | Low | Low | Dropdowns default to "Auto-detect" until catalog loads; generation/run still works |
| Provider-disabled agent still selected in localStorage | Low | Low | `populateAgentDropdown()` checks that restored agent is present in current catalog; resets if not |
| `WebMonitorServer.kt` is already 5187 lines | Low | Low | Following existing patterns; no architectural change; additions are modular JS functions and HTML blocks |

## Security Considerations

- `ModelFetcher` applies a 10-second timeout on all HTTP connections; reads response body up to a reasonable size limit (1 MB) to prevent resource exhaustion.
- Cached model JSON is treated as untrusted input: wrapped in try/catch during parsing; malformed JSON falls through to `ModelCatalog.kt` fallback.
- `POST /api/settings/fetch-models` only accepts known provider IDs (`anthropic`, `openai`, `gemini`); rejects arbitrary URLs.
- API keys used by `ModelFetcher` are not logged; existing key-handling conventions from `ClientProvider.kt` are followed.
- No new environment variables exposed.

## Dependencies

- Sprints 001–025 completed.
- No new Gradle dependencies (uses `java.net.HttpURLConnection`).
- No new system dependencies.

## Open Questions

1. **Per-run model persistence**: Should the selected `agentId`/`modelId` be stored with the run record so a user can see which model was used for a previous run? Not in scope for this sprint — the run `originalPrompt` field is the current per-run metadata. Future enhancement.

2. **Stale model warning**: If a user has "gpt-4.1-mini" in localStorage but OpenAI has removed it from the fetched list, should the UI show a warning? Current proposal: silently reset to "Auto-detect" with a console log. A future sprint could add a non-blocking toast.

3. **Provider-specific model filtering criteria**: The `fetchOpenAIModels()` filter (ids starting with `gpt-`, `o1`, `o3`, `o4`) is a heuristic. If OpenAI changes their model naming scheme, the filter will need updating. Consider making the filter configurable or reading from a model type field in the API response.
