# Sprint 023 Merge Notes

## Claude Draft Strengths
- Clean two-job workflow structure (`build-base` → `build-and-release`)
- Correct GHA cache scoping to prevent base/server cache thrash
- Good risk table covering first-tag edge case and GHCR permission scope
- Interview-confirmed decisions embedded: git diff, `attractor-base` name, semver+latest tags

## Codex Draft Strengths
- Spotted the **double-compilation inefficiency**: current plan has Gradle running once for JARs
  and again inside the Docker builder stage. Codex proposes copying the already-built JAR into
  the image via `.dockerignore` negation — eliminates the builder stage entirely from `Dockerfile`
- More careful **previous-tag detection**: use `git tag --sort=-version:refname` filtered to `v*`
  pattern rather than `git describe --tags HEAD^` which can pick up non-release tags
- Correctly flagged `build-base` job should always _run_ (only condition the build/push _steps_)
  so `build-and-release` always gets a job to depend on

## Valid Critiques Accepted

1. **Harden previous-tag resolution**: replace `git describe --tags --abbrev=0 HEAD^` with
   `git tag --sort=-version:refname | grep '^v' | grep -v "^${CURRENT_TAG}$" | head -1`
   — safer, explicitly filters to `v*` tags, handles annotated and lightweight tags equally

2. **Always-run `build-base` job**: the entire `build-base` job should run unconditionally;
   only the `docker/setup-buildx`, `docker/login`, `docker/metadata`, and `docker/build-push`
   steps are conditioned on `rebuild == 'true'`. This ensures `needs: build-base` in the server
   job is always satisfiable.

3. **Eliminate double Gradle build**: strip the builder stage from `Dockerfile` entirely. The
   workflow already produces the release JAR; use `.dockerignore` negation to allow
   `build/libs/attractor-server-*.jar` into the Docker context and copy it directly. `Dockerfile`
   becomes a two-line runtime image.

4. **Base semver tags are sparse by design**: base only gets semver tags on releases where
   `Dockerfile.base` changes. Server `Dockerfile` references `attractor-base:latest` — no
   semver pin needed. This is correct semantics and aligns with the efficiency goal.

## Critiques Rejected

- **Server image rename to `attractor-server`**: user confirmed keeping existing
  `ghcr.io/coreydaley/attractor` name — no backward-compat break needed.
- **Content-hash tag on base**: `latest` + semver (when rebuilt) is sufficient traceability.
  A content hash adds complexity without a concrete use case.

## Interview Refinements Applied
- Change detection: git diff between tags (confirmed)
- Base image name: `ghcr.io/coreydaley/attractor-base` (confirmed)
- Base tags: semver (when rebuilt) + `latest` (confirmed — sparse semver is fine and correct)
- Server image name: `ghcr.io/coreydaley/attractor` unchanged (existing)

## Final Decisions
- `Dockerfile.base`: JRE + all apt tools only; no app artifact
- `Dockerfile`: single-stage, `FROM attractor-base:latest`, `COPY` pre-built JAR from build context
- `.dockerignore`: negate `build/libs/attractor-server-*.jar` to include JAR in context
- `release.yml`: two jobs; `build-base` always runs (steps conditional); `build-and-release` needs build-base
- Previous-tag detection: `git tag --sort=-version:refname | grep '^v' | grep -v "^$TAG$" | head -1`
- GHA cache scopes: `attractor-base` and `attractor-server` (separate)
