# Sprint 024 Merge Notes

## Claude Draft Strengths
- Concrete, file-level task list with specific edits (not vague outcomes)
- Git History Panel section well-scoped from user perspective (states, refresh, PATH requirement)
- Port fix correctly counted ~37 occurrences in rest-v1.md
- Interview correctly surfaced user decision to delete `docs/api/rest-v1.md` instead of just fixing it

## Codex Draft Strengths
- Added an explicit "Phase 1: Inventory + truth-check checklist" as a safety net before editing
- Identified `docs-site/content/docker.md` Java version drift (says "Java 21 JRE", actual is `eclipse-temurin:25-jre-noble`)
- Raised `/api/v1/docs` vs legacy `/docs` ambiguity worth noting in README
- Recommended making endpoint count wording resilient ("currently 37 endpoints" rather than hardcoded)

## Valid Critiques Accepted
1. **Docker Java version fix** — `docs-site/content/docker.md` claims "Java 21 JRE"; `Dockerfile.base` is actually `eclipse-temurin:25-jre-noble`. Add this to Phase 3.
2. **Endpoint count wording** — Use "37 endpoints" (verified from RestApiRouter.kt) without "35+" vs "37+" confusion. Keep it accurate and specific.
3. **Audit checklist** — Add explicit grep targets before editing: `/docs`, `8080`, `dot\b`, `JDK 21`, `Java 21`, `35 endpoint`.

## Critiques Rejected (with reasoning)
1. **Swagger UI route (`/api/v1/docs`) callout** — Codex suggests explicitly documenting `/api/v1/docs` (swagger UI) to distinguish it from removed `/docs`. However, in `RestApiRouter.kt` the `/api/v1/docs` route serves a Swagger UI (not raw docs). There is no user-facing documentation for this route today, and adding it to the README could cause confusion. Deferred to a follow-up when/if the Swagger UI is polished for end-users.
2. **Keeping `docs/api/rest-v1.md`** — Both Codex and Claude drafts proposed modifying it. User decision (interview): delete the file entirely. This overrides both draft proposals.

## Interview Refinements Applied
- User: delete `docs/api/rest-v1.md` rather than fix the port. All README references to this file must also be removed and redirected to the Hugo site.
- User: add all 4 missing node types to the DOT format guide (not just 2).

## Final Decisions
1. **README.md**: Remove stale `/docs` paragraph, `/docs` from API table, and "built-in Docs window" text. Remove all references to `docs/api/rest-v1.md` (file will be deleted). Fix `dot`→`graphviz`, endpoint count, JDK 21→25, project structure /docs mention.
2. **docs-site/content/web-app.md**: Fix `dot`→`graphviz`. Add Git History Panel section.
3. **docs-site/content/dot-format.md**: Add all 4 missing node types.
4. **docs-site/content/docker.md**: Fix "Java 21 JRE" → "Java 25 JRE".
5. **docs/api/rest-v1.md**: Delete the file entirely.
