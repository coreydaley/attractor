# Sprint 011 Merge Notes

## Claude Draft Strengths
- Clear 5-phase plan with good separation of concerns
- Settings page UI design with execution mode toggle + per-provider toggles well thought out
- Security rationale (no shell injection via `ProcessBuilder` argv list) explicitly called out
- `ClientFactory.fromSettings(store)` concept is clean and localizes adapter selection
- Risks table covers the important failure modes

## Codex Draft Strengths
- `LlmExecutionConfig` — typed data class with `enum class ExecutionMode { API, CLI }` and `ProviderToggles`; much cleaner than ad-hoc string reads in the factory
- `ProcessRunner` interface as a testability seam for CLI subprocess — critical for unit-testable CLI adapters without forking real processes
- Broader integration surface: explicitly includes `PipelineRunner`, `LlmCodergenBackend`, `FailureDiagnoser` so execution mode is truly global
- `ModelSelection.kt` as a dedicated module for provider/model selection logic (separates "which provider?" from "how to call it?")
- Stable provider keys (`"anthropic"`, `"openai"`, `"gemini"`) in all modes — adapter is an implementation detail behind those keys
- Phase 6 for REST API v1 (`RestApiRouter`) with validation of new setting values
- Phase 7 tests include `SettingsEndpointsTest` and `ModelSelectionTest` — covers more surface

## Valid Critiques Accepted

1. **Scope gap**: My draft only refactored `DotGenerator`. Codex correctly identifies that `PipelineRunner`, `LlmCodergenBackend`, and `LlmFailureDiagnoser` also construct LLM clients and must be wired through the same settings-aware seam. **Accepted — added to implementation plan.**

2. **DotGenerator call sites**: Converting to a class requires updating `RestApiRouter` too, not just `WebMonitorServer`. **Accepted — `RestApiRouter` added to the refactor task.**

3. **Provider key stability**: Using `"anthropic-cli"` as adapter name but `"anthropic"` as the `Request.provider` key creates confusion. API-vs-CLI should be behind a stable key. **Accepted — CLI adapters register under `"anthropic"`, `"openai"`, `"gemini"` (same keys as HTTP adapters).**

4. **Error type alignment**: `ApiError` doesn't exist in this codebase. Should use `ConfigurationError`, `ProviderError`, `SdkError`. **Accepted — replaced throughout.**

5. **CLI flags unverified**: Codex correctly flags that `-p` and `--model` flags are assumed. Added an explicit "verify CLI invocation" task early in the CLI adapter phase.

## Critiques Rejected (with reasoning)

- **`allSettings()` unnecessary**: Rejected as too prescriptive — the method may still be useful for the `/api/settings` GET endpoint which needs all keys in one response. The draft said "if not already present", which is a soft suggestion, not a requirement. No real harm in adding it.

## Interview Refinements Applied

1. **Hard error on missing CLI binary** — both drafts already converged on this; confirmed by interview.
2. **CLI detection badge in Settings** — added to Settings UI phase: server-side binary detection endpoint (`GET /api/settings/cli-status`) that checks PATH for each CLI binary; Settings page shows green/red indicator.
3. **Full CLI command template configurable per provider** — adds three new settings keys: `cli_anthropic_command` (default `"claude -p {prompt}"`), `cli_openai_command` (default `"codex -p {prompt}"`), `cli_gemini_command` (default `"gemini -p {prompt}"`). The `ProcessRunner` tokenizes the command template and substitutes `{prompt}`.

## Final Decisions

1. **New modules**: `LlmExecutionConfig.kt`, `ProcessRunner.kt`, `ClientProvider.kt`, `ModelSelection.kt` — taken from Codex draft (cleaner decomposition)
2. **Three CLI adapters** using `ProcessRunner` — taken from both drafts
3. **Settings keys**: `execution_mode`, `provider_anthropic_enabled`, `provider_openai_enabled`, `provider_gemini_enabled`, `cli_anthropic_command`, `cli_openai_command`, `cli_gemini_command` — extended from both drafts + interview
4. **CLI detection endpoint**: `GET /api/settings/cli-status` returning `{anthropic: bool, openai: bool, gemini: bool}` — from interview
5. **Global scope**: `DotGenerator`, `PipelineRunner`, `LlmCodergenBackend`, `LlmFailureDiagnoser` all wired through `ClientProvider` — from Codex critique
6. **Stable provider keys** in all modes — from Codex critique
7. **7-phase implementation** following Codex's more granular breakdown
