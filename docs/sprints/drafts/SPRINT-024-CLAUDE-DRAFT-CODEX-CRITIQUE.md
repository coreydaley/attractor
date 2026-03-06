# Critique: SPRINT-024-CLAUDE-DRAFT

## Overall Assessment

Claude’s draft is strongly aligned with the stated sprint intent: it stays documentation-only, enumerates the key drift points introduced by Sprints 021–023, and breaks the work into clear phases that map directly to the known gaps (README `/docs` drift, `dot`→`graphviz`, REST default port, Git History Panel, DOT node types).

The main issues are (a) one notable drift point not captured (Docker docs still claim Java 21 in the base image), and (b) a slight under-emphasis on the “audit” part of the sprint (a broader scan to catch additional inconsistencies beyond the pre-identified ones).

## What Works Well

1. **Scope discipline**: explicitly “docs only / no code changes” and verifies against `RestApiRouter.kt`, `WebMonitorServer.kt`, and `CLAUDE.md`.
2. **Concrete, actionable task list**: tasks are phrased at the level of specific edits, not vague outcomes.
3. **Git History Panel guidance is user-centered**: focuses on what users see and do (collapsed/expanded states, degraded states, refresh behavior).
4. **DOT node types update is appropriately minimal**: adds missing shapes without attempting to redesign the DOT guide.
5. **Port fix approach is practical**: calls out both the header line and the many `curl` examples (and correctly anticipates ~37 `localhost:8080` occurrences).

## Gaps / Concerns

1. **Missing `docs-site/content/docker.md` correction (Java version drift)**: `docs-site/content/docker.md` currently describes the base image as “Java 21 JRE”, but `Dockerfile.base` is now `eclipse-temurin:25-jre-noble`. This is exactly the kind of post-chore drift the sprint is meant to catch, and it should be explicitly in scope.

2. **Swagger UI route ambiguity**: The draft is right to remove legacy `/docs` references, but it doesn’t explicitly call out the still-existing `/api/v1/docs` swagger UI route in `RestApiRouter.kt`. Without an explicit note, a future doc edit could accidentally “correct” the wrong thing (removing mention of `/api/v1/docs` or conflating it with the removed legacy route).

3. **“Audit” is slightly under-specified**: Risks mention grepping for stale strings, but the implementation plan mostly follows the pre-known list. The intent’s success criteria includes “No other known inaccuracies remain”, which implies a deliberate repository-wide scan (at least across `README.md`, `docs-site/content/*.md`, and `docs/api/rest-v1.md`) for ports, tool labels, and Java version references.

4. **Endpoint count should be verified from router, not asserted**: The draft sets “37 endpoints” as the target, but the intent says “37+” and the router may grow. The plan should treat the count as a computed fact (from `RestApiRouter.kt`) and update wording accordingly (e.g., “37 endpoints (as of Sprint 024)” or “37+ endpoints”).

## Recommended Adjustments

1. **Add a docs-site Docker phase item**:
   - Update `docs-site/content/docker.md` to match `Dockerfile.base` (Java 25 JRE) and do a quick spot-check for other stale runtime references.

2. **Add explicit `/api/v1/docs` vs legacy `/docs` language** in the README and/or REST docs:
   - “Legacy runtime `/docs` page removed (Sprint 022). Swagger UI is available at `/api/v1/docs`.”

3. **Add an explicit audit checklist step early**:
   - Run a targeted search across docs for: `/docs`, `8080`, `dot` vs `graphviz`, `JDK 21`/`Java 21`, and “35 endpoints”.
   - Treat any hits as either fixes in-scope (docs) or a tracked follow-up (if it implies code drift).

4. **Make endpoint count wording resilient**:
   - Prefer “37+ endpoints” or “currently 37 endpoints” with the count derived from `RestApiRouter.kt`.

## Verdict

Implementation-ready with minor additions. If Claude’s draft explicitly includes the Docker docs Java version fix, calls out `/api/v1/docs` to avoid route confusion, and strengthens the “audit” step beyond the pre-known diffs, it will fully meet the sprint’s intent and reduce the chance of leaving small but user-visible inaccuracies behind.

