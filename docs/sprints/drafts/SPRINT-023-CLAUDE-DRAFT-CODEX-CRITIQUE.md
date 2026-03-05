# Critique: SPRINT-023-CLAUDE-DRAFT

## Overall Assessment

Claude’s draft matches the sprint intent well: it cleanly separates a slow-changing “OS tools”
layer into `Dockerfile.base`, makes the server image depend on that base, and restructures
`release.yml` into two jobs so the base rebuild can be skipped when unchanged. The plan is close
to implementation-ready.

The main correctness gap is around **tagging semantics** for `attractor-base`: the draft suggests
base images carry the same semver tags as every server release, but the sprint constraint says the
base image must only push when `Dockerfile.base` changes. Those two requirements conflict unless
we redefine “push” to include retagging.

## What Works Well

1. **Intent alignment and scope discipline**: focuses on Dockerfiles + `release.yml` and doesn’t
   propose unrelated refactors.
2. **Clear workflow structure**: two-job split (`build-base` → `build-and-release`) is easy to
   reason about and preserves tag-triggered releases.
3. **Conditional base rebuild**: `git diff --name-only … -- Dockerfile.base` is the right general
   direction and handles “file added in this sprint” cleanly (diff will detect it as changed).
4. **Multi-platform and caching awareness**: calling out `linux/amd64,linux/arm64` and separating
   cache scopes is practical and should reduce build time variability.
5. **Low-risk Dockerfile change**: keeping the builder stage intact and only swapping the runtime
   base reduces the chance of breaking the build.

## Gaps / Concerns

1. **Base semver tags vs. “only push if changed” conflict**: If `build-base` skips on most tags,
   the base image cannot also receive *new* semver tags on those releases without pushing/retagging.
   The “Image traceability” use case and Definition of Done as written imply base gets semver tags
   per release, which violates the efficiency constraint.
2. **Previous-tag detection is under-specified**:
   - `git describe --tags --abbrev=0 HEAD^` can select non-release tags and isn’t constrained to
     `v*`.
   - It can also behave oddly with annotated tags or when tags are not on the first-parent history.
   A more explicit “list all `v*` tags, exclude current, pick previous by version/date” approach
   is safer.
3. **Tag examples are inconsistent with `metadata-action` output**: The draft lists base tags like
   `v0.5.0`, but `docker/metadata-action` semver patterns typically emit `0.5.0` (no `v`) unless a
   prefix is configured. This isn’t a blocker, but it should be made consistent to avoid confusion.
4. **Server image naming not reconciled with intent**: The intent calls for `attractor-server`,
   while the draft keeps `ghcr.io/coreydaley/attractor` (existing). That’s a reasonable choice for
   compatibility, but it should be an explicit decision with a migration plan (or dual-publish).
5. **Double-compilation inefficiency remains**: The workflow builds `releaseJar` for GitHub
   release assets, and the Dockerfile builder stage also runs Gradle to produce the jar for the
   image. That’s not the sprint’s main goal, but it’s worth calling out as an optional
   optimization (e.g., copy the already-built jar into the image, which would require `.dockerignore`
   adjustments).

## Recommended Adjustments

1. **Clarify base tagging semantics** to match the constraint:
   - Option A: base pushes only `:latest` (and optionally `:base-<content-hash>`) *when changed*.
   - Option B: allow “retag-only pushes” and state that in the constraint/DoD (but that dilutes the
     original efficiency goal).
2. **Harden previous-tag resolution**:
   - Filter to `v*` tags explicitly.
   - Prefer version-aware sorting when tags are semver-like, and fall back to “no previous tag →
     rebuild base”.
3. **Make tag outputs consistent** with `docker/metadata-action` behavior (decide whether tags
   include or exclude the `v` prefix, and document it).
4. **Resolve server image naming**:
   - Either adopt `ghcr.io/coreydaley/attractor-server`, or explicitly keep the existing name and
     treat it as a compatibility requirement.
5. **(Optional) Reduce duplicate builds** by packaging the JAR built in the workflow into the
   server image (requires `.dockerignore` negation patterns to include only
   `build/libs/attractor-server-*.jar`).

## Verdict

Strong draft with a good implementation shape. With a small rewrite around base tagging semantics
and a safer “previous tag” selection method, this plan should execute smoothly and deliver the
intended release-time savings.

