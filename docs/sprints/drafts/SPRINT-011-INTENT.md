# Sprint 011 Intent: AI Provider Execution Mode & Per-Provider Toggles

## Seed

> users of this application should be able to specify whether to utilize the ai agents needed using either direct API access or cli using the claude, codex, and gemini commands line applications. I want the users to be able to select whether to use direct API access or command line via a toggle on the settings page. users should also be able to toggle individual ai agent type (such as claude, codex, gemini) on and off as well.

## Context

The application currently invokes LLM providers exclusively via direct HTTP REST API calls (using `OkHttp`). Provider selection is determined solely by the presence of API key environment variables (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`). There is no runtime configuration for execution mode or provider enable/disable — these decisions are made at startup based on environment.

Three CLI tools exist as official alternatives to direct API access:
- `claude` — Anthropic's official CLI
- `codex` — OpenAI's CLI
- `gemini` — Google's CLI

This sprint introduces a runtime-configurable execution layer: users can toggle between **direct API** and **CLI subprocess** mode globally, and can enable/disable individual providers independently via the Settings page.

## Recent Sprint Context

- **Sprint 008** — Performance Optimization: SSE broadcast storm fix, SQLite WAL mode, `hasLog` caching. Established `RunStore.getSetting()`/`setSetting()` for SQLite-backed settings.
- **Sprint 009** — Critical Path Test Coverage: 52+ Kotest FunSpec unit tests across 8 modules. Established test patterns: `FunSpec`, temp dirs, no network calls, mock-friendly seams.
- **Sprint 010** — Full-Featured RESTful API v1 (in_progress): New `RestApiRouter.kt`, `/api/v1/` prefix, 35+ endpoints for pipelines, runs, settings, models. Settings endpoints included.

## Relevant Codebase Areas

### LLM Execution Layer
- `src/main/kotlin/attractor/llm/Client.kt` — `Client.fromEnv()`: registers providers from API key env vars; `setDefaultClient()`/`getDefaultClient()` globals
- `src/main/kotlin/attractor/llm/ModelCatalog.kt` — provider registry: `anthropic`, `openai`, `gemini` with model lists
- `src/main/kotlin/attractor/llm/AnthropicAdapter.kt`, `OpenAIAdapter.kt`, `GeminiAdapter.kt` — HTTP REST adapters for each provider
- `src/main/kotlin/attractor/web/DotGenerator.kt` — `selectModel()`: hardcoded priority (Anthropic → OpenAI → Gemini); all generation calls `Client.fromEnv()` directly

### Settings Infrastructure
- `src/main/kotlin/attractor/store/RunStore.kt` — `getSetting(key, default)` / `setSetting(key, value)` via SQLite `settings` table
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Settings page (`viewSettings`), toggle pattern for `fireworks_enabled`, `/api/settings` GET + `/api/settings/update` POST endpoints

### New for Sprint 011
- New CLI execution adapters: `AnthropicCliAdapter.kt`, `OpenAICliAdapter.kt`, `GeminiCliAdapter.kt`
- Settings keys: `execution_mode` (`api`|`cli`), `provider_anthropic_enabled` (`true`|`false`), `provider_openai_enabled`, `provider_gemini_enabled`
- Settings page: global execution mode toggle + per-provider enable/disable toggles

## Constraints

- Must follow project conventions (inline HTML in `WebMonitorServer.kt`, `RunStore` for settings persistence)
- No new Gradle dependencies — subprocess execution via `ProcessBuilder` (already used in `build.gradle.kts`)
- Must not break existing API-key-based execution (API mode remains the default)
- CLI adapters must implement the same streaming interface as HTTP adapters so `DotGenerator` is unaffected
- Settings changes must take effect immediately without server restart
- Must integrate with Sprint 010's `/api/v1/settings` endpoint (settings GET/PUT should reflect new keys)

## Success Criteria

1. Settings page shows a global "Execution Mode" toggle: **API** ↔ **CLI**
2. Settings page shows individual provider toggles: Anthropic ✓/✗, OpenAI ✓/✗, Gemini ✓/✗
3. When a provider is disabled, it is never selected for generation regardless of API key presence
4. When CLI mode is selected, generation invokes the appropriate CLI command via subprocess instead of HTTP
5. CLI adapters stream output the same way as HTTP adapters (real-time delta events)
6. Settings persist across server restarts (stored in SQLite)
7. All new settings are reflected in the REST API settings endpoints

## Verification Strategy

- **Unit tests**: Mock `ProcessBuilder` / test adapter selection logic in isolation
- **Settings persistence**: Verify `getSetting`/`setSetting` roundtrip for all new keys
- **Provider disable**: Verify disabled providers are excluded from `selectModel()` candidates
- **Adapter interface**: Verify CLI adapters conform to streaming interface (type-level verification)
- **Edge cases**:
  - All providers disabled → error message (no provider available)
  - CLI mode selected but CLI binary not on PATH → graceful error with helpful message
  - Switching modes mid-use: next generation uses updated setting

## Uncertainty Assessment

- **Correctness uncertainty**: Medium — CLI streaming (reading process stdout line-by-line as SSE deltas) has edge cases around buffering, partial lines, and error codes
- **Scope uncertainty**: Low — seed is specific; toggles are well-defined
- **Architecture uncertainty**: Medium — where to inject execution mode into `DotGenerator`/`Client` without a full refactor

## Open Questions

1. Should CLI adapters support the same model selection as API adapters, or use the CLI's default model?
2. If execution mode is `cli` but a provider's CLI is not installed, should we fall back to API mode or report an error?
3. Should per-provider CLI command names be configurable (e.g. custom path to `claude` binary), or hardcoded?
4. Should the Settings page show whether each CLI tool is detected as installed (binary presence check)?
