# Sprint 011: AI Provider Execution Mode & Per-Provider Toggles

## Overview

Attractor currently selects LLM providers entirely based on which API keys are present in the environment — there is no runtime control over which providers are active or how they are invoked. This makes provider usage opaque and inflexible: users cannot switch execution mode at runtime, disable a provider without unsetting environment variables, or prefer CLI-based flows when official CLIs are already authenticated locally.

This sprint introduces a **two-tier runtime configuration layer** surfaced on the Settings page:

1. **Global execution mode**: toggle between *Direct API* (current behaviour — HTTP REST calls via OkHttp) and *CLI subprocess* mode (invoke the `claude`, `codex`, or `gemini` CLI binaries via `ProcessBuilder`).
2. **Per-provider enable/disable toggles**: independently enable or disable Anthropic, OpenAI, and Gemini, excluding a provider without unsetting its API key.
3. **Per-provider CLI command template**: customise the exact CLI invocation command for each provider (e.g., point to a non-standard binary path or pre-set flags).

All settings persist in the existing SQLite `settings` table and take effect immediately — no server restart required. The execution mode change is *global*: it affects DOT generation, pipeline codergen stages, and failure diagnosis, not just the Generate page.

The implementation adds a clean settings-aware seam (`LlmExecutionConfig`, `ClientProvider`, `ModelSelection`) and three CLI `ProviderAdapter` implementations (`AnthropicCliAdapter`, `OpenAICliAdapter`, `GeminiCliAdapter`) that satisfy the existing `ProviderAdapter` interface — keeping existing API-mode adapters entirely unchanged.

## Use Cases

1. **API mode with provider filter**: User has both `ANTHROPIC_API_KEY` and `OPENAI_API_KEY` set but wants to force all generation through OpenAI. They disable the Anthropic toggle on Settings. All subsequent generation uses OpenAI, regardless of API key presence.
2. **CLI mode — local auth**: User has `claude` CLI installed and authenticated via browser login (no API key env var needed). They switch to CLI mode in Settings. DOT generation and pipeline stages all invoke `claude` via subprocess.
3. **Custom CLI command**: User's `claude` binary is at a non-standard path. They set the Anthropic CLI command to `/opt/claude/bin/claude -p {prompt}` in Settings. The adapter substitutes `{prompt}` at call time.
4. **All providers disabled**: User accidentally disables all three providers. The next generation attempt shows: *"No provider available. Enable at least one provider in Settings."*
5. **CLI binary not on PATH**: User selects CLI mode but `codex` is not installed. Generation fails with: *"CLI not found: codex. Install it or add it to PATH, or switch to API mode in Settings."*
6. **Settings persist across restart**: User sets CLI mode. Server restarts. CLI mode is still active — settings are read from SQLite on each request.
7. **Settings page shows CLI detection**: User sees a green/red indicator next to each provider showing whether the CLI binary is currently reachable on PATH.

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  Settings Page (Web UI)                                               │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  Execution Mode:   [● API]  [ CLI]                              │ │
│  │  Providers:                                                     │ │
│  │    [✓] Anthropic  [CLI: claude -p {prompt}]  ● detected        │ │
│  │    [✓] OpenAI     [CLI: codex -p {prompt}]   ✗ not found       │ │
│  │    [✓] Gemini     [CLI: gemini -p {prompt}]  ● detected        │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ POST /api/settings/update
                                │ GET  /api/settings/cli-status (detection)
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  RunStore (SQLite: settings table)                                    │
│  execution_mode           = "api" | "cli"        (default: "api")    │
│  provider_anthropic_enabled = "true" | "false"  (default: "true")    │
│  provider_openai_enabled    = "true" | "false"  (default: "true")    │
│  provider_gemini_enabled    = "true" | "false"  (default: "true")    │
│  cli_anthropic_command    = "<template>"  (default: "claude -p {prompt}") │
│  cli_openai_command       = "<template>"  (default: "codex -p {prompt}")  │
│  cli_gemini_command       = "<template>"  (default: "gemini -p {prompt}") │
└───────────────────────────────┬──────────────────────────────────────┘
                                │ LlmExecutionConfig.from(store)
                                ▼
┌──────────────────────────────────────────────────────────────────────┐
│  LlmExecutionConfig                                                   │
│  enum class ExecutionMode { API, CLI }                                │
│  data class ProviderToggles(anthropic, openai, gemini: Boolean)       │
│  data class CliCommands(anthropic, openai, gemini: String)            │
│  fun isProviderEnabled(name: String): Boolean                         │
└───────────────────────────────┬──────────────────────────────────────┘
                                │
                     ┌──────────┴──────────┐
                     │                     │
            ┌────────▼──────┐    ┌─────────▼────────┐
            │ ModelSelection │    │  ClientProvider   │
            │ selectModel(  │    │  getClient(config)│
            │   config, env)│    │  → Client(        │
            │ → (provider,  │    │    API adapters    │
            │    modelId)   │    │    OR CLI adapters)│
            └───────────────┘    └─────────┬─────────┘
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                       │
          ┌─────────▼──────┐   ┌───────────▼─────┐   ┌───────────▼─────┐
          │  DotGenerator  │   │ LlmCodergenBackend│   │LlmFailureDiag-  │
          │  (class+store) │   │ (pipeline stages) │   │noser (diag runs)│
          └────────────────┘   └──────────────────┘   └─────────────────┘
```

### CLI Adapter Design

CLI adapters implement `ProviderAdapter` using an injectable `ProcessRunner` interface for testability:

```
interface ProcessRunner {
    fun start(args: List<String>): Process
}

class AnthropicCliAdapter(
    private val commandTemplate: String = "claude -p {prompt}",
    private val runner: ProcessRunner = DefaultProcessRunner
) : ProviderAdapter {
    override val name = "anthropic"

    override fun stream(request: Request): Sequence<StreamEvent> {
        val args = buildArgs(commandTemplate, request)
        val proc = runner.start(args)
        return sequence {
            val buf = CharArray(256)
            val reader = proc.inputStream.bufferedReader()
            var n: Int
            while (reader.read(buf).also { n = it } != -1) {
                yield(StreamEvent(type = TEXT_DELTA, delta = String(buf, 0, n)))
            }
            val exit = proc.waitFor()
            if (exit != 0) {
                val stderr = proc.errorStream.bufferedReader().readText().take(2048)
                throw ProviderError("claude exited with code $exit: $stderr")
            }
        }
    }
}
```

Provider keys remain stable (`"anthropic"`, `"openai"`, `"gemini"`) regardless of API or CLI mode — the mode is an implementation detail behind those keys.

## Implementation Plan

### Phase 1: Settings Data Model (~10%)

**Files:**
- `src/main/kotlin/attractor/llm/LlmExecutionConfig.kt` — CREATE

**Tasks:**
- [ ] Define `enum class ExecutionMode { API, CLI }`
- [ ] Define `data class LlmExecutionConfig(mode, providerToggles, cliCommands)` with:
  - `fun isProviderEnabled(name: String): Boolean`
  - `companion object fun from(store: RunStore): LlmExecutionConfig` — reads all 7 settings with defaults
- [ ] Boolean parser for `"true"`/`"false"` strings (treat `null` as `true`)
- [ ] Defaults: `mode=API`, all providers enabled, CLI commands as documented above

### Phase 2: ProcessRunner & CLI Adapters (~25%)

**Files:**
- `src/main/kotlin/attractor/llm/adapters/ProcessRunner.kt` — CREATE
- `src/main/kotlin/attractor/llm/adapters/AnthropicCliAdapter.kt` — CREATE
- `src/main/kotlin/attractor/llm/adapters/OpenAICliAdapter.kt` — CREATE
- `src/main/kotlin/attractor/llm/adapters/GeminiCliAdapter.kt` — CREATE

**Tasks:**
- [ ] `ProcessRunner` interface with `fun start(args: List<String>): Process` + `object DefaultProcessRunner`
- [ ] `buildArgs(commandTemplate: String, request: Request): List<String>`:
  - Tokenize template (split on whitespace, preserving quoted segments)
  - Substitute `{prompt}` with the concatenated system+user message string
  - If no `{prompt}` token found, append prompt as last arg
- [ ] `AnthropicCliAdapter(commandTemplate, runner)`:
  - `initialize()`: run `<binary> --version` (or first token of template + `--version`), throw `ConfigurationError` if binary not found
  - `stream()`: start process, read stdout in 256-char chunks as `TEXT_DELTA` events; non-zero exit → throw `ProviderError` with stderr excerpt (max 2048 chars)
  - `complete()`: collect stream, return `LlmResponse`
- [ ] `OpenAICliAdapter` and `GeminiCliAdapter` — same pattern with their defaults
- [ ] Shared `buildPromptText(request: Request): String` — concatenates system and user messages into single string for CLI `{prompt}` substitution

### Phase 3: ClientProvider & ModelSelection (~15%)

**Files:**
- `src/main/kotlin/attractor/llm/ClientProvider.kt` — CREATE
- `src/main/kotlin/attractor/llm/ModelSelection.kt` — CREATE

**Tasks:**
- [ ] `object ClientProvider`:
  - `fun getClient(config: LlmExecutionConfig, env: Map<String, String> = System.getenv()): Client`
  - For each enabled provider: if API mode → register HTTP adapter (if API key present), if CLI mode → register CLI adapter
  - If no providers registered → throw `ConfigurationError("No provider available. Enable at least one provider in Settings.")`
- [ ] `object ModelSelection`:
  - `fun selectModel(config: LlmExecutionConfig, env: Map<String, String> = System.getenv()): Pair<String, String>` → `(providerName, modelId)`
  - Priority order: Anthropic → OpenAI → Gemini
  - In API mode: provider eligible only if enabled AND API key present
  - In CLI mode: provider eligible only if enabled (binary presence checked at `initialize()` time)
  - Returns `("anthropic", "claude-sonnet-4-6")`, `("openai", "gpt-5.2-mini")`, or `("gemini", "gemini-3-flash-preview")` for first eligible

### Phase 4: LLM Call Site Integration (~20%)

**Files:**
- `src/main/kotlin/attractor/web/DotGenerator.kt` — MODIFY
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — MODIFY (construction)
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — MODIFY (construction)
- `src/main/kotlin/attractor/web/PipelineRunner.kt` — MODIFY
- `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` — MODIFY
- `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` — MODIFY

**Tasks:**
- [ ] Convert `object DotGenerator` → `class DotGenerator(private val store: RunStore)`:
  - Replace `Client.fromEnv()` + `selectModel()` with `ClientProvider.getClient(config)` and `ModelSelection.selectModel(config)` where `config = LlmExecutionConfig.from(store)`
  - Remove redundant API key env-var checks (now handled by `ClientProvider`)
  - Update `WebMonitorServer` and `RestApiRouter` to construct and share a `DotGenerator(store)` instance
- [ ] `PipelineRunner`: pass a `RunStore` reference (or `LlmExecutionConfig` lambda) through to `LlmCodergenBackend` and `FailureDiagnoser` so their LLM calls respect runtime settings
- [ ] `LlmCodergenBackend`: accept a `ClientProvider`-style lambda/dependency and use it at call time (reads fresh config per call)
- [ ] `FailureDiagnoser`: same — replace `Client.fromEnv()` call with config-aware resolution

### Phase 5: Settings UI & Endpoints (~15%)

**Files:**
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — MODIFY

**Tasks:**
- [ ] Extend `GET /api/settings` response to include all 7 new keys with correct types
- [ ] Add `GET /api/settings/cli-status` endpoint:
  - Server-side: attempt `ProcessBuilder(<binary>, "--version").start()`, check exit code for each CLI binary
  - Response: `{"anthropic": true, "openai": false, "gemini": true}` (detected / not found)
- [ ] Update Settings page HTML (`viewSettings`):
  - "Execution Mode" row: two-button toggle (API / CLI), using same pattern as `fireworks_enabled`
  - "Providers" section: three rows (Anthropic, OpenAI, Gemini), each with:
    - Enable/disable checkbox
    - CLI command text input field (shown when mode is CLI; pre-filled with stored or default value)
    - Detection badge (green dot = detected, red X = not found — populated via `/api/settings/cli-status`)
  - Detection badge loaded on page open and after mode toggle
- [ ] Update `loadSettings()` JS to populate all controls
- [ ] Wire CLI command input fields to `POST /api/settings/update` on blur/change

### Phase 6: REST API v1 Integration (~5%)

**Files:**
- `src/main/kotlin/attractor/web/RestApiRouter.kt` — MODIFY

**Tasks:**
- [ ] Add all 7 new keys to `KNOWN_SETTINGS` (or equivalent allow-list)
- [ ] `GET /api/v1/settings`:
  - Return `execution_mode` as JSON string (`"api"` | `"cli"`)
  - Return `provider_*_enabled` as JSON booleans
  - Return `cli_*_command` as JSON strings
- [ ] `PUT /api/v1/settings/{key}` validation:
  - `execution_mode`: must be `"api"` or `"cli"`; reject others with 400
  - `provider_*_enabled`: must be `"true"` or `"false"`; reject others with 400
  - `cli_*_command`: non-empty string; reject blank with 400

### Phase 7: Tests (~10%)

**Files:**
- `src/test/kotlin/attractor/llm/LlmExecutionConfigTest.kt` — CREATE
- `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` — CREATE
- `src/test/kotlin/attractor/llm/adapters/CliAdapterTest.kt` — CREATE
- `src/test/kotlin/attractor/web/SettingsEndpointsTest.kt` — CREATE

**Tasks:**
- [ ] `LlmExecutionConfigTest`:
  - Defaults when no settings present
  - Roundtrip `setSetting`/`from(store)` for all 7 keys
  - `isProviderEnabled` returns false for explicitly disabled provider
- [ ] `ModelSelectionTest`:
  - All providers enabled (API mode) → Anthropic wins (if key present)
  - Anthropic disabled → OpenAI wins
  - All providers disabled → `ConfigurationError`
  - CLI mode + enabled provider → returns that provider (no key check)
  - API mode + no API keys → `ConfigurationError`
- [ ] `CliAdapterTest` (using `FakeProcessRunner`):
  - `stream()` yields `TEXT_DELTA` events in order for stdout bytes
  - Non-zero exit → `ProviderError` with stderr excerpt
  - `initialize()` throws `ConfigurationError` when fake runner simulates binary-not-found
  - `{prompt}` substitution in command template
- [ ] `SettingsEndpointsTest`:
  - `GET /api/settings` includes all 7 new keys
  - `GET /api/settings/cli-status` returns boolean map
  - `POST /api/settings/update` persists new keys
  - `PUT /api/v1/settings/execution_mode` validates `"api"` / `"cli"` values

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/main/kotlin/attractor/llm/LlmExecutionConfig.kt` | Create | Typed execution config + parsing from `RunStore` |
| `src/main/kotlin/attractor/llm/ClientProvider.kt` | Create | Builds `Client` with API or CLI adapters per settings |
| `src/main/kotlin/attractor/llm/ModelSelection.kt` | Create | Provider/model selection respecting enables and mode |
| `src/main/kotlin/attractor/llm/adapters/ProcessRunner.kt` | Create | Subprocess abstraction (testability seam) |
| `src/main/kotlin/attractor/llm/adapters/AnthropicCliAdapter.kt` | Create | `claude` CLI-backed `ProviderAdapter` |
| `src/main/kotlin/attractor/llm/adapters/OpenAICliAdapter.kt` | Create | `codex` CLI-backed `ProviderAdapter` |
| `src/main/kotlin/attractor/llm/adapters/GeminiCliAdapter.kt` | Create | `gemini` CLI-backed `ProviderAdapter` |
| `src/main/kotlin/attractor/web/DotGenerator.kt` | Modify | Convert `object` → `class(store)`; use `ClientProvider` + `ModelSelection` |
| `src/main/kotlin/attractor/web/WebMonitorServer.kt` | Modify | Settings UI; CLI detection endpoint; construct `DotGenerator(store)` |
| `src/main/kotlin/attractor/web/RestApiRouter.kt` | Modify | Construct `DotGenerator(store)`; expand settings allow-list + validation |
| `src/main/kotlin/attractor/web/PipelineRunner.kt` | Modify | Route LLM calls through settings-aware seam |
| `src/main/kotlin/attractor/handlers/LlmCodergenBackend.kt` | Modify | Accept settings-aware client dependency |
| `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` | Modify | Replace `Client.fromEnv()` with config-aware resolution |
| `src/test/kotlin/attractor/llm/LlmExecutionConfigTest.kt` | Create | Parsing + defaults unit tests |
| `src/test/kotlin/attractor/llm/ModelSelectionTest.kt` | Create | Provider selection logic unit tests |
| `src/test/kotlin/attractor/llm/adapters/CliAdapterTest.kt` | Create | CLI adapter streaming + error unit tests |
| `src/test/kotlin/attractor/web/SettingsEndpointsTest.kt` | Create | Settings HTTP endpoint coverage |

## Definition of Done

- [ ] Settings page shows "Execution Mode" toggle (API / CLI)
- [ ] Settings page shows per-provider enable/disable toggles (Anthropic, OpenAI, Gemini)
- [ ] Settings page shows CLI command template fields per provider (visible in CLI mode)
- [ ] Settings page shows CLI detection badge (detected / not found) per provider
- [ ] All 7 settings persist in SQLite and survive server restart
- [ ] In API mode: disabled providers are never selected, even if API key is present
- [ ] In CLI mode: generation invokes the correct CLI binary via subprocess for enabled providers
- [ ] CLI command template `{prompt}` substitution works correctly
- [ ] Missing CLI binary → clear, actionable `ConfigurationError` surfaced to user
- [ ] All-providers-disabled → clear error message
- [ ] Execution mode change is global: affects `DotGenerator`, `LlmCodergenBackend`, `FailureDiagnoser`
- [ ] `GET /api/v1/settings` returns all 7 keys with correct types
- [ ] `PUT /api/v1/settings/{key}` validates `execution_mode` and provider toggle values
- [ ] `make test` passes (all existing + new tests green)
- [ ] No new Gradle dependencies added
- [ ] No compiler warnings

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| CLI stdout buffering prevents real-time streaming | Medium | Medium | Read in byte-array chunks; document that "real-time" streaming requires unbuffered CLI output; `FakeProcessRunner` in tests avoids real subprocess dependency |
| CLI flag format differs per tool (flags unknown at sprint time) | Medium | High | Add explicit "verify CLI invocation" task first; design adapters with `{prompt}` template + fallback (append raw to args if no token found) |
| `DotGenerator object → class` breaks call sites | Low | Low | Both `WebMonitorServer` and `RestApiRouter` are in scope; one shared instance constructed at server init |
| Frequent SQLite reads for settings add overhead | Low | Low | Read once per generation call; SQLite reads are fast; defer caching to a future sprint if profiling shows impact |
| Subprocess execution security | Low | High | Use `ProcessBuilder` with argv list (no shell); do not allow arbitrary `PATH` injection; clamp stderr capture to 2048 chars |

## Security Considerations

- CLI adapters use `ProcessBuilder` with an explicit argument list — no shell interpolation. Prompts passed as positional argument values, not shell strings.
- CLI command templates are stored as user-configurable settings but are not executed via a shell (`ProcessBuilder` tokenizes them as an argv list). This means shell metacharacters are inert.
- Binary names derived from templates are not validated against a whitelist in this sprint — a future sprint should add that if the threat model requires it.
- Stderr captured from CLI processes is truncated to 2048 characters to prevent memory blowup from unexpectedly verbose CLIs.

## Dependencies

- Sprint 010 (REST API v1 — in_progress): `RestApiRouter.kt` must exist; this sprint extends its settings section
- External CLIs required for CLI mode (not required for API mode or for tests):
  - `claude` (Anthropic) — https://github.com/anthropics/claude-cli
  - `codex` (OpenAI) — https://github.com/openai/codex
  - `gemini` (Google) — https://github.com/google-gemini/gemini-cli

## Open Questions

1. **CLI model flag support**: Do `codex` and `gemini` CLIs support a `--model` flag? If not, the command template should omit it; the CLI uses its configured default. Verify during Phase 2 implementation.
2. **System prompt handling in CLI mode**: If CLI tools do not support a separate system prompt flag, `buildPromptText()` must prepend the system message to the user prompt in the `{prompt}` substitution. Document per-adapter behaviour.
3. **Provider priority order**: Currently hardcoded Anthropic → OpenAI → Gemini in `ModelSelection`. A future sprint could expose this as a settings drag-order, but it's out of scope here.
