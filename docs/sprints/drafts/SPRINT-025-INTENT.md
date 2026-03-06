# Sprint 025 Intent: Custom 'attractor' Hugo Theme for Docs-Site

## Seed

Create a custom Hugo theme for the docs-site that matches the look and feel of the main attractor application and switch the theme for the docs-site to this new theme, the theme name should be 'attractor'.

## Context

Sprint 022 created the Hugo docs microsite at `docs-site/` using the third-party `hugo-geekdoc` theme (committed directly to `docs-site/themes/hugo-geekdoc/`). The docs site is live at `https://attractor.coreydaley.dev` and contains five content sections.

The main attractor web application (`WebMonitorServer.kt`) has a polished, consistent dark/light-mode design built on CSS custom properties:
- **Dark mode**: `#0d1117` bg, `#161b22` surface, `#388bfd` blue accent
- **Light mode**: `#f4f4f6` bg, `#ffffff` surface, `#4f46e5` indigo accent
- **Font**: Figtree (Google Fonts), fallback to Segoe UI / system-ui
- **Code blocks**: dark background (`#0d1117`), monospace (`Consolas`, `Cascadia Code`, `Courier New`)
- **Borders**: `#30363d` dark / `#d1d1d8` light, `border-radius: 8px` cards, `6px` elements
- **Favicon**: ⚡ lightning bolt in a rounded rect, accent-colored

The docs-site currently has `theme = "hugo-geekdoc"` in `hugo.toml`. A custom `attractor` theme would replace this dependency with a first-party theme that:
1. Matches the visual identity of the main app exactly
2. Is fully owned and maintainable within this repo
3. Eliminates the large `hugo-geekdoc` vendored dependency (~thousands of files)

## Recent Sprint Context

- **Sprint 021** — Git History Panel: Added `WorkspaceGit.summary()`, REST endpoint `GET /api/v1/projects/{id}/git`, collapsible git bar in the project detail tab.
- **Sprint 022** — Hugo Docs Microsite on GitHub Pages: Created `docs-site/` with five Markdown pages, deployed via GitHub Actions, removed the in-app `/docs` endpoint from `WebMonitorServer.kt`.
- **Sprint 023** — Split Docker Build: Introduced `Dockerfile.base`, simplified `Dockerfile`, restructured `release.yml`.

## Relevant Codebase Areas

- `docs-site/hugo.toml` — Hugo config; `theme` field to update
- `docs-site/themes/hugo-geekdoc/` — existing theme to be replaced (keep or remove)
- `docs-site/themes/attractor/` — new theme to create
- `docs-site/content/` — five content pages with weight-based ordering
- `docs-site/layouts/` — contains a `partials/microformats/` override already (preserve)
- `docs-site/static/` — static assets
- `src/main/kotlin/attractor/web/WebMonitorServer.kt` — source of truth for colors, fonts, and design language

## Constraints

- Must follow project conventions in CLAUDE.md
- Hugo site must still build: `hugo --source docs-site --minify` exits 0
- No new Gradle dependencies
- `docs-site/public/` is gitignored; do NOT commit build output
- The `docs-site/layouts/partials/microformats/` override must be preserved
- The existing content Markdown files must render correctly with the new theme (no front-matter changes required unless unavoidable)
- The theme should not require Hugo Modules / Go (keep it as plain file-based theme)
- geekdoc theme files may be removed (large vendored dependency) after the new theme works

## Success Criteria

- `docs-site/themes/attractor/` exists as a valid Hugo theme
- `hugo.toml` sets `theme = "attractor"`
- `hugo --source docs-site --minify` builds successfully with zero errors
- The rendered site:
  - Uses the same dark color palette as the main app by default
  - Supports dark/light mode toggle (stored in localStorage)
  - Uses Figtree font (Google Fonts)
  - Renders all five content sections with sidebar navigation ordered by weight
  - Has a header with the Attractor branding and favicon consistent with the main app
  - Code blocks use dark background with monospace font
  - Responsive layout (mobile-friendly sidebar)
  - Syntax highlighting uses the `github-dark` style (already configured in `hugo.toml`)

## Verification Strategy

- Spec/documentation: Hugo theme file structure is well-documented; verify against Hugo docs conventions
- Reference: `WebMonitorServer.kt` CSS variables and font choices are the visual reference
- Testing: `hugo --source docs-site --minify` must pass; Hugo will error on missing template references
- Edge cases: home page (`_index.md`), single pages, list pages all need templates
- The `docs-site/layouts/partials/microformats/` override must still be present and not shadowed by the theme

## Uncertainty Assessment

- **Correctness uncertainty**: Low-Medium — Hugo theme structure is well-documented, but the exact template file set required depends on content type usage (need to handle `_default/baseof.html`, `_default/single.html`, `_default/list.html`, and `index.html`)
- **Scope uncertainty**: Low — the seed is specific: create a theme named 'attractor' and switch to it
- **Architecture uncertainty**: Low — Hugo theme structure is standard; the design tokens come directly from the existing app CSS

## Open Questions

1. Should the `hugo-geekdoc` theme files be deleted from the repo after the new theme is functional? The vendored theme is large (~thousands of files). Removing it would reduce repo size and remove a maintenance burden.
2. Should the new theme include a search feature? (geekdoc had `geekdocSearch = false` in config — so search is already disabled; the new theme can omit it entirely for v1)
3. Should the sidebar navigation be auto-generated from `content/` page weights, or should the theme support explicit nav config in `hugo.toml`? Auto-generation from weights is simplest and consistent with existing front matter.
4. Should the theme's dark mode default to `dark` and toggle to `light` (matching the main app's default dark UI), or should it follow the OS preference?
