# Sprint 011: AI Provider Execution Mode & Per-Provider Toggles

## Overview

Today the application selects LLM providers entirely based on which API keys are present in the environment — there is no runtime control over which providers are active or how they are invoked. This sprint adds a **two-tier runtime configuration layer** surfaced through the existing Settings page:

1. **Global execution mode**: toggle between *Direct API* (current behaviour — HTTP REST calls via OkHttp) and *CLI subprocess* mode (invoke the `claude`, `codex`, or `gemini` CLI binaries via `ProcessBuilder`).
2. **Per-provider enable/disable toggles**: independently enable or disable Anthropic, OpenAI, and Gemini regardless of API key presence, so users can easily restrict which provider is used without unsetting environment variables.

Both settings persist in the existing SQLite `settings` table and take effect immediately on the next generation request — no server restart required.

The implementation introduces three CLI `ProviderAdapter` implementations (`AnthropicCliAdapter`, `OpenAICliAdapter`, `GeminiCliAdapter`) that satisfy the same `ProviderAdapter` interface as the existing HTTP adapters, a new `ClientFactory` object that builds the right `Client` based on runtime settings, a refactored `DotGenerator` (converted from `object` to `class`) that receives the `RunStore` and delegates client construction to the factory, and updated Settings page UI.

## Use Cases

1. **API mode with provider filter**: User has both `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` set but wants to force all generation through OpenAI. They disable the Anthropic toggle on Settings. Next pipeline run uses OpenAI.
2. **CLI mode — Anthropic only**: User wants generation to go through the `claude` CLI (e.g., because their organisation routes `claude` through a proxy). They set mode to CLI and leave only Anthropic enabled. DotGenerator invokes `claude -p "<prompt>"` via subprocess.
3. **All providers disabled**: User accidentally disables all three providers. The next generation attempt shows an error: *"No provider available. Enable at least one provider in Settings."*
4. **CLI binary not on PATH**: User selects CLI mode but `codex` is not installed. When OpenAI is selected as provider and invoked, a clear error is surfaced: *"CLI not found: codex. Install it or switch to API mode in Settings."*
5. **Settings persist across restart**: User sets CLI mode. Server restarts. CLI mode is still active — settings are read from SQLite on each request.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Settings Page (Web UI)                                          │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  Execution Mode:  [● API]  [ CLI]                        │   │
│  │  Providers:   [✓] Anthropic   [✓] OpenAI   [✓] Gemini   │   │
│  └──────────────────────────────────────────────────────────┘   │
└────────────────────────┬────────────────────────────────────────┘
                         │ setSetting() / getSetting()
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  RunStore (SQLite: settings table)                               │
│  execution_mode           = "api" | "cli"                        │
│  provider_anthropic_enabled = "true" | "false"                   │
│  provider_openai_enabled    = "true" | "false"                   │
│  provider_gemini_enabled    = "true" | "false"                   │
└────────────────────────┬────────────────────────────────────────┘
                         │ fromSettings(store)
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  ClientFactory (attractor.llm)                                   │
│  fromSettings(store: RunStore): Client                           │
│                                                                  │
│  mode = api  →  AnthropicAdapter, OpenAIAdapter, GeminiAdapter  │
│  mode = cli  →  AnthropicCliAdapter, OpenAICliAdapter,           │
│                 GeminiCliAdapter                                  │
│                                                                  │
│  (only enabled providers are registered)                         │
└────────────────────────┬────────────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────────────┐
│  DotGenerator(store: RunStore)                                   │
│  selectModel(store) — respects enabled providers                 │
│  generateStream() / generate() / iterateStream() / fixStream()  │
└────────────────────────┬────────────────────────────────────────┘
                         │
              ┌──────────┴──────────┐
              │                     │
   ┌──────────▼──────┐   ┌──────────▼──────────┐
   │  HTTP Adapters  │   │  CLI Adapters         │
   │  (existing)     │   │  ProcessBuilder +     │
   │                 │   │  stdout streaming     │
   └─────────────────┘   └──────────────────────┘
```

### CLI Adapter Design

CLI adapters satisfy `ProviderAdapter` using `ProcessBuilder`:

```
AnthropicCliAdapter.stream(request):
  val proc = ProcessBuilder("claude", "--model", request.model,
                            "-p", buildPromptString(request))
             .redirectErrorStream(false)
             .start()
  val reader = proc.inputStream.bufferedReader()
  return sequence {
      val buf = CharArray(256)
      var n: Int
      while (reader.read(buf).also { n = it } != -1) {
          yield(StreamEvent(type=TEXT_DELTA, delta=String(buf, 0, n)))
      }
      val exit = proc.waitFor()
      if (exit != 0) throw ApiError("claude exited with $exit: ${proc.errorStream.bufferedReader().readText()}")
  }
```

`complete()` collects the full sequence into a single `LlmResponse`.

### Settings Keys and Defaults

| Key | Type | Default | Values |
|-----|------|---------|--------|
| `execution_mode` | String | `"api"` | `"api"` \| `"cli"` |
| `provider_anthropic_enabled` | String | `"true"` | `"true"` \| `"false"` |
| `provider_openai_enabled` | String | `"true"` | `"true"` \| `"false"` |
| `provider_gemini_enabled` | String | `"true"` | `"true"` \| `"false"` |

All four are stored in the existing `settings` table with no schema changes.

## Implementation Plan

### Phase 1: Settings & UI (~15%)

**Files:**
- `src/main/kotlin/attractor/db/RunStore.kt` — add `allSettings(): Map<String, String>` for bulk read (if not already present); confirm `getSetting(key)` + `setSetting(key, value)` are sufficient
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — extend Settings page HTML + `/api/settings/update` handler

**Tasks:**
- [ ] Confirm all 4 new settings keys are handled by existing `getSetting`/`setSetting` (no schema change needed — they're just new keys)
- [ ] Add "Execution Mode" row to Settings page with two-button radio toggle (API / CLI style, matching existing fireworks toggle pattern)
- [ ] Add "Providers" section with three toggles: Anthropic, OpenAI, Gemini — using same checkbox pattern as `fireworks_enabled`
- [ ] Wire toggles to `/api/settings/update` POST endpoint with correct keys
- [ ] Ensure Sprint 010's `RestApiRouter` settings endpoints (`GET /api/v1/settings`, `PUT /api/v1/settings`) include the four new keys in responses

### Phase 2: CLI Adapters (~35%)

**Files:**
- `src/main/kotlin/attractor/llm/adapters/AnthropicCliAdapter.kt` — CREATE
- `src/main/kotlin/attractor/llm/adapters/OpenAICliAdapter.kt` — CREATE
- `src/main/kotlin/attractor/llm/adapters/GeminiCliAdapter.kt` — CREATE

**Tasks:**
- [ ] Define `AnthropicCliAdapter(binaryName: String = "claude")` implementing `ProviderAdapter`
  - `name` = `"anthropic-cli"`
  - `stream()`: `ProcessBuilder(binaryName, "--model", model, "-p", promptText)`, read stdout in chunks
  - `complete()`: drain stream sequence into single `LlmResponse`
  - `initialize()`: verify binary is on PATH (`ProcessBuilder(binaryName, "--version").start()` exits 0)
- [ ] Define `OpenAICliAdapter(binaryName: String = "codex")` implementing `ProviderAdapter`
  - `name` = `"openai-cli"`
  - Same pattern with `codex` binary (flag format TBD — document in adapter)
- [ ] Define `GeminiCliAdapter(binaryName: String = "gemini")` implementing `ProviderAdapter`
  - `name` = `"gemini-cli"`
  - Same pattern with `gemini` binary
- [ ] Shared helper: `buildPromptText(request: Request): String` — concatenates system + user messages into single string for CLI `--prompt` flag
- [ ] Error handling: `IOException` on `ProcessBuilder.start()` → throw `ConfigurationError("CLI binary not found: $binaryName. Install it or switch to API mode in Settings.")`
- [ ] Error handling: non-zero exit code → collect stderr, throw `ApiError`

### Phase 3: ClientFactory (~20%)

**Files:**
- `src/main/kotlin/attractor/llm/ClientFactory.kt` — CREATE

**Tasks:**
- [ ] Define `object ClientFactory`
- [ ] `fun fromSettings(store: RunStore): Client`:
  1. Read `execution_mode` (default `"api"`)
  2. Read `provider_anthropic_enabled` (default `"true"`), `provider_openai_enabled` (default `"true"`), `provider_gemini_enabled` (default `"true"`)
  3. Read env vars for API keys (unchanged logic)
  4. For each provider (Anthropic, OpenAI, Gemini), register the adapter if:
     - Provider is enabled in settings, AND
     - API key is present in env (API mode) OR CLI mode is selected
  5. Return `Client(providers, firstProvider)`
- [ ] `fun isProviderAvailable(providerName: String, store: RunStore): Boolean` — used by `selectModel()`
- [ ] Throw `ConfigurationError` if no providers are registered: *"No provider available. Enable at least one provider in Settings."*
- [ ] `fun fromEnv(): Client` — preserve as alias for `fromSettings` with a no-op store (backward compat for tests)

### Phase 4: DotGenerator Refactor (~15%)

**Files:**
- `src/main/kotlin/attractor/web/DotGenerator.kt` — MODIFY
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — MODIFY (construction site)

**Tasks:**
- [ ] Convert `object DotGenerator` → `class DotGenerator(private val store: attractor.db.RunStore)`
- [ ] Replace `Client.fromEnv()` calls with `ClientFactory.fromSettings(store)`
- [ ] Update `selectModel()` to `selectModel(store: RunStore)` — filter candidates by `isProviderAvailable(provider, store)`:
  ```kotlin
  fun selectModel(): String {
      if (ClientFactory.isProviderAvailable("anthropic", store)) return "claude-sonnet-4-6"
      if (ClientFactory.isProviderAvailable("openai", store))    return "gpt-5.2-mini"
      if (ClientFactory.isProviderAvailable("gemini", store))    return "gemini-3-flash-preview"
      throw IllegalStateException("No LLM provider available. Check Settings.")
  }
  ```
- [ ] Remove redundant API key presence checks (now handled by `ClientFactory`)
- [ ] Update `WebMonitorServer` constructor / init to instantiate `DotGenerator(store)` instead of `DotGenerator`

### Phase 5: Tests (~15%)

**Files:**
- `src/test/kotlin/attractor/llm/ClientFactoryTest.kt` — CREATE
- `src/test/kotlin/attractor/llm/CliAdapterTest.kt` — CREATE

**Tasks:**
- [ ] `ClientFactoryTest`:
  - `fromSettings` with no providers enabled → throws `ConfigurationError`
  - `fromSettings` in API mode → returns Client with HTTP adapters for enabled providers
  - `fromSettings` in CLI mode → returns Client with CLI adapters for enabled providers
  - `fromSettings` with a provider disabled → that provider's adapter is absent from Client
  - `isProviderAvailable` returns false for disabled provider
  - Use `MockRunStore` (in-memory implementation of store interface, or a simple wrapper)
- [ ] `CliAdapterTest`:
  - `initialize()` with missing binary → throws `ConfigurationError` (mock `ProcessBuilder` or test with known-absent binary name)
  - `stream()` with a mock process → emits TEXT_DELTA events for stdout content
  - `complete()` → collects stream into single LlmResponse with correct text
  - Non-zero exit code → throws `ApiError`

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/llm/adapters/AnthropicCliAdapter.kt` | Create | CLI adapter for `claude` binary |
| `src/main/kotlin/attractor/llm/adapters/OpenAICliAdapter.kt` | Create | CLI adapter for `codex` binary |
| `src/main/kotlin/attractor/llm/adapters/GeminiCliAdapter.kt` | Create | CLI adapter for `gemini` binary |
| `src/main/kotlin/attractor/llm/ClientFactory.kt` | Create | Builds `Client` from runtime settings |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Convert `object` → `class(store)`; use `ClientFactory` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Settings UI toggles; construct `DotGenerator(store)` |
| `src/test/kotlin/attractor/llm/ClientFactoryTest.kt` | Create | Unit tests for factory logic |
| `src/test/kotlin/attractor/llm/CliAdapterTest.kt` | Create | Unit tests for CLI adapters |

## Definition of Done

- [ ] Settings page shows "Execution Mode" toggle (API / CLI)
- [ ] Settings page shows per-provider toggles (Anthropic / OpenAI / Gemini)
- [ ] All four settings persist across server restarts (SQLite)
- [ ] In API mode: disabled providers are excluded from generation
- [ ] In CLI mode: generation invokes the correct CLI binary via subprocess
- [ ] Missing CLI binary produces a clear, actionable error message
- [ ] All-disabled state produces a clear error message
- [ ] `make test` passes (all existing + new tests green)
- [ ] No new Gradle dependencies added
- [ ] No compiler warnings

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| CLI stdout buffering when piped | High | Medium | Read in byte-array chunks rather than line-by-line; document that unbuffered streaming requires `stdbuf -oL` if needed |
| CLI flag interface differs per tool | Medium | High | Document exact flags per tool in adapter; make `binaryArgs` overridable in adapter constructor for future customisation |
| `object DotGenerator` → `class` breaks existing call sites | Low | Low | Only called from `WebMonitorServer`; one constructor change |
| CLI mode selected but no CLI binary installed | Medium | Low | `initialize()` check + clear error message surfaced to user |
| Settings keys absent on first run (cold start) | Low | Low | `getSetting()` returns null; factory defaults to `"api"` / `"true"` everywhere |

## Security Considerations

- CLI invocation uses `ProcessBuilder` with a fixed argument list — no shell interpolation, no injection risk from user-controlled prompt (prompt is passed as a separate argument, not concatenated into a shell string)
- Binary names are hardcoded defaults (`"claude"`, `"codex"`, `"gemini"`); not user-configurable in this sprint to avoid PATH injection
- No API keys are passed to CLI adapters (the CLI tools read keys from their own config/env)

## Dependencies

- Sprint 010 (REST API v1) — settings endpoints should reflect new keys; this sprint runs concurrent with Sprint 010 in_progress
- No external libraries required

## Open Questions

1. **CLI flag format**: Exact flag names for `codex` and `gemini` CLIs need to be verified during implementation. The draft uses `-p "<prompt>"` for all three by analogy with `claude`, but this may differ.
2. **Model flag**: Does each CLI support a `--model` flag? If not, the model selection in CLI mode falls back to the CLI tool's default.
3. **Fallback behaviour**: If CLI mode is selected but a provider's binary is missing, should we fall back to API mode silently, or surface an error? (This draft proposes surfacing an error — fail loudly.)
4. **System prompt handling**: CLI tools may not support a separate `--system` flag. If not, the system prompt must be prepended to the user prompt in `buildPromptText()`.
