# Sprint 023: Split Docker Build into Base and Server Images

## Overview

Tagged releases currently rebuild a heavyweight Docker runtime layer on every push: the existing
`Dockerfile` installs a large suite of OS tools (graphviz, git, python, ruby, node, go, rust,
compilers, build tools, etc.) even though those dependencies change infrequently. The server JAR
*does* change every release, so the right optimization is to separate “slow-changing OS/tooling”
from “fast-changing application artifact”.

This sprint introduces a dedicated base image (`attractor-base`) built from a new
`Dockerfile.base`, and a slimmer server image (`attractor-server`) that layers only the release
server JAR and Attractor runtime configuration on top of that base. The release workflow remains
tag-triggered, but is restructured so the base image is rebuilt/pushed **only** when
`Dockerfile.base` changed since the previous tag, while the server image is always rebuilt/pushed.

Note: Historical planning docs reference `CLAUDE.md`, but there is no `CLAUDE.md` in this repo.
This sprint follows existing repo patterns (Gradle tasks `releaseJar`/`releaseCliJar`, existing
GitHub Actions conventions in `.github/workflows/*.yml`, and sprint conventions in
`docs/sprints/README.md`).

## Use Cases

1. **Fast release cycle**: Tag `vX.Y.Z` pushes a new server image quickly because the runtime base
   layer is reused when `Dockerfile.base` is unchanged.
2. **Tooling update**: Adding/removing an OS tool (e.g., `graphviz` or `git`) triggers a base image
   rebuild on the next tag, then server image builds against the updated base.
3. **Multi-platform publishing**: Both images publish to GHCR for `linux/amd64` and `linux/arm64`.
4. **Traceable release pipeline**: The workflow clearly distinguishes “base/tooling” from
   “application artifact”, with explicit dependency ordering so the server build never races the
   base build.
5. **Local reproducibility**: A developer can build the base image directly, then build the server
   image against it using the same Dockerfiles used by CI.

## Architecture

### Images

- **Base image**: `ghcr.io/coreydaley/attractor-base`
  - Built from `Dockerfile.base`
  - Contents: JRE + apt-managed OS tools currently installed in the runtime stage of `Dockerfile`
  - Published only when `Dockerfile.base` changed since the previous tag
- **Server image**: `ghcr.io/coreydaley/attractor-server` (see Open Questions for naming)
  - Built from `Dockerfile`
  - Base: `FROM ghcr.io/coreydaley/attractor-base:<tag>`
  - Contents: release server JAR + runtime env defaults + exposed port + entrypoint
  - Published on every tag

### Dockerfile layout

**`Dockerfile.base` (new):**

- `FROM eclipse-temurin:21-jre`
- `apt-get install` list migrated from the current `Dockerfile` runtime stage
- No application artifact copy and no Attractor-specific env defaults beyond what’s needed for the
  OS/tool layer

**`Dockerfile` (modified):**

- Runtime stage changes to `FROM ghcr.io/coreydaley/attractor-base:<tag-or-latest>`
- Copies the server artifact into `/app/attractor-server.jar`
- Retains current runtime configuration:
  - `VOLUME /app/data`, `ENV ATTRACTOR_DB_NAME=...`
  - provider API key env vars (empty by default)
  - `EXPOSE 7070`, `ENTRYPOINT [...]`, `CMD [...]`

**Artifact packaging strategy (recommended):**

Use the server JAR already produced by the workflow (`./gradlew releaseJar`) as the Docker build
input, rather than rebuilding the JAR inside Docker. This requires adjusting `.dockerignore` to
allow `build/libs/attractor-server-*.jar` into the build context while still excluding the rest of
`build/`.

### Release workflow (two jobs)

Restructure `.github/workflows/release.yml` into two jobs:

```text
on tag push (v*)
  job: build-base
    - checkout (fetch tags)
    - detect previous tag + diff Dockerfile.base
    - if changed (or no previous tag): buildx build/push attractor-base (amd64+arm64)

  job: build-and-release
    needs: build-base
    - build release JARs (releaseJar, releaseCliJar)
    - attest JARs
    - create GitHub release with JAR assets
    - buildx build/push attractor-server (amd64+arm64), FROM attractor-base
```

### Base rebuild detection strategy

Default strategy: compare `Dockerfile.base` between the current tag and the previous tag:

- Determine `PREV_TAG` (the most recent prior `v*` tag, excluding the current tag).
- If `PREV_TAG` is empty, rebuild base (first tag or cannot resolve prior tag).
- Else, rebuild base only if `git diff --name-only "$PREV_TAG" "$GITHUB_SHA" -- Dockerfile.base`
  indicates a change.

This keeps detection self-contained (no registry inspection), but must be implemented carefully to
avoid false negatives due to tag sorting or missing tags in checkout.

## Implementation Plan

### Phase 1: Create `Dockerfile.base` (~25%)

**Files:**
- `Dockerfile.base` — Create

**Tasks:**
- [ ] Create `Dockerfile.base` starting from `eclipse-temurin:21-jre`.
- [ ] Move the runtime-stage `apt-get install` tool list out of `Dockerfile` into `Dockerfile.base`.
- [ ] Keep installation strictly apt-managed (no curl-pipe installers) for supply-chain simplicity.
- [ ] Ensure `Dockerfile.base` builds locally: `docker build -f Dockerfile.base .`

### Phase 2: Make server `Dockerfile` depend on base (~25%)

**Files:**
- `Dockerfile` — Modify
- `.dockerignore` — Modify

**Tasks:**
- [ ] Update runtime stage: `FROM ghcr.io/coreydaley/attractor-base:<tag-or-latest>`.
- [ ] Remove the apt-get tool installation block from `Dockerfile`.
- [ ] Ensure `Dockerfile` only copies in the server artifact and sets runtime config.
- [ ] Update `.dockerignore` to include only `build/libs/attractor-server-*.jar` (keep `build/`
      excluded otherwise) so CI can package the already-built release JAR into the server image.

### Phase 3: Restructure `release.yml` into base + server jobs (~40%)

**Files:**
- `.github/workflows/release.yml` — Modify

**Tasks:**
- [ ] Split into two jobs: `build-base` and `build-and-release` with `needs` ordering.
- [ ] Add a “detect base change since previous tag” step in `build-base`.
- [ ] Conditionalize the base image build/push step(s) on the detection result.
- [ ] Build and push the server image on every tag.
- [ ] Ensure both images are built with:
  - platforms: `linux/amd64,linux/arm64`
  - `docker/setup-buildx-action`
  - `docker/login-action` to `ghcr.io`
  - `docker/metadata-action` for tagging/labels
  - GHA cache, ideally with separate scopes for base vs server to avoid cache thrash

### Phase 4: Verification and edge-case hardening (~10%)

**Files:**
- (none required; may add notes to `docs/` if helpful)

**Tasks:**
- [ ] Validate base detection edge cases:
  - first tag after introducing `Dockerfile.base`
  - no previous `v*` tag available (should rebuild base)
  - `Dockerfile.base` unchanged since previous tag (should skip base push)
  - `Dockerfile.base` changed (should build/push base, then server)
- [ ] Confirm server image build works when base is skipped (uses existing base tag as intended).
- [ ] Local sanity check:
  - build base image
  - build release JAR
  - build server image using base

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `Dockerfile.base` | Create | Base runtime image with OS tools (slow-changing layer) |
| `Dockerfile` | Modify | Use `attractor-base` as runtime base; copy server artifact only |
| `.dockerignore` | Modify | Allow release server JAR into docker context while keeping build output mostly ignored |
| `.github/workflows/release.yml` | Modify | Split into base + server jobs; conditional base push; always server push |
| `docs/sprints/drafts/SPRINT-023-CODEX-DRAFT.md` | Create | Sprint plan draft (this document) |

## Definition of Done

- [ ] `Dockerfile.base` exists and builds `ghcr.io/coreydaley/attractor-base` with all OS tools
      currently installed in the runtime stage of `Dockerfile`.
- [ ] `Dockerfile` uses `attractor-base` as its runtime `FROM` and no longer installs OS tools.
- [ ] On tag push (`v*`), base image build/push occurs only when `Dockerfile.base` changed since the
      previous tag (or when there is no previous tag to compare).
- [ ] On tag push (`v*`), server image always builds and pushes.
- [ ] Both images are published multi-platform (`linux/amd64` + `linux/arm64`).
- [ ] `release.yml` is restructured into two jobs with clear dependency ordering.

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Previous-tag detection is incorrect (wrong sort / missing tags) | Medium | High | Use `fetch-depth: 0`, explicitly list/sort `v*` tags, add a safe fallback (“no previous tag → rebuild base”) |
| Base job is skipped but server needs it | Low | High | Keep base job always running; condition only the build/push step; server job depends on base job completion |
| `.dockerignore` changes bloat build context | Low | Medium | Include only `build/libs/attractor-server-*.jar` via negation patterns; keep `build/` excluded otherwise |
| Naming change breaks existing consumers expecting `ghcr.io/coreydaley/attractor` | Medium | Medium | Consider tagging/publishing server image under both names during transition, or keep current name (see Open Questions) |
| Base image tag strategy makes pinning hard | Medium | Medium | Publish `latest` plus a stable “content hash” tag when base changes (or document semver expectations clearly) |

## Security Considerations

- Keep base tooling install limited to distro packages (`apt-get`) to reduce supply-chain risk.
- GitHub Actions permissions should remain minimal:
  - `packages: write` only where needed
  - `contents: write` only for GitHub release creation
- Do not bake API keys into images; keep the current “empty env defaults” model.

## Dependencies

- GitHub Container Registry (`ghcr.io`) publishing under `ghcr.io/coreydaley/`
- Existing Gradle release tasks: `releaseJar`, `releaseCliJar`
- Existing tag naming convention: `v*`

## Open Questions

1. **Image naming for server**: keep current server image name (`ghcr.io/coreydaley/attractor`) for
   backwards compatibility vs. publish as `ghcr.io/coreydaley/attractor-server` (intent). If we
   rename, should we dual-tag/push both names for a deprecation window?
2. **Base tag strategy**: base pushes only when `Dockerfile.base` changes. Recommended:
   - always push `:latest` when base changes
   - also push a deterministic tag derived from `Dockerfile.base` content (e.g., `:base-<sha>`)
   Avoid expecting a base semver tag on every release, because that conflicts with “only push when
   base changes”.
3. **Previous tag resolution**: use version-aware sorting (`--sort=-version:refname`) vs. date
   sorting. Version sorting is preferred if tags are semver-like.
4. **Workflow split location**: keep everything in `.github/workflows/release.yml` (recommended)
   vs. create a separate docker-only workflow. Keeping it together makes the tag-triggered release
   story easier to audit.

