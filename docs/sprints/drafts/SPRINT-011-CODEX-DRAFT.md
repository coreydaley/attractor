# Sprint 011: AI Provider Execution Mode (API vs CLI) + Per-Provider Toggles

## Overview

Attractor currently selects LLM providers exclusively via environment variables at startup: if an API key env var is present, the provider is registered, and all generation is performed via direct HTTP calls (OkHttp). This makes provider usage opaque and inflexible: users cannot switch execution mode at runtime, cannot disable a provider without unsetting env vars, and cannot prefer CLI-based flows when official CLIs are available and already authenticated locally.

This sprint introduces a runtime-configurable LLM execution layer with two user-facing controls on the Settings page: a global **Execution Mode** toggle (**API** vs **CLI**) and per-provider enable/disable toggles (Anthropic/Claude, OpenAI/Codex, Google/Gemini). These settings are persisted in SQLite via `RunStore` and take effect immediately (no restart), affecting DOT generation/fix/iterate flows as well as pipeline execution and failure diagnosis.

Implementation emphasizes minimal disruption: we keep the existing `ProviderAdapter` interface and introduce CLI-backed adapters that conform to it, plus a small “client provider” seam so LLM call sites can resolve the active adapter set at call time based on current settings.

Note: `CLAUDE.md` is referenced in historical planning docs but is not present in this repo; this sprint follows existing Kotlin/web patterns in `src/main/kotlin/attractor/**` and sprint conventions in `docs/sprints/README.md`.

## Use Cases

1. **Local-auth CLI usage**: A user has `claude`/`codex`/`gemini` CLIs installed and authenticated, but does not want to manage API keys as env vars.
2. **Provider governance**: A user disables specific providers (e.g., Gemini) even if keys exist, ensuring they are never selected or invoked.
3. **Operational switching**: A user toggles from API → CLI (or back) and the next generation call uses the new mode without restarting the server.
4. **Troubleshooting**: A user switches to CLI mode to compare behavior with official tools and get better provider-side error messages.

## Architecture

### High-level flow

```text
Web UI (Settings)
  -> POST /api/settings/update (legacy) OR PUT /api/v1/settings/{key} (REST v1)
      -> RunStore.settings (SQLite)
          -> LlmExecutionConfig.from(store) (reads current settings)
              -> ClientProvider.getClient(config) (API or CLI adapters)
                  -> ProviderAdapter.complete/stream(...)
```

### Key design choices

- **Settings are source of truth**: `execution_mode` + `provider_*_enabled` are stored in SQLite and read at call time.
- **Provider selection is explicit**: when selecting a model, we also set `Request.provider` so the correct adapter is used and disabled providers are never chosen.
- **CLI invocation is subprocess-based**: implemented via `ProcessBuilder` (no new dependencies), with a test seam (`ProcessRunner`) to avoid real subprocesses in unit tests.
- **Graceful failure**:
  - CLI mode selected but binary missing → clear error (and UI/API reflects it).
  - All providers disabled → clear error (“no provider available”).
  - Provider disabled but explicitly requested by node config → fail fast with actionable message.

## Implementation Plan

### Phase 1: Settings keys, defaults, and parsing (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt` — Modify
- `src/main/kotlin/attractor/llm/LlmExecutionConfig.kt` — Create (new config + helpers)

**Tasks:**
- [ ] Add settings keys and defaults (insert-or-ignore on startup):
  - `execution_mode`: default `api`
  - `provider_anthropic_enabled`: default `true`
  - `provider_openai_enabled`: default `true`
  - `provider_gemini_enabled`: default `true`
- [ ] Create `LlmExecutionConfig` with:
  - `enum class ExecutionMode { API, CLI }`
  - `data class ProviderToggles(...)`
  - `fun from(store: RunStore): LlmExecutionConfig` (string parsing + defaults)
  - `fun isProviderEnabled(provider: String): Boolean`
- [ ] Add a small, shared boolean parser (`"true"/"false"`), treating missing as default.

### Phase 2: Settings page UI + legacy settings endpoints (~20%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — Modify

**Tasks:**
- [ ] Extend `GET /api/settings` response to include:
  - `fireworks_enabled` (existing)
  - `execution_mode` (`"api"` or `"cli"`)
  - `provider_anthropic_enabled` (boolean)
  - `provider_openai_enabled` (boolean)
  - `provider_gemini_enabled` (boolean)
- [ ] Update Settings page HTML (`viewSettings`) to include:
  - Execution mode control (radio group or segmented toggle)
  - Per-provider enable checkboxes
  - Optional “detected” indicator for CLI binaries (if implemented in Phase 4/5)
- [ ] Update `loadSettings()` JS wiring for the new controls.
- [ ] Ensure `saveSetting()` continues to serialize values as strings for SQLite persistence.

### Phase 3: Runtime adapter selection seam (~20%)

**Files:**
- `src/main/kotlin/attractor/llm/ClientProvider.kt` — Create (or similar)
- `src/main/kotlin/attractor/web/DotGenerator.kt` — Modify
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — Modify
- `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` — Modify (minimal seam)
- `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` — Modify (diagnoser uses seam)

**Tasks:**
- [ ] Introduce a `ClientProvider` abstraction that can produce a `Client` based on current settings:
  - `fun getClient(store: RunStore): Client`
  - internally selects adapters based on `execution_mode` and provider enable flags
- [ ] Update `DotGenerator` to accept a `RunStore` (or a `ClientProvider`) so it can:
  - choose an enabled provider/model pair
  - set `Request.provider` explicitly
  - stream via the correct adapter without relying on `Client.fromEnv()` defaults
- [ ] Update pipeline execution to resolve LLM behavior at call time:
  - `PipelineRunner` passes a `ClientProvider` (or lambda) to `LlmCodergenBackend` and `LlmFailureDiagnoser`
  - next node/tool round uses latest settings (satisfies “switch mid-use” requirement)

### Phase 4: CLI adapters + process runner seam (~25%)

**Files:**
- `src/main/kotlin/attractor/llm/adapters/ProcessRunner.kt` — Create
- `src/main/kotlin/attractor/llm/adapters/AnthropicCliAdapter.kt` — Create
- `src/main/kotlin/attractor/llm/adapters/OpenAICliAdapter.kt` — Create
- `src/main/kotlin/attractor/llm/adapters/GeminiCliAdapter.kt` — Create

**Tasks:**
- [ ] Create `ProcessRunner` interface for testability:
  - `fun run(args: List<String>, stdin: String? = null, env: Map<String,String> = emptyMap(), cwd: File? = null): ProcessResult`
  - streaming support (stdout/stderr readers)
- [ ] Implement CLI adapters that conform to `ProviderAdapter`:
  - `complete(request)`:
    - build CLI args for model selection (or omit if CLI-default)
    - provide a deterministic prompt format (system + conversation transcript)
    - capture stdout; non-zero exit → `ProviderError` with stderr excerpt
    - return `LlmResponse(Message.assistant(text))`
  - `stream(request)`:
    - run subprocess
    - read stdout incrementally and yield `StreamEvent(TEXT_DELTA, delta=...)`
    - on exit, yield `FINISH` (and include a synthesized `LlmResponse`)
- [ ] Implement binary detection (`claude`/`codex`/`gemini`) using a safe `ProcessBuilder` call (no shell injection).
- [ ] Define behavior when CLI is missing:
  - error message must name the missing binary and suggest installing it / adding to PATH.

### Phase 5: Provider/model selection rules (~10%)

**Files:**
- `src/main/kotlin/attractor/llm/ModelSelection.kt` — Create (or integrate into `LlmExecutionConfig`)
- `src/main/kotlin/attractor/web/DotGenerator.kt` — Modify (selection now consults settings)

**Tasks:**
- [ ] Implement `selectDefaultModel(store, env)` that:
  - filters providers by per-provider enabled flags
  - in API mode: requires API key presence for eligibility
  - in CLI mode: requires CLI binary presence for eligibility
  - chooses preferred provider order (keep existing priority: Anthropic → OpenAI → Gemini unless overridden)
  - returns `(provider, modelId)` so `Request.provider` is always explicit
- [ ] Define “all providers disabled/unavailable” error contract (HTTP 400 with helpful message).

### Phase 6: REST API v1 settings integration (~5%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — Modify

**Tasks:**
- [ ] Expand `KNOWN_SETTINGS` to include new keys:
  - `execution_mode`, `provider_anthropic_enabled`, `provider_openai_enabled`, `provider_gemini_enabled`
- [ ] Ensure `GET /api/v1/settings` reflects all keys and uses correct JSON types:
  - booleans as JSON booleans
  - `execution_mode` as a JSON string
- [ ] Validate `PUT /api/v1/settings/{key}` values:
  - `execution_mode` ∈ {`api`,`cli`}
  - provider toggles parse as boolean

### Phase 7: Tests (~5%)

**Files:**
- `src/test/kotlin/attractor/llm/LlmExecutionConfigTest.kt` — Create
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — Create
- `src/test/kotlin/attractor/llm/adapters/CliAdapterTest.kt` — Create
- `src/test/kotlin/attractor/web/SettingsEndpointsTest.kt` — Create or Modify existing REST tests

**Tasks:**
- [ ] Settings persistence tests:
  - defaults exist after `RunStore` init
  - roundtrip `setSetting`/`getSetting` for new keys
- [ ] Selection tests:
  - disabled providers excluded
  - CLI mode requires binary presence (using fake `ProcessRunner`)
  - API mode requires env key presence (injectable env seam or wrapper)
  - all disabled/unavailable → error
- [ ] CLI adapter tests:
  - streaming yields `TEXT_DELTA` events in-order
  - non-zero exit returns `ERROR` or throws `ProviderError` with stderr excerpt

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/db/RunStore.kt` | Modify | Add defaults for new persisted settings |
| `src/main/kotlin/attractor/llm/LlmExecutionConfig.kt` | Create | Parse and expose execution mode + provider toggles |
| `src/main/kotlin/attractor/llm/ClientProvider.kt` | Create | Resolve the active `Client` based on settings |
| `src/main/kotlin/attractor/llm/ModelSelection.kt` | Create | Central provider/model selection logic |
| `src/main/kotlin/attractor/llm/adapters/ProcessRunner.kt` | Create | Subprocess seam for CLI adapters + tests |
| `src/main/kotlin/attractor/llm/adapters/AnthropicCliAdapter.kt` | Create | `claude` CLI-backed ProviderAdapter |
| `src/main/kotlin/attractor/llm/adapters/OpenAICliAdapter.kt` | Create | `codex` CLI-backed ProviderAdapter |
| `src/main/kotlin/attractor/llm/adapters/GeminiCliAdapter.kt` | Create | `gemini` CLI-backed ProviderAdapter |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Settings UI + settings endpoints return new keys |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Consult settings; choose provider/model; use CLI in CLI mode |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Resolve LLM execution via settings-aware seam |
| `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` | Modify | Use settings-aware client resolution per call |
| `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` | Modify | Diagnoser uses settings-aware client resolution |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | `/api/v1/settings` includes new keys + validation |
| `src/test/kotlin/attractor/llm/LlmExecutionConfigTest.kt` | Create | Unit tests for parsing + defaults |
| `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` | Create | Unit tests for provider/model selection |
| `src/test/kotlin/attractor/llm/adapters/CliAdapterTest.kt` | Create | Unit tests for CLI streaming/error behavior |
| `src/test/kotlin/attractor/web/SettingsEndpointsTest.kt` | Create/Modify | Legacy + REST settings endpoint coverage |

## Definition of Done

- [ ] Settings page exposes `execution_mode` toggle (API/CLI) and per-provider enable toggles.
- [ ] Settings persist in SQLite and survive server restart.
- [ ] Provider disable reliably prevents selection and invocation (even if env keys exist).
- [ ] CLI mode invokes the correct CLI binary via subprocess for enabled providers.
- [ ] CLI adapters stream output via `StreamEventType.TEXT_DELTA` so existing streaming consumers remain compatible.
- [ ] REST API v1 settings endpoints reflect and update the new keys with validation.
- [ ] All new unit tests pass (`make test`).

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| CLI stdout buffering prevents “real-time” streaming | Medium | Medium | Prefer line/char streaming; document limitations; add keepalive flush; test with fake runner |
| CLI argument/model mapping differs across tools | Medium | Medium | Centralize per-provider arg building; start with “CLI default model” fallback; document supported models |
| Frequent SQLite reads for settings add overhead | Low | Low/Medium | Add tiny in-memory cache with short TTL (optional) or read-once per stage/tool-call |
| Subprocess execution security (arg injection, PATH issues) | Medium | High | Use `ProcessBuilder(args)` (no shell), validate args, avoid user-controlled binary paths by default |

## Security Considerations

- Do not invoke CLIs via a shell string; always use `ProcessBuilder` with an argv list.
- Do not allow settings to specify arbitrary binary paths in this sprint (unless explicitly scoped and sanitized).
- Clamp captured stderr/stdout size in error messages to avoid log injection and memory blowups.

## Dependencies

- External CLIs installed and discoverable on PATH (for CLI mode):
  - `claude`, `codex`, `gemini`
- No new Gradle dependencies.

## Open Questions

1. Should CLI adapters attempt to select models explicitly (mapping `ModelCatalog` IDs to CLI flags), or use CLI defaults initially?
2. If CLI mode is selected but a provider’s CLI is missing, should we:
   - hard error, or
   - fall back to API mode for that provider, or
   - fall back to another enabled provider?
3. Should the Settings page show per-provider “CLI detected” status (and if so, is that computed server-side or client-side)?
4. Should provider priority (Anthropic → OpenAI → Gemini) remain hardcoded or become a setting?

