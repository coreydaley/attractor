# Sprint 026 Intent: Agent & Model Selection UI with Dynamic Catalog

## Seed

Users should be able to select which Agent and Model that they would like to use when creating or iterating on a project. On the create and iterate pages there should be a way to select an Agent (out of all of the currently enabled Agents based on whether Direct API or CLI Subprocess is selected) and also select a valid model to use. The settings page should have an area for specifying a url to a json file (web based url https) or to upload a file with a specific format that specifies the Agents and what models are available to use with them, this file should specify DirectAPI and CLI Subprocess agents and their models that can be used. It should default to a file in this repository using its raw url and we should have a python script in the scripts directory that checks on the web for what models are currently available for each agent, and then builds the file, something like agents.json and have a periodic github action that runs the script and commits the file if it is successful. The project should then use that Agent and Model when generating the graph or running the project.

## Context

Currently, `ModelSelection.selectModel()` auto-picks the first enabled provider in a hardcoded priority order (Anthropic > OpenAI > Gemini > Copilot > Custom) and uses a hardcoded default model per provider. There is no UI for choosing which provider or model to use per-request. The `ModelCatalog.kt` is a static Kotlin object — the list of models is hardcoded in the binary and only updated by releasing new code.

The `DotGenerator` (graph generation) and `ProjectRunner` (project execution) both call `ModelSelection.selectModel()` and use whatever it returns. There is no way for the user to override the provider or model without changing which providers are enabled in settings.

The `agents.json` file will live in the repository and be maintained by a Python script + GitHub Action. The server will load it (from a configurable URL or bundled fallback), expose it via `/api/agents`, and the frontend will use it to populate agent/model dropdowns on the Create/Iterate pages.

## Recent Sprint Context

- **Sprint 023**: Split Docker build into base + server images for faster CI releases.
- **Sprint 024**: Documentation-only audit — corrected drift across README, docs-site, and API reference.
- **Sprint 025**: Built a first-party custom Hugo theme (`attractor`) for the docs site, matching app's dark/light design language and eliminating the vendored geekdoc dependency.

## Relevant Codebase Areas

- `src/main/kotlin/attractor/llm/ModelCatalog.kt` — static list of `ModelInfo` objects per provider; needs to be supplemented/replaced by a dynamic catalog from `agents.json`.
- `src/main/kotlin/attractor/llm/ModelSelection.kt` — `selectModel(config)` auto-selects provider+model; needs to accept explicit `agentId`/`modelId` overrides.
- `src/main/kotlin/attractor/llm/LlmExecutionConfig.kt` — `RunOptions`-equivalent for LLM settings; lives alongside `ProviderToggles`, `CliCommands`, `CustomApiConfig`.
- `src/main/kotlin/attractor/web/DotGenerator.kt` — calls `ModelSelection.selectModel()` for all generation/iterate/fix/describe operations; needs optional agent/model params.
- `src/main/kotlin/attractor/web/ProjectRunner.kt` — `RunOptions` data class (simulate, autoApprove); needs optional `agentId`/`modelId` fields that propagate to handler execution.
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — massive single-file SPA (~5187 lines); Create view and Settings view need UI additions; new `/api/agents` endpoint needed.
- `src/main/kotlin/attractor/handlers/` — `CodergenHandler.kt` + `LlmCodergenBackend.kt` must use the `agentId`/`modelId` from `RunOptions` instead of always calling `ModelSelection.selectModel()`.
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — tests for auto-selection; needs new tests for explicit overrides.
- `scripts/` — currently has `dev.sh` and `generate-openapi.py`; new `fetch_models.py` goes here.
- `.github/workflows/` — new `update-agents.yml` for periodic catalog refresh.

## Constraints

- Must follow project conventions in CLAUDE.md.
- Must integrate with existing architecture (settings stored in SQLite via `store.getSetting`/`store.saveSetting`, settings served via `/api/settings`, SPA JS pattern).
- `WebMonitorServer.kt` is one large Kotlin file with all HTML/CSS/JS inlined — additions go in that file following existing patterns.
- `RunOptions` changes must be backward-compatible (both fields optional with null/empty defaults) so that `rerun` and legacy runs work without agent/model overrides.
- The `agents.json` default URL must be the raw GitHub URL of the file in this repository.
- Python script must work without requiring auth tokens for Anthropic/OpenAI/Gemini (use their public docs/API listings or well-known endpoints).
- GitHub Action must not fail the pipeline if the fetch fails (idempotent, commit only on change).

## Success Criteria

1. User can select an Agent (e.g., "Anthropic", "OpenAI") and Model (e.g., "claude-sonnet-4-6") on the Create page before generating DOT.
2. The same selectors appear on the Iterate view so users can change agent/model when iterating.
3. The selected agent+model is used when running the generated project (not just for generation).
4. Settings page has a "Model Catalog" section: URL field (pre-filled with repo raw URL) + file upload option.
5. `agents.json` exists in the repo, is kept up to date by a GitHub Action, and follows a documented schema.
6. The agent dropdown only shows providers that are currently enabled in Settings (respecting execution mode — Direct API vs CLI Subprocess).
7. The model dropdown is filtered to models valid for the selected agent in the current execution mode.
8. If no agent/model is selected (blank), behavior falls back to existing auto-selection logic.

## Verification Strategy

- Reference: existing `ModelSelectionTest.kt` patterns + new tests for explicit override path.
- Manual: load the app, enable a provider, open Create, verify dropdowns populate correctly.
- Manual: select a specific agent+model, generate, run, verify logs show the correct provider/model used.
- Manual: change the catalog URL in Settings to a custom JSON file, verify the dropdowns update.
- Automated: new unit tests for `ModelSelection.selectModel()` with explicit overrides.
- Automated: new unit tests for `agents.json` parsing / catalog loading logic.
- Edge cases: invalid model for provider, model not in catalog (fall through to auto-select), provider disabled after selection.

## Uncertainty Assessment

- **Correctness uncertainty**: Medium — the LLM call chain from `RunOptions` → `Engine` → `CodergenHandler` needs careful tracing; currently `CodergenHandler` likely calls `ClientProvider` + `ModelSelection` independently, so propagating `agentId`/`modelId` through the whole chain requires understanding that path fully.
- **Scope uncertainty**: Medium — the "Python script checks what models are currently available" requirement may be limited by what providers expose without auth (public model lists vs API-only).
- **Architecture uncertainty**: Low — the pattern (new DB setting, new API endpoint, new JS section, new dropdowns) is consistent with existing Settings patterns in the codebase.

## Interview Refinements (Applied)

1. **Scope**: Agent+model selection applies to BOTH DOT generation AND every codergen stage during project execution. `RunOptions` carries `agentId`/`modelId` through the full run.

2. **Model catalog approach (MAJOR CHANGE)**: Drop the Python script + `agents.json` file + GitHub Action. Instead:
   - When a provider is enabled in Settings, the server queries that provider's live model listing API and stores the results in the SQLite DB (keys: `models_anthropic`, `models_openai`, `models_gemini`, etc.).
   - Each provider section in Settings has a **"Refresh models"** button to re-fetch on demand.
   - `/api/agents` builds the catalog from DB-stored model lists, falling back to `ModelCatalog.kt` hardcoded defaults when the DB is empty or the API is unreachable.
   - CLI subprocess mode providers (which have no API key to query with) always use hardcoded fallback lists.
   - A new `ModelFetcher.kt` handles the per-provider HTTP queries.
   - A new `/api/settings/fetch-models` endpoint is the backend for the Refresh button.

3. **Custom agent model dropdown**: Show a single disabled option "Use custom_api_model setting". The model for the Custom agent is configured in Settings, not per-request.

4. **localStorage persistence**: The Create page remembers the last-selected agentId and modelId across page reloads. Dropdowns restore from localStorage on load if the agent is still enabled.

5. **No `agents.json` repo file, no Python script, no GitHub Action.**
