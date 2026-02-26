# Sprint 009: Critical Path Test Coverage

## Overview

Eight sprints of feature work have accumulated a growing body of complex, independently-testable
logic with zero dedicated test coverage. The 5 existing test files focus on the DOT parser,
condition evaluator, engine integration, lint rules, and web-layer state — all written either
at system-inception or as sprint deliverables. The core state, engine routing, and transform
modules have been exercised only as side-effects of higher-level tests, not in isolation.

This sprint systematically fills those gaps by writing focused unit tests for seven modules:
`EdgeSelector`, `RetryPolicy`/`BackoffConfig`, `Checkpoint`, `Context`, `Stylesheet`/`StylesheetParser`,
`VariableExpansionTransform`, and `LlmFailureDiagnoser.parseResponse()`. All modules are pure
(no LLM calls, no process spawning, no network I/O) with deterministic, directly assertable
behavior. No production code changes are required — this sprint is tests-only.

The coverage increase is expected to catch regressions from future refactors and document
behavioral invariants that are currently implicit (e.g., that backoff is capped at maxDelayMs,
that `Context.getInt()` coerces String "42" to 42, that `Stylesheet` id selectors beat class
selectors).

## Use Cases

1. **Routing logic is protected**: A future change to `EdgeSelector.select()` that accidentally
   drops Step 3 (suggested next IDs) is caught immediately by the test suite, before it reaches
   a running pipeline.

2. **Backoff math is documented**: A developer reads the `RetryPolicy` tests and knows the
   delay bounds for each preset without reading the source formula.

3. **Checkpoint roundtrip verified**: A change to serialization format (e.g., adding a new
   field to `CheckpointData`) is caught if it breaks `save()`/`load()` compatibility.

4. **Context coercion contract is clear**: The `Context.getInt("key")` tests document that
   numeric strings are coerced, that missing keys return the default, and that non-numeric
   strings also return the default.

5. **Stylesheet specificity rules enforced**: A universal rule `{ llm_model: gpt-4o }` does not
   override an id rule `#mynode { llm_model: claude-opus }` — this is verified explicitly.

6. **Variable expansion no-ops cleanly**: When a pipeline graph has no `goal` attribute,
   `VariableExpansionTransform` returns the graph unchanged.

7. **Diagnoser parse fallback is robust**: When the LLM prepends its response with
   `"Here is the diagnosis:\n{...}"`, `parseResponse()` still extracts the JSON correctly.

## Architecture

```
Test structure (new files)
═══════════════════════════════════════════════════════════════════════════════
src/test/kotlin/attractor/
├── engine/
│   ├── EngineTest.kt                  (existing — 13 tests)
│   ├── EdgeSelectorTest.kt            (NEW — 8 tests)
│   ├── RetryPolicyTest.kt             (NEW — 7 tests)
│   └── FailureDiagnoserTest.kt        (NEW — 5 tests)
├── state/
│   ├── CheckpointTest.kt              (NEW — 7 tests)
│   └── ContextTest.kt                 (NEW — 8 tests)
├── style/
│   └── StylesheetTest.kt              (NEW — 7 tests)
└── transform/
    └── VariableExpansionTransformTest.kt (NEW — 5 tests)

Total new tests: ~47 across 6 new files
```

All new files use Kotest `FunSpec` and `io.kotest.matchers` — the same style as existing tests.
No new Gradle dependencies required.

## Implementation Plan

### Phase 1: EdgeSelectorTest (~20%)

**Files:**
- `src/test/kotlin/attractor/engine/EdgeSelectorTest.kt` (create)

**Why critical**: `EdgeSelector.select()` is the core routing decision — wrong behavior means
pipelines take the wrong path silently. The 5-step algorithm is documented in Section 3.3.

**Tests:**
- [ ] `select() returns null when node has no outgoing edges`
- [ ] `Step 1 — condition match wins over all other rules`
- [ ] `Step 2 — preferred label match used when no condition matches`
- [ ] `Step 3 — suggested next ID respected when label doesn't match`
- [ ] `Step 4 — unconditional edge selected by weight+lexical when no hint`
- [ ] `Step 5 — highest weight wins among unconditional edges`
- [ ] `Lexical tiebreak — alphabetically first to-ID wins on equal weight`
- [ ] `normalizeLabel strips [K], K), K- prefixes and lowercases`

**Setup**: Tests build `DotGraph` and `DotNode` objects directly (no parsing needed). Use a
simple helper `fun makeEdge(to: String, condition: String = "", label: String = "", weight: Int = 1)`.

---

### Phase 2: RetryPolicyTest (~15%)

**Files:**
- `src/test/kotlin/attractor/engine/RetryPolicyTest.kt` (create)

**Why critical**: Retry budget and backoff timing directly affect how long a failing stage
blocks the pipeline and whether recovery is attempted.

**Tests:**
- [ ] `BackoffConfig.delayForAttempt(1) returns initialDelayMs ± 50% jitter`
- [ ] `BackoffConfig.delayForAttempt(n) never exceeds maxDelayMs`
- [ ] `BackoffConfig with jitter=false returns exact delay`
- [ ] `RetryPolicy.NONE has maxAttempts=1`
- [ ] `RetryPolicy.fromNode uses node max_retries when set`
- [ ] `RetryPolicy.fromNode falls back to graph default when node has no max_retries`
- [ ] `RetryPolicy.fromNode with 0 retries gives maxAttempts=1`

**Notes**: Do NOT actually sleep in tests. Assert that `delayForAttempt()` returns a value in
the expected range. For jitter tests, assert `delay >= initialDelayMs * 0.5` and
`delay <= initialDelayMs * 1.5`.

---

### Phase 3: CheckpointTest (~20%)

**Files:**
- `src/test/kotlin/attractor/state/CheckpointTest.kt` (create)

**Why critical**: Resume correctness depends entirely on checkpoint roundtrip fidelity. A
checkpoint that drops fields or misreads the file silently skips stages.

**Tests:**
- [ ] `save() + load() roundtrip preserves all fields`
- [ ] `load() returns null when checkpoint.json does not exist`
- [ ] `load() returns null when checkpoint.json contains invalid JSON`
- [ ] `create() extracts retry counts from Context internal keys`
- [ ] `create() captures context values in checkpoint`
- [ ] `save() creates the logsRoot directory if it does not exist`
- [ ] `saved checkpoint file is compact JSON (no newlines in the value content)`

**Setup**: Use `Files.createTempDirectory()` for filesystem isolation; delete in `afterTest`.

---

### Phase 4: ContextTest (~20%)

**Files:**
- `src/test/kotlin/attractor/state/ContextTest.kt` (create)

**Why critical**: `Context` is shared mutable state threaded through the entire engine. Bugs in
`getInt()`, `clone()`, or `incrementInt()` produce wrong pipeline decisions or data loss.

**Tests:**
- [ ] `set() + get() stores and retrieves value`
- [ ] `get() returns default when key absent`
- [ ] `getInt() coerces String "42" to 42`
- [ ] `getInt() returns default for non-numeric String "hello"`
- [ ] `getInt() returns value for Number types`
- [ ] `incrementInt() increments from 0 and returns new value`
- [ ] `clone() produces independent copy (mutations do not propagate)`
- [ ] `snapshot() returns a copy (mutations to snapshot do not affect Context)`

---

### Phase 5: StylesheetTest (~15%)

**Files:**
- `src/test/kotlin/attractor/style/StylesheetTest.kt` (create)

**Why critical**: Stylesheet controls which LLM model is used per node. Wrong specificity
resolution means model assignments silently override intended ones.

**Tests:**
- [ ] `Universal selector applies to all nodes`
- [ ] `Class selector matches nodes with matching cssClass`
- [ ] `Id selector matches only the named node`
- [ ] `Id selector overrides class selector overrides universal (specificity)`
- [ ] `Existing node attribute is not overwritten by stylesheet`
- [ ] `StylesheetParser handles quoted and unquoted property values`
- [ ] `StylesheetParser returns empty list for blank input`

**Setup**: Build `DotNode` objects directly; use `Stylesheet.applyToNode()` and
`Stylesheet.applyToGraph()`.

---

### Phase 6: VariableExpansionTransformTest (~10%)

**Files:**
- `src/test/kotlin/attractor/transform/VariableExpansionTransformTest.kt` (create)

**Tests:**
- [ ] `$goal in prompt is replaced with graph goal`
- [ ] `$goal in label is replaced with graph goal`
- [ ] `Graph with empty goal is returned unchanged`
- [ ] `Node with no $goal in prompt is unchanged`
- [ ] `Multiple $goal occurrences in a prompt are all replaced`

**Setup**: Parse a DOT string or construct `DotGraph` directly. Assert that node `attrs["prompt"]`
and `attrs["label"]` have the expected values after transform.

---

### Phase 7: FailureDiagnoserTest (~10%)

**Files:**
- `src/test/kotlin/attractor/engine/FailureDiagnoserTest.kt` (create)

**Why critical**: `LlmFailureDiagnoser.parseResponse()` is `private` — but its behavior is
observable via `NullFailureDiagnoser` (which is testable directly) and the JSON parsing logic
can be exercised via a package-internal test or by testing the public `analyze()` with a mock
`Client`.

**Approach**: Test `parseResponse()` indirectly via a real `LlmFailureDiagnoser` instance with
a mock `Client` that returns canned text. This keeps tests in the same package and exercises the
full parsing path.

**Tests:**
- [ ] `NullFailureDiagnoser returns ABORT with correct explanation`
- [ ] `NullFailureDiagnoser custom reason is preserved`
- [ ] `parseResponse: valid JSON returns correct DiagnosisResult`
- [ ] `parseResponse: JSON embedded in LLM preamble text is extracted`
- [ ] `parseResponse: completely invalid text returns ABORT fallback`

**Mock Client setup**: Create a minimal `Client` stub that returns a canned `GenerateResponse`
for the parse tests. Look at how `EngineTest` uses `SimulationBackend` for pattern reference.

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `src/test/kotlin/attractor/engine/EdgeSelectorTest.kt` | Create | 5-step routing algorithm |
| `src/test/kotlin/attractor/engine/RetryPolicyTest.kt` | Create | Backoff math, preset values, `fromNode()` |
| `src/test/kotlin/attractor/engine/FailureDiagnoserTest.kt` | Create | JSON parsing, fallback behavior |
| `src/test/kotlin/attractor/state/CheckpointTest.kt` | Create | Save/load roundtrip, corrupt file handling |
| `src/test/kotlin/attractor/state/ContextTest.kt` | Create | Type coercion, clone independence |
| `src/test/kotlin/attractor/style/StylesheetTest.kt` | Create | Specificity, selector types |
| `src/test/kotlin/attractor/transform/VariableExpansionTransformTest.kt` | Create | `$goal` substitution |

## Definition of Done

- [ ] `EdgeSelectorTest.kt` exists with 8 tests; all pass
- [ ] `RetryPolicyTest.kt` exists with 7 tests; all pass
- [ ] `FailureDiagnoserTest.kt` exists with 5 tests; all pass
- [ ] `CheckpointTest.kt` exists with 7 tests; all pass
- [ ] `ContextTest.kt` exists with 8 tests; all pass
- [ ] `StylesheetTest.kt` exists with 7 tests; all pass
- [ ] `VariableExpansionTransformTest.kt` exists with 5 tests; all pass
- [ ] All 47 new tests pass with `gradle test`
- [ ] All existing tests (61) continue to pass — no regressions
- [ ] No new production code changes
- [ ] No new Gradle dependencies
- [ ] Build passes: `export JAVA_HOME=... && gradle -p . jar`

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `LlmFailureDiagnoser.parseResponse()` is `private` — cannot call directly | High | Medium | Test via mock `Client` in `analyze()`, or add `internal` visibility for test access |
| `BackoffConfig` jitter range test is flaky (random) | Low | Low | Test that delay is in `[0.5×, 1.5×]` range; run assertion inside a loop if needed |
| `DotGraph`/`DotNode` construction for EdgeSelector tests is verbose | Medium | Low | Extract a tiny `makeGraph()` helper within the test file |
| Checkpoint tests leave temp files if test fails before cleanup | Low | Low | Use `afterTest { tmpDir.deleteRecursively() }` or `withCleanup` pattern |
| `StylesheetApplicationTransform` not covered (wraps `Stylesheet.applyToGraph()`) | Low | Low | `Stylesheet` tests cover the underlying logic; transform wrapper is trivial |

## Security Considerations

- No new endpoints, network access, or process spawning introduced
- Temp directory tests use `Files.createTempDirectory()` — scoped, not world-writable

## Dependencies

- Sprint 008 (completed) — `PipelineStateTest.kt` establishes Kotest `FunSpec` pattern for
  `web` package; reuse for new test files
- No external dependencies

## Open Questions

1. Should `FailureDiagnoserTest` create a `Client` mock using an anonymous object that
   implements `Client`, or is there an existing test double pattern in the codebase?
2. Should `VariableExpansionTransformTest` also cover `StylesheetApplicationTransform`?
   (It is a one-liner wrapper around `Stylesheet.applyToGraph()`.)
3. Is `Context.incrementInt()` worth a concurrent stress test (10 threads × 1000 increments)?
