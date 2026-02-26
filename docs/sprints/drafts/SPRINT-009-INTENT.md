# Sprint 009 Intent: Critical Path Test Coverage

## Seed

ensure adequate test coverage of critical code paths

## Context

The project has 5 test files with ~976 lines of tests across 61 test cases. Recent sprints have
added significant logic — Sprint 007 introduced failure diagnosis/self-healing, Sprint 008 added
performance optimizations. Several core modules with complex, independently testable logic have
zero dedicated test coverage, creating blind spots in the safety net.

**Tested today (direct unit tests):**
- `ParserTest.kt` — DOT grammar, 15 tests, 298 lines
- `ConditionEvaluatorTest.kt` — edge condition expressions, 13 tests, 132 lines
- `EngineTest.kt` — end-to-end engine execution, 13 tests, 334 lines
- `ValidatorTest.kt` — lint rules, 14 tests, 212 lines
- `PipelineStateTest.kt` — web-layer state, 6 tests, ~90 lines (Sprint 008)

**Not covered with dedicated unit tests:**
- `EdgeSelector` — 5-step routing algorithm, `normalizeLabel()`, weight+lexical tiebreak
- `RetryPolicy` / `BackoffConfig` — exponential backoff, jitter bounds, `fromNode()` factory
- `Checkpoint` — `save()`/`load()` roundtrip, `create()` from Context, corrupt file handling
- `Context` — thread-safe CRUD, `getInt()` type coercion, `incrementInt()`, `clone()`
- `Stylesheet` / `StylesheetParser` — selector specificity, property inheritance, parse errors
- `VariableExpansionTransform` — `$goal` substitution in prompt/label
- `LlmFailureDiagnoser.parseResponse()` — JSON extraction from LLM preamble, parse fallback

## Recent Sprint Context

- **Sprint 006**: Pipeline history navigation, version navigator strip, `GET /api/pipeline-view`
  endpoint, `isHydratedViewOnly` flag on `PipelineEntry`
- **Sprint 007**: Intelligent failure diagnosis layer (`FailureDiagnoser` interface,
  `NullFailureDiagnoser`, `LlmFailureDiagnoser`), repair attempts, failure_report.json,
  three new engine events: `DiagnosticsStarted`, `DiagnosticsCompleted`, `RepairAttempted`,
  `RepairSucceeded`, `RepairFailed`
- **Sprint 008**: Performance optimization — SSE broadcast storm elimination, `hasLog` caching,
  `ConcurrentLinkedDeque` log buffer, SQLite PRAGMAs, compact checkpoints, regex pre-compilation,
  fixed thread pool; added `PipelineStateTest.kt`

## Relevant Codebase Areas

| Module | File | Why It Matters |
|--------|------|----------------|
| `EdgeSelector` | `engine/EdgeSelector.kt` | 5-step routing algo; controls pipeline flow |
| `RetryPolicy` | `engine/RetryPolicy.kt` | Core reliability; backoff math |
| `Checkpoint` | `state/Checkpoint.kt` | Resume correctness; roundtrip fidelity |
| `Context` | `state/Context.kt` | Thread-safe shared state; type coercion |
| `Stylesheet` | `style/Stylesheet.kt` | CSS-like model stylesheet; specificity rules |
| `VariableExpansionTransform` | `transform/VariableExpansionTransform.kt` | Goal injection |
| `LlmFailureDiagnoser` | `engine/FailureDiagnoser.kt` | LLM response parsing; fallback behavior |

## Constraints

- Must follow project conventions: Kotest `FunSpec` style, `io.kotest` matchers
- No new production dependencies — tests only
- No LLM API calls in tests (use stubs/test data for `LlmFailureDiagnoser` parsing tests)
- Build command: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ~/.gradle/wrapper/dists/gradle-8.7-bin/*/gradle-8.7/bin/gradle -p . jar`
- All existing tests must continue to pass
- Tests must be hermetic (no internet, no shared mutable state, no port binding)

## Success Criteria

1. Every module listed in "Relevant Codebase Areas" has at least one dedicated test file
2. All identified critical paths have explicit test coverage
3. Edge cases (corrupt file, empty input, concurrent access, type coercion) are enumerated
4. Build passes with zero failures
5. No regressions in existing tests

## Verification Strategy

- **Reference implementation**: The spec (`../attractor/attractor-spec.md`) defines expected
  behavior for EdgeSelector (Section 3.3), RetryPolicy (Section 3.5), and Context
- **Correctness verification**: Each test directly asserts observable outputs (return values,
  file contents, side effects)
- **Edge cases to test**:
  - `EdgeSelector.select()` returns `null` when no edges exist
  - `BackoffConfig.delayForAttempt()` stays within `[0.5×, 1.5×]` jitter band
  - `Checkpoint.load()` returns `null` for missing file, `null` for corrupt JSON
  - `Context.getInt()` coerces String "42" → 42, handles non-numeric gracefully
  - `StylesheetParser` id selector overrides class selector overrides universal
  - `VariableExpansionTransform` is a no-op when goal is blank
- **Testing approach**: Unit tests only (no integration); temporary files via
  `Files.createTempDirectory()` for filesystem tests

## Uncertainty Assessment

- **Correctness uncertainty**: Low — these are pure functions with deterministic behavior;
  no LLM calls involved in the modules under test
- **Scope uncertainty**: Low — the seed is specific; modules are enumerated
- **Architecture uncertainty**: Low — all tests follow existing Kotest FunSpec patterns
  already established in the project

## Open Questions

1. Should `VariableExpansionTransform` tests also cover `StylesheetApplicationTransform`
   (which is a thin wrapper around `Stylesheet.applyToGraph`), or is it covered by the
   `Stylesheet` tests?
2. For `LlmFailureDiagnoser.parseResponse()`, should we test it via a package-private helper
   or via integration with a mock `Client`?
3. Should `RetryPolicy` tests actually test sleep timing, or just assert delay calculations
   without sleeping?
4. Is `Context.incrementInt()` concurrency safety worth a multi-threaded stress test?
