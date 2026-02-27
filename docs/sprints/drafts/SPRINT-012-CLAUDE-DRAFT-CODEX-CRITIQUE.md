# Critique: SPRINT-012-CLAUDE-DRAFT

## Overall Assessment

The draft is strong on structure and is close to executable, but it currently misses several requirements from the Sprint 012 intent and has a few concrete mismatches with the repo/build reality. The biggest gaps are endpoint coverage completeness, artifact naming/release expectations, and documentation scope.

## High-Priority Findings

1. **Endpoint coverage is incomplete vs intent requirement "all 35 endpoints accessible"**
   - Reference: `docs/sprints/drafts/SPRINT-012-CLAUDE-DRAFT.md:55`, `:230-283`, `:399-403`
   - The command matrix omits:
     - `PATCH /api/v1/pipelines/{id}` (no `pipeline update` command)
     - `GET /api/v1/dot/generate/stream`
     - `GET /api/v1/dot/fix/stream`
     - `GET /api/v1/dot/iterate/stream`
   - This conflicts with the intent success criterion that CLI access all 35 REST v1 endpoints.
   - Fix: add explicit commands/tasks/tests for those four endpoints.

2. **CLI artifact naming strategy does not match the intent deliverable**
   - Reference: `docs/sprints/drafts/SPRINT-012-CLAUDE-DRAFT.md:7`, `:86-94`, `:375-377`
   - The draft narrative targets `attractor-cli-<version>.jar`, but implementation + DoD produce classifier output (`coreys-attractor-<version>-cli.jar`).
   - This is a contract mismatch with Sprint 012 intent criterion #1.
   - Fix: set explicit archive base name/version pattern for CLI jar (`attractor-cli-${version}.jar`) instead of relying on classifier naming.

3. **Documentation deliverable is under-scoped**
   - Reference: `docs/sprints/drafts/SPRINT-012-CLAUDE-DRAFT.md:185-425`
   - Intent requires useful help through command and documentation, but the plan does not include README/docs updates as first-class file changes.
   - Fix: add explicit docs tasks (README CLI section, command examples, build/release usage notes).

## Medium-Priority Findings

1. **Java runtime statement conflicts with repository requirements**
   - Reference: `docs/sprints/drafts/SPRINT-012-CLAUDE-DRAFT.md:7`
   - Draft says "Java 17+", while repo tooling/workflows are standardized on Java 21 (`README.md`, `Makefile`, CI/release workflows).
   - Fix: standardize sprint language/tests/build steps on Java 21.

2. **Release workflow plan needs deterministic multi-asset upload details**
   - Reference: `docs/sprints/drafts/SPRINT-012-CLAUDE-DRAFT.md:179`, `:421`
   - Current release workflow uploads one jar via a single discovered path. Draft says "attach CLI jar" but does not define robust two-asset selection (server + CLI) to avoid accidental single-asset releases.
   - Fix: add explicit asset discovery/output variables for both jars and pass both paths to release action.

3. **Error/exit-code policy is inconsistent between prose and test expectations**
   - Reference: `docs/sprints/drafts/SPRINT-012-CLAUDE-DRAFT.md:101`, `:324`, `:416`
   - The draft states generic "exit code 1 on any error" but mixes command/usage failures and unknown command behavior into the same code path. This can make scripting brittle.
   - Fix: define a stable exit-code contract (e.g., usage vs runtime/API) and test for it consistently.

## Suggested Edits Before Implementation

1. Add missing command coverage for `pipeline update` and all three DOT streaming endpoints, plus tests.
2. Lock CLI jar naming to `attractor-cli-<version>.jar` in Gradle task configuration and DoD wording.
3. Add explicit documentation file updates (`README.md` minimum) to satisfy the "help + docs" requirement.
4. Normalize Java requirement language to Java 21 across plan and DoD.
5. Specify exact release workflow changes that always upload both server and CLI jars.

## Bottom Line

The draft is directionally good and implementation-ready after one revision pass. The revision should focus on endpoint completeness, artifact naming contract, and explicit documentation/release details to align fully with Sprint 012 intent.
