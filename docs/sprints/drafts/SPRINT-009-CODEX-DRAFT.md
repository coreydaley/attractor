# Sprint 009: Critical Path Unit Test Coverage

## Overview

Sprint 007 and Sprint 008 added important engine behavior (diagnosis/repair and runtime optimizations),
but several high-leverage modules still lack dedicated unit coverage. The current suite is strong in parser,
condition evaluation, engine flow, linting, and pipeline-state behavior, yet key deterministic components
that drive routing, retry timing, checkpoint persistence, and transformation correctness are still effectively
protected only by indirect tests.

This sprint closes those gaps by adding focused, hermetic unit tests for each uncovered critical path listed
in the Sprint 009 intent. The goal is not broad integration expansion; it is explicit verification of edge
selection semantics, backoff bounds, checkpoint roundtrip/corrupt handling, context coercion/thread safety,
stylesheet specificity behavior, variable expansion no-op behavior, and LLM diagnosis response parsing fallbacks
without any network calls.

The result should be a tighter safety net around pure logic and persistence boundaries so future refactors in
engine/state/style layers can move quickly with lower regression risk.

## Use Cases

1. **Routing regressions are caught immediately**: Changes to `EdgeSelector` no longer silently alter branch selection priority.
2. **Retry behavior remains bounded and predictable**: Backoff delay calculations and jitter windows are validated without sleep-based tests.
3. **Resume semantics stay reliable**: `Checkpoint.save()`/`load()` roundtrip and corrupt/missing file behavior are explicitly guarded.
4. **Shared state helpers stay safe under concurrency**: `Context` coercion and atomic increment semantics are verified with concurrent updates.
5. **Model styling remains deterministic**: CSS-like selector precedence (`*` < `.class` < `#id`) and parser resilience stay covered.
6. **Goal variable injection remains stable**: `$goal` replacement behavior in prompts/labels, including blank-goal no-op, is enforced.
7. **Diagnosis parsing degrades safely**: `LlmFailureDiagnoser` parsing handles valid JSON, preambles, and malformed outputs predictably.

## Architecture

### Coverage Target Map

```text
engine/
  EdgeSelector.kt            -> EdgeSelectorTest.kt
  RetryPolicy.kt             -> RetryPolicyTest.kt
  FailureDiagnoser.kt        -> LlmFailureDiagnoserTest.kt (parse/fallback only; stub client)

state/
  Checkpoint.kt              -> CheckpointTest.kt
  Context.kt                 -> ContextTest.kt

style/
  Stylesheet.kt              -> StylesheetTest.kt

transform/
  VariableExpansionTransform.kt -> VariableExpansionTransformTest.kt
```

### Test Strategy

```text
Unit tests only
  -> deterministic inputs/outputs
  -> temp directories for filesystem behaviors
  -> no HTTP ports, no live LLM/API calls
  -> no reliance on wall-clock sleeps for backoff tests
```

### Guardrail Principles

- Prefer direct behavioral assertions over implementation-coupled assertions.
- Keep each test file scoped to one module family.
- Use existing Kotest `FunSpec` + `io.kotest` matcher conventions.
- Avoid new production dependencies; test-only code/helpers if needed.

## Implementation Plan

### Phase 1: Edge Selection and Retry Math Coverage (~20%)

**Files:**
- `src/test/kotlin/attractor/engine/EdgeSelectorTest.kt` (new)
- `src/test/kotlin/attractor/engine/RetryPolicyTest.kt` (new)

**Tasks:**
- [ ] Add `EdgeSelector` tests for full priority chain: condition match, preferred label, suggested next IDs, unconditional weight+lexical fallback, no-edges `null`.
- [ ] Add `normalizeLabel()` tests for bracket, `K)`, `K -`, whitespace/case normalization.
- [ ] Add `BackoffConfig.delayForAttempt()` tests for deterministic growth when `jitter=false`.
- [ ] Add jitter-band tests asserting each sampled delay stays within `[0.5x, 1.5x]` of the non-jitter base and capped by `maxDelayMs` before jitter.
- [ ] Add `RetryPolicy.fromNode()` tests for explicit `max_retries` override vs graph default fallback and minimum-attempt floor.

### Phase 2: Checkpoint Persistence Coverage (~15%)

**Files:**
- `src/test/kotlin/attractor/state/CheckpointTest.kt` (new)

**Tasks:**
- [ ] Add roundtrip save/load test using temp directory (`Files.createTempDirectory`) validating timestamp/current node/completed nodes/retries/context/logs/stage durations.
- [ ] Add `load()` tests returning `null` for missing checkpoint file.
- [ ] Add `load()` tests returning `null` for corrupt or non-JSON checkpoint payload.
- [ ] Add `Checkpoint.create()` test validating extraction of `internal.retry_count.*` keys and context snapshot propagation.
- [ ] Assert saved checkpoint JSON is compact/non-empty and parseable (behavioral check, not strict formatting coupling).

### Phase 3: Context Semantics and Concurrency Coverage (~20%)

**Files:**
- `src/test/kotlin/attractor/state/ContextTest.kt` (new)

**Tasks:**
- [ ] Add CRUD/snapshot/log tests (`set`, `get`, `contains`, `remove`, `appendLog`, `logs`, `snapshot`).
- [ ] Add `getInt()` coercion tests: numeric values, numeric strings (`"42"`), invalid strings/default fallback.
- [ ] Add `incrementInt()` tests for absent keys, numeric-string keys, non-numeric fallback path.
- [ ] Add `clone()` isolation tests ensuring cloned context mutations do not back-write into source.
- [ ] Add bounded concurrency test using thread pool/latches to validate atomic `incrementInt()` totals across many increments.

### Phase 4: Stylesheet and Transform Coverage (~20%)

**Files:**
- `src/test/kotlin/attractor/style/StylesheetTest.kt` (new)
- `src/test/kotlin/attractor/transform/VariableExpansionTransformTest.kt` (new)

**Tasks:**
- [ ] Add `StylesheetParser` parse tests for universal/class/id selectors and declaration parsing with quoted/unquoted values.
- [ ] Add selector specificity tests confirming `#id` overrides `.class` overrides `*` when property is not explicitly set on node.
- [ ] Add explicit-node-attribute precedence tests confirming stylesheet does not overwrite pre-existing node attrs.
- [ ] Add malformed/partial stylesheet tests verifying parser degrades without throwing and only applies valid rules.
- [ ] Add `VariableExpansionTransform` tests for prompt and label substitution, untouched attributes when `$goal` absent, and no-op when graph goal is blank.

### Phase 5: Failure Diagnoser Parsing Coverage (~15%)

**Files:**
- `src/test/kotlin/attractor/engine/LlmFailureDiagnoserTest.kt` (new)
- `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` (modify, testability seam only if needed)

**Tasks:**
- [ ] Implement hermetic diagnoser tests with a stub `Client` response path (no network/API keys).
- [ ] Cover valid JSON response parsing to `DiagnosisResult`.
- [ ] Cover JSON-with-preamble/extraneous text extraction fallback.
- [ ] Cover malformed/no-JSON responses producing `ABORT` parse-error diagnosis.
- [ ] If direct parse-path testing is hard due to private visibility, add minimal testability seam (e.g., internal helper) without changing runtime behavior.

### Phase 6: Suite Validation and Regression Pass (~10%)

**Files:**
- `src/test/kotlin/attractor/engine/EngineTest.kt` (existing, unchanged unless overlap cleanup needed)
- `src/test/kotlin/attractor/**` (new files above)

**Tasks:**
- [ ] Run full test suite and confirm all new and existing tests pass.
- [ ] Remove/avoid redundant assertions in `EngineTest` that are now covered by dedicated module tests where appropriate.
- [ ] Verify no tests use network calls, shared mutable global state, or fixed ports.
- [ ] Verify intent build command succeeds with zero failures.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/test/kotlin/attractor/engine/EdgeSelectorTest.kt` | Create | Dedicated coverage for routing algorithm priority and label normalization |
| `src/test/kotlin/attractor/engine/RetryPolicyTest.kt` | Create | Verify retry-attempt mapping and backoff/jitter bounds |
| `src/test/kotlin/attractor/state/CheckpointTest.kt` | Create | Validate checkpoint roundtrip, missing/corrupt file handling, create() mapping |
| `src/test/kotlin/attractor/state/ContextTest.kt` | Create | Validate coercion, atomic increment, clone isolation, and thread safety |
| `src/test/kotlin/attractor/style/StylesheetTest.kt` | Create | Verify parser behavior, selector specificity, and non-overwrite semantics |
| `src/test/kotlin/attractor/transform/VariableExpansionTransformTest.kt` | Create | Verify `$goal` substitution and blank-goal no-op behavior |
| `src/test/kotlin/attractor/engine/LlmFailureDiagnoserTest.kt` | Create | Verify diagnosis parsing and fallback logic without live LLM calls |
| `src/main/kotlin/attractor/engine/FailureDiagnoser.kt` | Modify (optional/minimal) | Add testability seam only if needed to isolate parse behavior |

## Definition of Done

- [ ] Every module in Sprint 009 intent has at least one dedicated test file.
- [ ] `EdgeSelector` 5-step routing behavior is covered by direct unit assertions.
- [ ] `BackoffConfig` delay behavior is validated for capped growth and jitter bounds.
- [ ] `Checkpoint.load()` missing/corrupt behavior is explicitly tested (`null` expected).
- [ ] `Context.getInt()` coercion and `incrementInt()` concurrency behavior are explicitly tested.
- [ ] `Stylesheet` selector specificity and non-overwrite rules are explicitly tested.
- [ ] `VariableExpansionTransform` blank-goal no-op behavior is explicitly tested.
- [ ] `LlmFailureDiagnoser` parse fallback behavior is covered with hermetic stubs.
- [ ] Full test/build command from intent runs clean with zero regressions.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Flaky timing/concurrency assertions in `ContextTest` | Medium | Medium | Use latches/executor joins and deterministic final-count assertions; avoid sleep-based checks |
| Over-coupling tests to implementation details | Medium | Medium | Assert externally observable behavior and outputs rather than private internals |
| `LlmFailureDiagnoser` parse path hard to isolate due to private method | Medium | Low | Test through stubbed `Client`; if needed add minimal internal seam with unchanged behavior |
| Jitter tests fail nondeterministically if assertions are too strict | Low | Medium | Assert broad documented bounds and sample multiple attempts without exact-value checks |
| Scope creep into integration tests | Low | Medium | Keep all additions under unit-test scope and temp-file fixtures only |

## Security Considerations

- Tests must not issue external API requests or require credentials.
- Temporary filesystem fixtures should be isolated per test and cleaned up.
- No secrets should be written to fixture logs/checkpoints.
- Concurrency tests should avoid unbounded thread creation.

## Dependencies

- Depends on current test stack (`kotest`, existing Gradle test configuration).
- Uses existing project modules only; no new production dependencies.
- Builds on behaviors introduced in Sprints 007 and 008 that now need dedicated unit coverage.

## Open Questions

1. Should `StylesheetApplicationTransform` receive a small dedicated test, or is direct `Stylesheet.applyToGraph` coverage sufficient for this sprint?
2. For diagnoser parsing, should we keep tests black-box via `analyze()` only, or expose a minimal internal parsing helper for tighter unit granularity?
3. Should the `Context.incrementInt()` concurrency test run as a standard unit test at all times, or be marked as a slower test if CI runtime becomes noticeable?
4. Do we want strict minimum assertion counts per target module to prevent future regressions in test depth?
