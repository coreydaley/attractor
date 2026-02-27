# Critique: SPRINT-011-CLAUDE-DRAFT

## Overall Assessment

The draft aligns well with the Sprint 011 intent at the UI/settings level and sketches a plausible CLI adapter approach, but it currently under-scopes the execution-layer changes needed for “global execution mode” to affect *all* LLM usage in the app (not just DOT generation). There are also a few concrete mismatches with the current codebase (call sites, error types, provider naming) that will cause implementation churn if not corrected up front.

## High-Priority Findings

1. **Scope gap: pipeline execution + failure diagnosis are not wired to settings**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:12`, `:162-182`
   - The plan refactors `DotGenerator` to use `ClientFactory`, but does not address other LLM call sites that currently construct clients from env at runtime (pipeline codergen + failure diagnoser). As written, toggling `execution_mode` and provider enables would not reliably impact pipeline stages, which conflicts with the “global execution mode” intent and “next generation uses updated setting” success criteria.
   - Fix: add explicit tasks/files to route `PipelineRunner`’s backend selection and `LlmFailureDiagnoser` client construction through the same settings-aware factory/seam.

2. **`DotGenerator object → class` refactor is incomplete (Rest API v1 still calls `DotGenerator` statically)**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:162-182`
   - `DotGenerator` is invoked from both the legacy server routes and the REST API router; converting it to a class requires updating *all* call sites (not just `WebMonitorServer`), or introducing an injected instance shared by both.
   - Fix: explicitly include `src/main/kotlin/attractor/web/RestApiRouter.kt` in the DotGenerator refactor phase (or keep `DotGenerator` as an `object` and pass a `RunStore`/factory into methods).

3. **Provider naming/keying is inconsistent between selection logic and adapter registration**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:128-138`, `:149-159`, `:171-177`
   - The draft assigns CLI adapter `name` values like `"anthropic-cli"` but the rest of the plan (selection + availability checks) uses provider names like `"anthropic"`, `"openai"`, `"gemini"`. In this codebase, `Client` dispatch is keyed by the provider map keys and `Request.provider`, not the adapter’s `name` field.
   - Fix: keep the provider keys stable (`"anthropic"`, `"openai"`, `"gemini"`) regardless of API/CLI mode, and treat API-vs-CLI as an implementation detail behind those stable keys.

4. **Error types referenced in the draft don’t exist in the codebase**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:88-89`, `:141-142`, `:201`
   - The draft uses `ApiError`, but the current LLM layer uses errors like `ConfigurationError`, `ProviderError`, `NetworkError`, `SdkError`.
   - Fix: align adapter behavior + tests to the existing error taxonomy (or explicitly introduce a new error type and update call sites accordingly).

5. **CLI invocation flags are assumed rather than planned as a verified contract**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:17`, `:70-90`, `:120-142`, `:252-255`
   - The plan hardcodes `--model` and `-p` usage across all three CLIs. The OpenAI `codex` and Google `gemini` CLIs may not support those flags, and behavior may differ (stdin vs flag, multi-message vs single prompt, streaming behavior).
   - Fix: make “verify exact CLI invocation” an explicit early task (even if only by manual testing), and design adapters to tolerate differences (stdin fallback, “CLI default model” fallback, and clear docs about supported flags).

## Medium-Priority Findings

1. **Legacy settings API work is under-specified**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:107-118`
   - `/api/settings/update` already writes arbitrary keys; the work is mainly `GET /api/settings` response shape + Settings UI wiring. The draft mentions extending the update handler but doesn’t call out the need to extend the GET payload and client-side `loadSettings()` behavior.

2. **REST API v1 settings integration needs concrete touchpoints**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:118`
   - `RestApiRouter` currently constrains settings to a known-key set and returns values as JSON strings. The plan should specify updating the known-key list, plus type/validation rules (booleans vs string enum for `execution_mode`) so the REST API reflects the UI toggles reliably.

3. **`RunStore.allSettings()` may be unnecessary**
   - Reference: `docs/sprints/drafts/SPRINT-011-CLAUDE-DRAFT.md:109-110`
   - The current `getSetting`/`setSetting` are sufficient for the UI toggles; adding a bulk read API only makes sense if the REST API wants “all settings” in one round trip.

## Suggested Edits Before Implementation

1. Expand scope to include non-DotGenerator LLM call sites (pipeline backend selection + failure diagnoser) so the “global execution mode” is actually global.
2. Decide whether `DotGenerator` remains an `object` with injected dependencies per call, or becomes a `class`—and update *both* `WebMonitorServer` and `RestApiRouter` accordingly.
3. Standardize on stable provider keys (`anthropic`/`openai`/`gemini`) and keep API vs CLI behind that stable interface.
4. Replace `ApiError` references with the existing error types, and specify whether streaming errors are yielded as events or thrown as exceptions.
5. Add an explicit “verify CLI flags per provider” task and design adapters with fallbacks (stdin, default model) to avoid blocking the sprint on CLI-specific differences.

## Bottom Line

The draft is close to sprint-ready for the UI/settings portion, but it needs one pass to (1) widen the integration surface beyond `DotGenerator`, and (2) correct several concrete codebase mismatches (provider keying, missing error types, and incomplete refactor call sites). Once those are addressed, the plan should be executable with lower risk.

