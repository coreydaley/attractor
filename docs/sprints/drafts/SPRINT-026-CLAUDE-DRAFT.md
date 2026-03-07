# Sprint 026: Agent & Model Selection UI with Dynamic Catalog

## Overview

Right now, Attractor auto-selects a provider ("anthropic", "openai", etc.) and a default model
based on a hardcoded priority list in `ModelSelection.kt`. The user has no say: as long as Anthropic
is enabled and an API key is present, every DOT generation and every project run uses Claude Sonnet
regardless of what the user might prefer. The model list is baked into the JVM binary and only
changes when a new release ships.

This sprint fixes both problems. It introduces:

1. **A dynamic `agents.json` catalog** stored in the repo root, kept up to date by a periodic
   GitHub Action that runs a Python script (`scripts/fetch_models.py`) to query provider APIs.
   The server loads this catalog from a configurable URL (default: the file's raw GitHub URL) or
   from a user-uploaded file, and exposes it through a new `/api/agents` endpoint.

2. **Agent + Model dropdowns on the Create and Iterate pages.** The Agent dropdown is filtered to
   providers that are currently enabled in Settings (and compatible with the current execution
   mode). The Model dropdown is filtered to models the catalog lists for that agent and mode.
   Selecting nothing falls through to the existing auto-selection logic.

3. **End-to-end propagation.** The chosen `agentId`/`modelId` flows from the UI into the
   `/api/generate/stream` and `/api/run` requests, through `RunOptions`, `LlmCodergenBackend`,
   and ultimately into every LLM call that runs during a project — both generation and execution.

4. **Settings page catalog management.** A new "Model Catalog" section lets the operator point to
   any HTTPS URL hosting the catalog JSON or upload a file directly. The URL/content is stored in
   the SQLite DB just like other settings.

## Use Cases

1. **Prefer a reasoning model for complex projects**: User enables Anthropic and OpenAI in Direct
   API mode. They open Create, select Agent "OpenAI" and Model "o3", then click Generate. The DOT
   is generated using o3. They click Create and the entire pipeline runs using o3 for every
   `codergen` stage.

2. **Use a cheaper model for quick iterations**: User selects "claude-haiku-4-5-20251001" on the
   Iterate page to test small DOT changes cheaply before switching back to Sonnet.

3. **Custom/self-hosted model catalog**: Operator replaces the catalog URL in Settings with a
   private HTTPS URL hosting their own `agents.json` that includes internal Ollama models under
   the `custom` agent. Dropdowns immediately reflect the new model list on next page load.

4. **Catalog auto-refresh via CI**: `update-agents.yml` runs weekly. `fetch_models.py` queries
   Anthropic's, OpenAI's, and Gemini's model list endpoints, writes a new `agents.json`, and
   opens a commit if the file changed. No manual releases required to pick up a new model.

5. **No selection = existing behavior**: User leaves Agent and Model dropdowns at their defaults
   ("Auto-detect"). Behavior is exactly as before Sprint 026.

## Architecture

```
agents.json (repo root)
  │  raw GitHub URL (default catalog source)
  │
  ▼
AgentCatalog.kt        ← loads JSON from URL or DB-stored content; caches in memory (5 min TTL)
  │
  ├─ /api/agents        ← GET: returns full catalog JSON (filtered by enabled providers)
  │
  └─ AgentCatalogEntry  ← data class: id, displayName, directApiModels, cliModels

Settings page (UI)
  └─ "Model Catalog" section
        ├─ URL input (default = raw GitHub URL of agents.json)
        ├─ Upload file button
        └─ Save → PUT /api/settings/update { agents_catalog_url, agents_catalog_json }

Create page (UI)
  ├─ Agent dropdown   ← populated from /api/agents, filtered to enabled providers for current mode
  ├─ Model dropdown   ← populated by selecting an agent entry
  └─ values sent in:
        /api/generate/stream { prompt, agentId, modelId }
        /api/run             { dotSource, ..., agentId, modelId }

Iterate page (shares Create view's dropdowns; same values sent to /api/iterate/stream + /api/iterate)

Backend call chain (generate):
  DotGenerator.generateStream(prompt, agentId, modelId)
    → ModelSelection.selectModel(config, agentId, modelId)   ← explicit override when non-blank
    → client.stream(Request(model=modelId, provider=agentId, ...))

Backend call chain (run):
  /api/run body { agentId, modelId }
    → RunOptions(agentId=..., modelId=...)
    → ProjectRunner.submit(options=...)
    → LlmCodergenBackend(client, runAgentId, runModelId)
    → run(): model = node.llmModel.ifEmpty { runModelId }.ifEmpty { DEFAULT_MODEL }
             provider = node.llmProvider.ifEmpty { runAgentId }.ifEmpty { null }

agents.json schema:
{
  "version": "1",
  "updatedAt": "2026-03-06",
  "agents": [
    {
      "id": "anthropic",
      "displayName": "Anthropic",
      "directApi": {
        "models": [
          { "id": "claude-opus-4-6",           "displayName": "Claude Opus 4.6" },
          { "id": "claude-sonnet-4-6",          "displayName": "Claude Sonnet 4.6" },
          { "id": "claude-haiku-4-5-20251001",  "displayName": "Claude Haiku 4.5" }
        ]
      },
      "cliSubprocess": {
        "models": [
          { "id": "claude-opus-4-6",           "displayName": "Claude Opus 4.6" },
          { "id": "claude-sonnet-4-6",          "displayName": "Claude Sonnet 4.6" }
        ]
      }
    },
    { "id": "openai",   "displayName": "OpenAI",   "directApi": { "models": [...] }, "cliSubprocess": { "models": [...] } },
    { "id": "gemini",   "displayName": "Gemini",   "directApi": { "models": [...] }, "cliSubprocess": { "models": [...] } },
    { "id": "copilot",  "displayName": "Copilot",  "directApi": null,               "cliSubprocess": { "models": [...] } },
    { "id": "custom",   "displayName": "Custom API","directApi": { "models": [] },   "cliSubprocess": null }
  ]
}
```

## Implementation Plan

### Phase 1: `agents.json` catalog file (~5%)

**Files:**
- `agents.json` — Create (repo root)

**Tasks:**
- [ ] Create `agents.json` with all five providers (anthropic, openai, gemini, copilot, custom):
  - `anthropic.directApi.models`: claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001
  - `anthropic.cliSubprocess.models`: claude-opus-4-6, claude-sonnet-4-6, claude-haiku-4-5-20251001
  - `openai.directApi.models`: o3, o4-mini, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini
  - `openai.cliSubprocess.models`: o3, o4-mini, gpt-4.1, gpt-4.1-mini, gpt-4o, gpt-4o-mini
  - `gemini.directApi.models`: gemini-2.5-pro, gemini-2.5-flash, gemini-2.0-flash
  - `gemini.cliSubprocess.models`: gemini-2.5-pro, gemini-2.5-flash, gemini-2.0-flash
  - `copilot.directApi`: null (CLI-only agent)
  - `copilot.cliSubprocess.models`: `[{ "id": "copilot", "displayName": "Copilot (auto)" }]`
  - `custom.directApi.models`: `[]` (model set by custom_api_model setting)
  - `custom.cliSubprocess`: null (Direct API only)
- [ ] Validate JSON structure is syntactically correct.

---

### Phase 2: `AgentCatalog.kt` backend class (~10%)

**Files:**
- `src/main/kotlin/attractor/llm/AgentCatalog.kt` — Create

**Tasks:**
- [ ] Define data classes:
  ```kotlin
  data class AgentModelInfo(val id: String, val displayName: String)
  data class AgentModeConfig(val models: List<AgentModelInfo>)
  data class AgentEntry(
      val id: String,
      val displayName: String,
      val directApi: AgentModeConfig?,
      val cliSubprocess: AgentModeConfig?
  )
  data class AgentCatalogData(
      val version: String,
      val updatedAt: String,
      val agents: List<AgentEntry>
  )
  ```
- [ ] Implement `AgentCatalog` object with:
  - `DEFAULT_CATALOG_URL = "https://raw.githubusercontent.com/coreydaley/attractor/main/agents.json"`
  - `private var cached: AgentCatalogData? = null` and `private var cacheExpiry: Long = 0`
  - `fun load(store: RunStore): AgentCatalogData` — checks DB for `agents_catalog_json` (uploaded file content) first; then `agents_catalog_url`; then falls back to bundled `agents.json` resource; caches with 5-min TTL
  - `fun loadFromUrl(url: String): AgentCatalogData` — fetches HTTPS URL, parses JSON using `kotlinx.serialization` (or manual JSON parsing matching existing codebase style)
  - `fun invalidate()` — clears cache (called when settings are updated)
  - Handle network failure gracefully: log warning, fall back to last cached value or bundled file
- [ ] Bundle `agents.json` as a classpath resource: add it to `src/main/resources/agents.json` (symlink or copy).
- [ ] Parse JSON manually (no new Gradle dependencies) matching the pattern used elsewhere in the codebase.

---

### Phase 3: `/api/agents` endpoint (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Add `httpServer.createContext("/api/agents")` handler (GET only):
  - Loads catalog via `AgentCatalog.load(store)`
  - Returns full catalog JSON as-is (the raw JSON string from the catalog)
  - 200 with `Content-Type: application/json`
  - On error: returns `{"error":"..."}` with 500
- [ ] Add cache invalidation: call `AgentCatalog.invalidate()` in the `/api/settings/update` handler after saving settings (so next `/api/agents` call re-fetches if URL changed).

---

### Phase 4: `ModelSelection` explicit override support (~5%)

**Files:**
- `src/main/kotlin/attractor/llm/ModelSelection.kt` — Modify
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — Modify

**Tasks:**
- [ ] Add optional `agentId: String = ""` and `modelId: String = ""` params to `selectModel()`:
  ```kotlin
  fun selectModel(
      config: LlmExecutionConfig,
      env: Map<String, String> = System.getenv(),
      agentId: String = "",
      modelId: String = ""
  ): Pair<String, String>
  ```
- [ ] When `agentId` is non-blank, validate the provider is enabled and (in API mode) has a key; throw `ConfigurationError` if not. Return `agentId to modelId.ifEmpty { defaultModelForAgent(agentId) }`.
- [ ] When only `modelId` is provided (agent blank), treat as a model-only hint; auto-select agent from enabled providers as before, but substitute the model ID.
- [ ] Add `defaultModelForAgent(agentId: String): String` private helper mapping known agents to their default model (matching existing hardcoded defaults).
- [ ] Add tests:
  - `"explicit agentId+modelId bypasses priority order"` — with anthropic disabled, explicit `agentId="anthropic"` throws `ConfigurationError` (provider not enabled)
  - `"explicit agentId selects that provider"` — anthropic+openai enabled; agentId="openai" → openai, modelId used as-is
  - `"explicit modelId with blank agentId auto-selects agent"` — applies modelId override on top of auto-selected agent

---

### Phase 5: `RunOptions` + `DotGenerator` + `LlmCodergenBackend` changes (~10%)

**Files:**
- `src/main/kotlin/attractor/web/ProjectRunner.kt` — Modify (`RunOptions`)
- `src/main/kotlin/attractor/web/DotGenerator.kt` — Modify
- `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` — Modify

**Tasks:**

**RunOptions:**
- [ ] Add `agentId: String = ""` and `modelId: String = ""` to `RunOptions` data class.
- [ ] In `ProjectRunner.runProject()`: pass `options.agentId` and `options.modelId` to `LlmCodergenBackend(client, options.agentId, options.modelId)`.

**LlmCodergenBackend:**
- [ ] Add constructor params `private val runAgentId: String = ""` and `private val runModelId: String = ""`.
- [ ] In `run()`: change model/provider resolution to:
  ```kotlin
  val model = node.llmModel.ifEmpty { runModelId }.ifEmpty { DEFAULT_MODEL }
  val provider = node.llmProvider.ifEmpty { runAgentId }.ifEmpty { null }
  ```
  (No other changes needed — the `generate()` call already accepts `provider`.)

**DotGenerator:**
- [ ] Add `agentId: String = ""` and `modelId: String = ""` params to `generateStream()`, `generate()`, `fixStream()`, `describeStream()`. (Not `iterateStream()` — it delegates to `generateStream()`.)
- [ ] In each method, replace:
  ```kotlin
  val (provider, model) = ModelSelection.selectModel(cfg)
  ```
  with:
  ```kotlin
  val (provider, model) = ModelSelection.selectModel(cfg, agentId = agentId, modelId = modelId)
  ```

---

### Phase 6: API endpoints — accept `agentId`/`modelId` in request bodies (~5%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**

For each of these endpoints, add `val agentId = jsonField(body, "agentId")` and `val modelId = jsonField(body, "modelId")` extraction:

- [ ] `/api/generate` (line ~479): pass `agentId`/`modelId` to `dotGenerator.generate(prompt, agentId, modelId)`.
- [ ] `/api/generate/stream` (line ~516): pass to `dotGenerator.generateStream(prompt, agentId, modelId) { ... }`.
- [ ] `/api/describe-dot/stream` (line ~568): pass to `dotGenerator.describeStream(dotSource, agentId, modelId) { ... }`.
- [ ] `/api/fix-dot` (line ~620): pass to `dotGenerator.fixStream(...)`.
- [ ] `/api/run` (line ~97): extract `agentId`/`modelId` from body; pass into `RunOptions(simulate, autoApprove, agentId, modelId)`.
- [ ] `/api/iterate/stream` (line ~673): pass to `dotGenerator.iterateStream(baseDot, changes, agentId, modelId) { ... }`.
- [ ] `/api/iterate` (line ~733): extract `agentId`/`modelId` from body; pass into `ProjectRunner.submit(..., options = sourceEntry.options.copy(agentId = agentId, modelId = modelId))`.

---

### Phase 7: Settings UI — Model Catalog section (~10%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (HTML + JS)

**Tasks:**

**HTML (in `viewSettings` div, after the existing providers section):**
- [ ] Add a new `<!-- Model Catalog -->` section in the Settings card:
  ```html
  <div class="setting-row" style="flex-direction:column; align-items:flex-start; gap:10px;">
    <div class="setting-info">
      <div class="setting-label">Model Catalog</div>
      <div class="setting-desc">JSON file defining available agents and models. Defaults to the built-in catalog from the repository.</div>
    </div>
    <div style="display:flex; gap:8px; width:100%; align-items:center;">
      <input id="catalogUrlInput" type="url" placeholder="https://raw.githubusercontent.com/coreydaley/attractor/main/agents.json"
        style="flex:1; padding:6px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-muted); color:var(--text); font-size:0.85rem;"
        onblur="saveCatalogUrl()">
      <button onclick="document.getElementById('catalogFileInput').click()"
        style="padding:6px 14px; border-radius:6px; border:1px solid var(--border); background:var(--surface-muted); color:var(--text); font-size:0.85rem; cursor:pointer; white-space:nowrap;">
        Upload file
      </button>
    </div>
    <input type="file" id="catalogFileInput" accept=".json" style="display:none;" onchange="onCatalogFileSelected()">
    <div id="catalogStatus" style="font-size:0.8rem; color:var(--text-muted);"></div>
    <button onclick="resetCatalogToDefault()" style="padding:4px 12px; border-radius:5px; border:1px solid var(--border); background:transparent; color:var(--text-muted); font-size:0.8rem; cursor:pointer;">
      Reset to default
    </button>
  </div>
  ```

**JS (in the script block):**
- [ ] `loadCatalogSettings()` — reads `agents_catalog_url` from `appSettings`, populates `#catalogUrlInput`.
- [ ] `saveCatalogUrl()` — calls `saveSetting('agents_catalog_url', value)`, then `AgentCatalog.invalidate()` on the server via the existing `saveSetting` path, updates `#catalogStatus`.
- [ ] `onCatalogFileSelected()` — reads file as text, calls `saveSetting('agents_catalog_json', fileContent)` to store the raw JSON in the DB; updates `#catalogStatus` with file name.
- [ ] `resetCatalogToDefault()` — calls `saveSetting('agents_catalog_url', '')` and `saveSetting('agents_catalog_json', '')`, resets input, updates status.
- [ ] Call `loadCatalogSettings()` inside `loadSettings()`.

---

### Phase 8: Create/Iterate UI — Agent & Model dropdowns (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify (HTML + JS)

**Tasks:**

**HTML (in the `viewCreate` / `create-section`):**
- [ ] Add agent+model selector row between the textarea and the options row:
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
- [ ] `var agentCatalog = null;` — cached catalog from `/api/agents`.
- [ ] `loadAgentCatalog()` — fetches `/api/agents`, stores in `agentCatalog`, calls `populateAgentDropdown()`.
- [ ] `populateAgentDropdown()` — filters `agentCatalog.agents` to those enabled in `appSettings` and compatible with current execution mode (Direct API → `directApi != null`; CLI → `cliSubprocess != null`). Adds `<option value="agentId">displayName</option>` for each.
- [ ] `onAgentChange()` — when agent changes, re-populate model dropdown by looking up the selected agent's models for the current mode. First option always `<option value="">Auto-detect</option>`.
- [ ] `getSelectedAgent()` / `getSelectedModel()` — return current dropdown values (empty string = auto-detect).
- [ ] Call `loadAgentCatalog()` inside `loadSettings()` (so dropdowns are ready by the time the Create view is shown).
- [ ] Update `triggerGenerate()` to include `agentId: getSelectedAgent(), modelId: getSelectedModel()` in the request body sent to `/api/generate/stream`.
- [ ] Update `runGenerated()` to include `agentId: getSelectedAgent(), modelId: getSelectedModel()` in the `/api/run` request body.
- [ ] Update iterate stream call to include the current agent+model values.
- [ ] Update `applyCreatePageAgentState()` to keep dropdowns in sync when execution mode changes.
- [ ] When the Create view is reset (`resetCreatePage()`), reset dropdowns back to "Auto-detect".

---

### Phase 9: `scripts/fetch_models.py` (~10%)

**Files:**
- `scripts/fetch_models.py` — Create

**Tasks:**
- [ ] Python 3 script (no third-party deps, stdlib only: `urllib.request`, `json`, `datetime`).
- [ ] Fetches model lists from:
  - **Anthropic**: `GET https://api.anthropic.com/v1/models` (requires `x-api-key` header; reads from `ANTHROPIC_API_KEY` env var; skips gracefully if not set, uses hardcoded fallback list).
  - **OpenAI**: `GET https://api.openai.com/v1/models` (requires `Authorization: Bearer` header; reads from `OPENAI_API_KEY`; same fallback pattern).
  - **Gemini**: `GET https://generativelanguage.googleapis.com/v1/models?key={key}` (reads from `GEMINI_API_KEY` or `GOOGLE_API_KEY`; same fallback).
  - **Copilot**: No public API — uses curated static list `["copilot"]`.
  - **Custom**: No models to fetch — always `[]` with a note.
- [ ] Filters raw model lists to those relevant to Attractor (exclude fine-tuned, deprecated, or non-generation models by checking `id` prefixes / capabilities).
- [ ] Builds `agents.json` in the schema above.
- [ ] Writes to `agents.json` in the current directory (or `--output` flag).
- [ ] Exits 0 on success; exits 1 if no providers could be contacted (all env vars missing and no fallbacks work).
- [ ] Prints a summary to stdout: which providers were fetched live vs. used fallback.

---

### Phase 10: GitHub Action `update-agents.yml` (~5%)

**Files:**
- `.github/workflows/update-agents.yml` — Create

**Tasks:**
- [ ] Trigger: `schedule: - cron: '0 6 * * 1'` (weekly, Monday 06:00 UTC) + `workflow_dispatch`.
- [ ] Steps:
  1. `actions/checkout@v4` (with `fetch-depth: 0` and `token: ${{ secrets.GITHUB_TOKEN }}`).
  2. `actions/setup-python@v5` with `python-version: '3.x'`.
  3. Run `python3 scripts/fetch_models.py --output agents.json`.
  4. Check if `agents.json` changed: `git diff --quiet agents.json || echo "changed=true" >> $GITHUB_OUTPUT`.
  5. If changed: commit with `git add agents.json && git commit -m "chore(catalog): update agents.json model list [skip ci]"` and `git push`.
  6. Env vars: `ANTHROPIC_API_KEY: ${{ secrets.ANTHROPIC_API_KEY }}`, `OPENAI_API_KEY: ${{ secrets.OPENAI_API_KEY }}`, `GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}`.
- [ ] Secrets are optional — script uses fallbacks if not set.
- [ ] The workflow never fails the pipeline: the commit step is conditional on file change.

---

### Phase 11: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — Modify
- `src/test/kotlin/attractor/llm/AgentCatalogTest.kt` — Create

**Tasks:**

**ModelSelectionTest additions:**
- [ ] `"explicit agentId+modelId returns that pair"` — explicit agentId="openai", modelId="gpt-4.1" with anthropic+openai enabled → ("openai", "gpt-4.1").
- [ ] `"explicit agentId with disabled provider throws ConfigurationError"` — anthropic disabled, agentId="anthropic" → ConfigurationError.
- [ ] `"explicit agentId with no key in API mode throws ConfigurationError"` — openai enabled but no OPENAI_API_KEY, agentId="openai" → ConfigurationError.
- [ ] `"blank modelId with explicit agentId uses default model for agent"` — agentId="gemini", modelId="" → ("gemini", default gemini model).

**AgentCatalogTest:**
- [ ] `"parses valid agents.json"` — parses a minimal JSON string; asserts agent count, model count per agent.
- [ ] `"unknown fields are ignored"` — JSON with extra fields at top level and in model objects; parses without error.
- [ ] `"agents with null directApi have no direct-api models"` — copilot entry: `directApi: null`; `entry.directApi` is null.
- [ ] `"agents with null cliSubprocess have no cli models"` — custom entry has no CLI models.

---

### Phase 12: Documentation (~5%)

**Files:**
- `docs-site/content/web-app.md` — Modify
- `README.md` — Modify

**Tasks:**
- [ ] **`web-app.md`**: Add "Agent & Model Selection" section covering:
  - Where the dropdowns appear (Create page, Iterate view)
  - "Auto-detect" behavior (falls through to existing priority order)
  - How the Model Catalog setting controls the available options
- [ ] **`web-app.md`**: Add "Model Catalog (Settings)" subsection covering:
  - The default URL (repo raw URL)
  - How to point to a custom URL
  - How to upload a file
  - How `fetch_models.py` and the weekly GitHub Action keep it fresh
- [ ] **`README.md`**: Add brief mention of agent/model selection to the "Web UI" paragraph.

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `agents.json` | Create | Catalog of agents and models (repo root; default catalog source) |
| `src/main/resources/agents.json` | Create | Bundled fallback copy of catalog for classpath loading |
| `src/main/kotlin/attractor/llm/AgentCatalog.kt` | Create | Loads, caches, and exposes the agent+model catalog |
| `src/main/kotlin/attractor/llm/ModelSelection.kt` | Modify | Accept explicit `agentId`/`modelId` override params |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Accept `agentId`/`modelId` in all generation methods |
| `src/main/kotlin/attractor/web/ProjectRunner.kt` | Modify | Add `agentId`/`modelId` to `RunOptions`; pass to backend |
| `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` | Modify | Add run-level `runAgentId`/`runModelId` constructor params |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | `/api/agents` endpoint; settings UI; create/iterate dropdowns |
| `scripts/fetch_models.py` | Create | Fetches model lists from provider APIs; writes `agents.json` |
| `.github/workflows/update-agents.yml` | Create | Weekly GitHub Action to run `fetch_models.py` and commit changes |
| `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` | Modify | New tests for explicit override behavior |
| `src/test/kotlin/attractor/llm/AgentCatalogTest.kt` | Create | Tests for catalog parsing |
| `docs-site/content/web-app.md` | Modify | Document agent/model selection UI and catalog settings |
| `README.md` | Modify | Brief mention of agent/model selection feature |

## Definition of Done

### Catalog
- [ ] `agents.json` exists in repo root with all 5 agents and correct schema
- [ ] `src/main/resources/agents.json` mirrors repo root file (for classpath fallback)
- [ ] `AgentCatalog.load()` returns valid data when URL is reachable
- [ ] `AgentCatalog.load()` falls back to classpath resource when URL is unreachable
- [ ] `/api/agents` returns JSON with 200 status

### Settings UI
- [ ] "Model Catalog" section visible in Settings
- [ ] URL input pre-filled with the configured URL (or empty = using default)
- [ ] File upload saves JSON to DB and is used on next `/api/agents` call
- [ ] "Reset to default" clears custom URL/JSON and reverts to repo raw URL
- [ ] `AgentCatalog` cache invalidated when catalog settings change

### Create/Iterate UI
- [ ] Agent dropdown appears on Create page; options reflect enabled providers for current execution mode
- [ ] Model dropdown appears; options reflect models for selected agent and current mode
- [ ] "Auto-detect" option always present as the first option
- [ ] Changing execution mode in Settings updates agent dropdown (disabled providers filtered out)
- [ ] Selecting "Auto-detect" for agent clears model dropdown back to "Auto-detect" only
- [ ] `triggerGenerate()` sends `agentId`/`modelId` to `/api/generate/stream`
- [ ] `runGenerated()` sends `agentId`/`modelId` to `/api/run`
- [ ] Iterate flow sends `agentId`/`modelId` to `/api/iterate/stream` and `/api/iterate`

### Backend
- [ ] `/api/run` parses `agentId`/`modelId` from request body into `RunOptions`
- [ ] `/api/generate/stream` passes `agentId`/`modelId` to `DotGenerator`
- [ ] `ModelSelection.selectModel()` returns explicit `agentId`+`modelId` when both provided
- [ ] `ModelSelection.selectModel()` throws `ConfigurationError` when explicit provider is disabled
- [ ] `LlmCodergenBackend` uses `runModelId` as fallback when `node.llmModel` is empty
- [ ] `LlmCodergenBackend` uses `runAgentId` as fallback when `node.llmProvider` is empty
- [ ] "Auto-detect" (empty) agent/model → existing auto-selection behavior unchanged

### Python script + GitHub Action
- [ ] `scripts/fetch_models.py` runs with `python3` stdlib only (no pip deps)
- [ ] Script exits 0 when at least one provider is fetched or uses fallback
- [ ] Script writes valid `agents.json` matching the schema
- [ ] `update-agents.yml` workflow exists and triggers weekly + on `workflow_dispatch`
- [ ] Workflow commits `agents.json` only when the file changed
- [ ] Workflow uses `[skip ci]` in commit message to avoid triggering itself

### Tests
- [ ] All existing `ModelSelectionTest` tests pass
- [ ] New explicit-override tests pass
- [ ] `AgentCatalogTest` tests pass
- [ ] `make test` exits 0

### Docs
- [ ] `docs-site/content/web-app.md` updated with agent/model selection docs
- [ ] `README.md` updated

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Provider APIs require auth for model listing | High | Medium | Script reads API keys from env vars; falls back to hardcoded lists when keys not present |
| `AgentCatalog.load()` blocks on slow HTTPS fetch | Medium | Medium | Add 5-second timeout to URL fetch; use cached value if available |
| Adding `agentId`/`modelId` to `RunOptions` breaks `resubmit()` | Low | High | Both fields default to `""` (auto); `resubmit()` reuses stored `options` unchanged |
| `LlmCodergenBackend` constructor change breaks existing usages | Low | High | All existing call sites pass no `runAgentId`/`runModelId`; Kotlin defaults handle it |
| Model ID not recognized by provider API | Low | High | Existing per-node `node.llmModel` mechanism already passes model strings directly; same behavior |
| GitHub Action commits in a loop (commit triggers action again) | Low | Medium | `[skip ci]` in commit message prevents re-trigger |
| Frontend dropdown populates before `/api/agents` responds | Low | Low | Dropdowns default to "Auto-detect" until catalog loads; generation/run still works |
| Custom catalog URL returns malformed JSON | Low | Low | `AgentCatalog.load()` catches parse exceptions, falls back to classpath resource |
| `WebMonitorServer.kt` already at 5187 lines | Low | Low | Following existing patterns; no architectural change needed |

## Security Considerations

- Catalog URL must be HTTPS only — reject non-HTTPS URLs in `AgentCatalog.loadFromUrl()`.
- Uploaded catalog JSON is stored verbatim in SQLite; it is never executed, only parsed; malformed JSON is caught and rejected.
- `fetch_models.py` uses only standard library HTTPS; no shell execution or dynamic code evaluation.
- GitHub Action secrets (`ANTHROPIC_API_KEY` etc.) are optional; script works without them using curated fallback lists.
- No new environment variables exposed in the Docker image.

## Dependencies

- Sprints 001–025 all completed.
- `kotlinx.serialization` is already available in the project (check `build.gradle.kts`); if not, JSON parsing uses the same manual `jsonField()` helpers already in `WebMonitorServer.kt`.
- No new Gradle dependencies.
- No new system dependencies.

## Open Questions

1. **Copilot CLI model IDs**: The `copilot` CLI doesn't expose a model list. Should the CLI subprocess mode for Copilot show just "Copilot (auto)" or also expose the underlying models (gpt-4.1, claude-sonnet-4-6, etc.) that Copilot might delegate to? Proposal: show only "Copilot (auto)" since the user can't control which model GitHub Copilot uses internally.

2. **Custom agent model dropdown**: The `custom` agent uses whatever model is set in `custom_api_model` DB setting. Should the model dropdown for Custom be disabled (since the model is set in Settings), or show a text input? Proposal: show a free-text input or single "Use custom_api_model setting" option.

3. **Persist last-used agent/model per session**: Should the Create page remember the last-selected agent+model between sessions (via `localStorage`)? Proposal: yes, store in `localStorage` and restore on page load.

4. **`src/main/resources/agents.json` vs symlink**: The repo root `agents.json` and `src/main/resources/agents.json` would need to stay in sync. Options: (a) copy step in `build.gradle.kts`, (b) symlink, (c) make `AgentCatalog` load from the repo root path when running in dev mode. Proposal: add a `processResources` task in Gradle to copy `agents.json` into the resources.
