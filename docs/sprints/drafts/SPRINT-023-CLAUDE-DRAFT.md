# Sprint 023: Split Docker Build into Base and Server Images

## Overview

Every tag release currently rebuilds the entire Docker image from scratch — including ~15 system
tools (Python, Ruby, Node, Go, Rust, GCC, etc.) that rarely change. These tools take 3-5 minutes
to install on each build. The JAR that actually changes is a 25MB copy at the very end.

This sprint splits the Docker build into two layers with separate lifecycles. `Dockerfile.base`
captures everything that changes rarely: the JRE runtime + all OS tool installations. It builds
`ghcr.io/coreydaley/attractor-base` and is only pushed when `Dockerfile.base` itself changes
between tags. `Dockerfile` stays lean: it uses `attractor-base` as its FROM, runs the Gradle
build, and copies the resulting JAR. The server image is rebuilt on every tag as before.

The `release.yml` workflow is restructured into two explicit jobs: `build-base` (conditional)
and `build-and-release` (always, depends on `build-base`). Change detection compares
`Dockerfile.base` between the current tag and the previous git tag using `git diff --name-only`.
Both images are multi-platform (`linux/amd64,linux/arm64`) and published to GHCR.

## Use Cases

1. **Fast release cycle**: A developer tags `v0.5.0`. `Dockerfile.base` is identical to `v0.4.1`.
   The base job detects no change, skips the 5-minute OS tool install, and only the server image
   is built. Total Docker time drops from ~8 minutes to ~3 minutes.

2. **OS tool update**: A developer adds a new tool to `Dockerfile.base` and tags `v0.6.0`. The
   base job detects the change, rebuilds and pushes `attractor-base`, then the server job builds
   on top of the fresh base.

3. **First-ever tag**: There is no previous tag to diff against. The base job falls back to
   rebuilding (safe default: always rebuild when uncertain).

4. **Multi-platform**: Both `attractor-base` and the server image are built for `linux/amd64`
   and `linux/arm64`, supporting Intel/AMD servers and Apple Silicon Macs.

5. **Image traceability**: `attractor-base` carries the same semver tag as the release
   (`v0.5.0`, `0.5`, `0`) so any image version is fully traceable to a specific base.

## Architecture

```
Tag push (v*)
  │
  ├─► Job: build-base
  │     │
  │     ├─ git diff Dockerfile.base vs prev tag
  │     │    ├─ changed  → build+push attractor-base (amd64+arm64)
  │     │    └─ unchanged → skip (use existing GHCR image)
  │     │
  │     └─ outputs: base_rebuilt=true|false
  │
  └─► Job: build-and-release (needs: build-base)
        │
        ├─ Gradle build → releaseJar + releaseCliJar
        ├─ Attest JARs
        ├─ Create GitHub Release (upload JARs)
        └─ Build + push attractor (server image, always)
             FROM ghcr.io/coreydaley/attractor-base:latest
             COPY server JAR

Image registry layout:
  ghcr.io/coreydaley/attractor-base      ← OS + tools layer
    tags: latest, v0.5.0, 0.5, 0
  ghcr.io/coreydaley/attractor           ← server image (existing)
    tags: latest, v0.5.0, 0.5, 0

Dockerfile.base (new file):
  FROM eclipse-temurin:21-jre
  RUN apt-get install [all tools]

Dockerfile (modified):
  # Build stage unchanged
  FROM eclipse-temurin:21-jdk AS builder
  ...
  # Runtime stage: use base image
  FROM ghcr.io/coreydaley/attractor-base:latest
  COPY --from=builder .../attractor-server-*.jar /app/attractor-server.jar
  [ENV vars, VOLUME, EXPOSE, ENTRYPOINT]
```

## Implementation Plan

### Phase 1: Create `Dockerfile.base` (~20%)

**Files:**
- `Dockerfile.base` — Create
- `Dockerfile` — Modify (change runtime `FROM`)

**Tasks:**
- [ ] Create `Dockerfile.base`:
  ```dockerfile
  FROM eclipse-temurin:21-jre
  RUN apt-get update && apt-get install -y --no-install-recommends \
          graphviz git python3 ruby nodejs npm golang-go \
          rustc cargo gcc g++ clang make gradle maven curl \
      && rm -rf /var/lib/apt/lists/*
  ```
- [ ] Modify `Dockerfile` runtime stage:
  - Remove the `RUN apt-get install ...` block (moved to `Dockerfile.base`)
  - Change `FROM eclipse-temurin:21-jre` → `FROM ghcr.io/coreydaley/attractor-base:latest`
  - Keep all ENV vars, VOLUME, WORKDIR, EXPOSE, ENTRYPOINT, CMD unchanged
  - Builder stage (JDK) remains identical

---

### Phase 2: Restructure `release.yml` — `build-base` job (~50%)

**Files:**
- `.github/workflows/release.yml` — Modify

**Tasks:**

#### Job: `build-base` (new, runs first)

- [ ] Add job `build-base` with `runs-on: ubuntu-latest`
- [ ] Permissions: `contents: read`, `packages: write`
- [ ] Steps:
  1. `actions/checkout@v4` with `fetch-depth: 0` (need full history for `git describe`)
  2. Detect previous tag:
     ```bash
     PREV_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")
     ```
  3. Detect change in `Dockerfile.base`:
     ```bash
     if [ -z "$PREV_TAG" ]; then
       echo "No previous tag found — rebuilding base image"
       echo "rebuild=true" >> $GITHUB_OUTPUT
     elif git diff --name-only "$PREV_TAG" HEAD -- Dockerfile.base | grep -q 'Dockerfile.base'; then
       echo "Dockerfile.base changed since $PREV_TAG — rebuilding base image"
       echo "rebuild=true" >> $GITHUB_OUTPUT
     else
       echo "Dockerfile.base unchanged since $PREV_TAG — skipping base image rebuild"
       echo "rebuild=false" >> $GITHUB_OUTPUT
     fi
     ```
  4. `docker/setup-buildx-action@v3` — conditional on `rebuild == 'true'`
  5. `docker/login-action@v3` (GHCR) — conditional on `rebuild == 'true'`
  6. `docker/metadata-action@v5` — conditional on `rebuild == 'true'`
     - `images: ghcr.io/${{ github.repository_owner }}/attractor-base`
     - Same semver tag patterns: `type=semver,pattern={{version}}`, `{{major}}.{{minor}}`, `{{major}}`
     - Plus `type=raw,value=latest`
  7. `docker/build-push-action@v6` — conditional on `rebuild == 'true'`
     - `file: Dockerfile.base`
     - `push: true`
     - `platforms: linux/amd64,linux/arm64`
     - `cache-from: type=gha,scope=base`
     - `cache-to: type=gha,mode=max,scope=base`
- [ ] Output `rebuild` value from this job for visibility in logs

#### Job: `build-and-release` (existing, restructured)

- [ ] Add `needs: build-base`
- [ ] Keep all existing steps (Gradle, attest, GitHub Release) unchanged
- [ ] Update Docker build step:
  - `file: Dockerfile` (explicit)
  - Add `cache-from: type=gha,scope=server`
  - Add `cache-to: type=gha,mode=max,scope=server`
  - Remove old single-scope cache (now scoped separately from base)
- [ ] Update `docker/metadata-action` image to remain `ghcr.io/${{ github.repository }}` (server image)
- [ ] Add `type=raw,value=latest` tag to server metadata as well

---

### Phase 3: GHA cache scoping (~10%)

**Files:**
- `.github/workflows/release.yml` — Modify (cache scope keys)

**Tasks:**
- [ ] Give the base image build its own GHA cache scope: `scope=attractor-base`
- [ ] Give the server image build its own GHA cache scope: `scope=attractor-server`
- [ ] This prevents the two multi-platform builds from evicting each other's cache layers

---

### Phase 4: Documentation update (~20%)

**Files:**
- `docs/sprints/SPRINT-023.md` — Create (this document)
- `README.md` — Review (Docker pull instructions may need updating)

**Tasks:**
- [ ] Verify README Docker section references `ghcr.io/coreydaley/attractor` (server image) —
  this is what end-users pull; no change needed for users
- [ ] Add comment in `Dockerfile` explaining the base image dependency

---

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `Dockerfile.base` | Create | OS + tool layer; builds `attractor-base` |
| `Dockerfile` | Modify | Change runtime FROM to `attractor-base:latest`; remove apt-get install block |
| `.github/workflows/release.yml` | Modify | Add `build-base` job with change detection; scope GHA caches |

## Definition of Done

### Dockerfiles
- [ ] `Dockerfile.base` exists with all tool installations from the settings page
- [ ] `Dockerfile` runtime stage uses `FROM ghcr.io/coreydaley/attractor-base:latest`
- [ ] `Dockerfile` builder stage (JDK) is unchanged
- [ ] Running `docker build -f Dockerfile.base .` locally produces a valid image
- [ ] Running `docker build -f Dockerfile .` locally (with `attractor-base` pulled) produces a valid server image

### Workflow
- [ ] `release.yml` has two jobs: `build-base` and `build-and-release`
- [ ] `build-and-release` declares `needs: build-base`
- [ ] `build-base` detects previous git tag correctly
- [ ] `build-base` skips push when `Dockerfile.base` is unchanged
- [ ] `build-base` pushes when `Dockerfile.base` changed or no previous tag exists
- [ ] `attractor-base` is tagged with semver + `latest`
- [ ] `attractor` (server) is tagged with semver + `latest`
- [ ] Both images are built for `linux/amd64,linux/arm64`
- [ ] GHA caches are scoped separately for base and server builds

### Quality
- [ ] No existing workflow steps removed or broken
- [ ] JAR build, attest, and GitHub Release steps unchanged
- [ ] YAML is valid (no syntax errors)
- [ ] No new secrets required (uses `GITHUB_TOKEN` as before)

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| `git describe --tags HEAD^` fails on first-ever tag | Medium | Low | `|| echo ""` fallback; empty PREV_TAG triggers rebuild (safe default) |
| `attractor-base:latest` not available when server build starts | Low | High | `build-and-release` job depends on `build-base`; even if base skipped, existing `latest` on GHCR is used |
| GHA cache eviction across scopes | Low | Low | Scoped caches don't interfere; worst case: cold build |
| `Dockerfile.base` semver tags diverge from server tags over time | Low | Low | Acceptable: base image version reflects when it last changed, not every release |
| `gradle` apt package too old for some projects | Low | Medium | Known limitation; document that projects needing specific Gradle version should use Gradle wrapper |
| `GHCR_TOKEN` permissions issue for base image push (different image name) | Low | Medium | `packages: write` permission on `github.repository_owner` scope covers all packages; test on first run |

## Security Considerations

- All images published to GHCR using `GITHUB_TOKEN` — no external secrets
- `Dockerfile.base` contains only apt-managed packages — no manual script downloads
- `fetch-depth: 0` checkout required for git tag history — acceptable for public repo
- Server `Dockerfile` pins to `attractor-base:latest` — ensure `latest` is always a known-good build
  - Mitigation: semver tags on `attractor-base` allow pinning to specific base if needed

## Dependencies

- Sprint 022 completed (no in-progress structural work)
- Current `Dockerfile` already installs all tools (just moving them to `Dockerfile.base`)
- GitHub Actions `docker/build-push-action`, `docker/metadata-action`, `docker/login-action` (already in use)
- No new Gradle dependencies

## Open Questions

1. **Tag for `attractor-base`**: Using same semver tags (`v0.5.0`, `0.5`, `0`, `latest`) means the
   base image version tracks when it was last built, not the server release version. This is correct
   semantics but could be confusing. Alternative: tag base with a content hash. Proposed: same
   semver + latest is clearest for humans.

2. **`latest` tag on base**: Using `latest` on base means `Dockerfile` can say
   `FROM attractor-base:latest` and always get the most recent stable base. This requires the base
   job to push `latest` even on skipped builds (i.e., `latest` must already point to the right image
   from a previous tag). Since we skip the push when unchanged, `latest` remains pointing to the
   last time it was actually rebuilt — which is correct.

3. **Local development**: Developers building `Dockerfile` locally need `attractor-base` available.
   Should there be a `make build-base` target for local use? Proposed: add `build-base` Makefile
   target as a convenience, not blocking for this sprint.
