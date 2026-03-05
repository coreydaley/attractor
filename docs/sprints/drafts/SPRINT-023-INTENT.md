# Sprint 023 Intent: Split Docker Build into Base and Server Images

## Seed

Let's split up this docker build to make it more efficient: one Dockerfile that builds a base
image and pushes it to GHCR ONLY if that Dockerfile has changed, and let's call the image
something like `attractor-base`. Then a second Dockerfile that uses `attractor-base` as its base
image, only builds the server artifact and copies it into the new image called `attractor-server`
and publishes it. All of this should only happen when we push a tag.

## Context

- All 22 sprints completed; codebase is stable
- Current `Dockerfile` is a two-stage file: JDK builder stage + JRE runtime stage (all OS tools)
- `release.yml` is one monolithic job: Gradle build → GitHub release → Docker build+push (multi-platform amd64/arm64)
- The runtime layer installs ~15 system tools (graphviz, git, python3, ruby, node, go, rust, gcc, etc.)
- These tools rarely change; rebuilding them on every release is wasteful
- The server JAR changes on every release

## Recent Sprint Context

- Sprint 020: Workspace git versioning — ProcessBuilder patterns, no new deps
- Sprint 021: Git history UI panel — REST endpoint + JS frontend
- Sprint 022: Hugo docs microsite — GitHub Actions workflow patterns (path-scoped triggers, jobs)

## Relevant Codebase Areas

- `Dockerfile` — current two-stage build (builder + runtime)
- `.github/workflows/release.yml` — tag-triggered, single `build-and-release` job
- `.github/workflows/docs.yml` — path-scoped workflow (reference for conditional job patterns)
- `build.gradle.kts` — `releaseJar` and `releaseCliJar` tasks

## Constraints

- Must follow project conventions in CLAUDE.md
- Everything is tag-triggered (`on: push: tags: 'v*'`) — no separate branch-push triggers
- Base image must only push if `Dockerfile.base` has changed (efficiency goal)
- Both images must be multi-platform: `linux/amd64,linux/arm64`
- Must publish to GHCR under `ghcr.io/coreydaley/`

## Success Criteria

- `Dockerfile.base` exists; builds `ghcr.io/coreydaley/attractor-base` with all OS tools
- `Dockerfile` uses `attractor-base` as its FROM; builds only the server JAR copy
- On tag push: base image rebuilds only if `Dockerfile.base` changed since the previous tag
- On tag push: server image always rebuilds (JAR changes every release)
- Both images published to GHCR as `linux/amd64` + `linux/arm64`
- Release workflow is restructured into two jobs with clear dependency

## Verification Strategy

- Spec/documentation: GitHub Actions `docker/build-push-action` and `docker/metadata-action` docs
- Edge cases: first tag ever (no previous tag to diff against — should rebuild base); `Dockerfile.base` identical to last tag (should skip); `Dockerfile.base` changed (should rebuild)
- Testing approach: review workflow YAML for correctness; verify image pull chain works locally

## Uncertainty Assessment

- Correctness uncertainty: Medium — conditional base rebuild logic (git diff on tags) has edge cases
- Scope uncertainty: Low — well-bounded; two Dockerfiles, restructured workflow
- Architecture uncertainty: Medium — change detection strategy (git diff vs image label inspection) has tradeoffs

## Open Questions

1. Change detection strategy: compare `Dockerfile.base` between current and previous git tag
   (clean, no registry dependency) vs. inspect image labels on existing GHCR image (works even
   if tags are far apart, but requires authenticated registry pull in a detection step)?
2. Image naming: `ghcr.io/coreydaley/attractor-base` (sibling repo) or
   `ghcr.io/coreydaley/attractor/attractor-base` (package namespace)?
3. Workflow structure: add a second job to existing `release.yml`, or create a new
   `release-docker.yml`? Keeping everything in `release.yml` is simpler to reason about.
4. What tag should `attractor-base` carry? Same semver tag as the release? Or `latest` only?
   Same semver makes the base pinnable; `latest` only keeps it simple.
