# Sprint 025: Custom Hugo Theme (`attractor`) for Docs Site

## Overview

Sprint 022 introduced the Hugo docs microsite at `docs-site/` using the third-party `hugo-geekdoc` theme, vendored directly into this repo under `docs-site/themes/hugo-geekdoc/`. While functional, it creates two problems: the docs no longer match Attractor’s visual identity, and the repo carries a large, third-party theme dependency with thousands of files we don’t own.

This sprint creates a first-party Hugo theme named `attractor` that matches the main Attractor web app’s design language (dark/light tokens, typography, spacing, border radii, code styling, and overall layout). Once the theme is functional, the docs site switches to `theme = "attractor"` and we remove (or at minimum stop depending on) the vendored Geekdoc theme.

The outcome is a docs site that looks and feels like the product, is easy to maintain in-repo, and builds with `hugo --source docs-site --minify` without any Hugo Modules or additional tooling.

## Use Cases

1. **Brand consistency**: A user clicks “Docs” from the Attractor UI and lands on a docs site that looks like the app (same palette, same font, same tone).
2. **Maintainable ownership**: We can tweak layout, navigation, and styling without upstream theme constraints or large vendor updates.
3. **Repo hygiene**: Removing `docs-site/themes/hugo-geekdoc/` dramatically reduces repo churn and size.
4. **Docs reading ergonomics**: Dark mode by default with a crisp light-mode toggle; readable tables and code blocks; mobile-friendly navigation.
5. **Low-friction publishing**: Existing GitHub Pages (or custom domain) deployment continues unchanged; no content rewrites required.

## Architecture

### Theme file layout (Hugo conventions)

The theme will be a plain file-based theme (no Hugo Modules):

```text
docs-site/themes/attractor/
  theme.toml
  layouts/
    _default/
      baseof.html
      single.html
      list.html
    index.html
    partials/
      head.html
      header.html
      sidebar.html
      footer.html
  static/
    css/attractor.css
    js/theme.js
    favicon.svg
```

Key points:
- `baseof.html` provides the global shell (header + sidebar + main content).
- `single.html` renders the five leaf content pages (`web-app.md`, `rest-api.md`, etc.).
- `list.html` / `index.html` renders the home page (`content/_index.md`) and any future list pages.
- The theme will call `partial "microformats/schema.html" .` so the existing override at `docs-site/layouts/partials/microformats/schema.html` continues to apply (and the theme will not ship its own `microformats/` partials).

### Navigation generation

Sidebar navigation is auto-generated from existing content weights:
- List `.Site.RegularPages.ByWeight` and render a stable set of links.
- Highlight the active page.
- Ensure the ordering matches the current weights (Web App 10, REST API 20, CLI 30, DOT Format 40, Docker 50).

### Styling + theming

CSS uses the same core design tokens as `WebMonitorServer.kt`:
- `data-theme="dark"` default with a toggle to `data-theme="light"`.
- Theme preference stored in `localStorage` (key: `attractor-theme`) and applied early in `<head>` to avoid flash.
- Font: Figtree via Google Fonts, fallback to system UI fonts.
- Code blocks: dark background (`#0d1117`) and monospace stack.
- Radii: 8px for cards, 6px for smaller elements; borders match app tokens.

## Implementation Plan

### Phase 1: Theme scaffold + config switch (~20%)

**Files:**
- `docs-site/themes/attractor/theme.toml` — Create
- `docs-site/themes/attractor/layouts/_default/baseof.html` — Create
- `docs-site/themes/attractor/layouts/_default/single.html` — Create
- `docs-site/themes/attractor/layouts/_default/list.html` — Create
- `docs-site/themes/attractor/layouts/index.html` — Create
- `docs-site/hugo.toml` — Modify

**Tasks:**
- [ ] Create a minimal valid Hugo theme at `docs-site/themes/attractor/`.
- [ ] Update `docs-site/hugo.toml` to `theme = "attractor"`.
- [ ] Ensure `hugo --source docs-site --minify` exits 0 with the new theme (even if styling is minimal initially).

---

### Phase 2: Layout shell + navigation (~35%)

**Files:**
- `docs-site/themes/attractor/layouts/partials/head.html` — Create
- `docs-site/themes/attractor/layouts/partials/header.html` — Create
- `docs-site/themes/attractor/layouts/partials/sidebar.html` — Create
- `docs-site/themes/attractor/layouts/partials/footer.html` — Create
- `docs-site/themes/attractor/layouts/_default/baseof.html` — Modify

**Tasks:**
- [ ] Header: Attractor branding + theme toggle control.
- [ ] Sidebar: auto-generate nav from page weights; active-link highlighting; collapsible on mobile.
- [ ] Main content: render `.Title` and `.Content` consistently for both home and leaf pages.
- [ ] Include the `microformats/schema.html` partial from the head so the existing site override continues to suppress JSON-LD.

---

### Phase 3: Match Attractor visual identity (~35%)

**Files:**
- `docs-site/themes/attractor/static/css/attractor.css` — Create
- `docs-site/themes/attractor/static/js/theme.js` — Create
- `docs-site/themes/attractor/layouts/partials/head.html` — Modify

**Tasks:**
- [ ] Implement CSS variables matching the app’s dark + light palettes.
- [ ] Typography: Figtree + system fallbacks; consistent headings, tables, and links.
- [ ] Components: “card” surfaces for sidebar and content; table styling for Markdown tables; blockquotes/callouts; code block styling.
- [ ] Theme toggle:
  - [ ] Apply stored theme (`localStorage['attractor-theme']`) on first paint.
  - [ ] Toggle updates `data-theme` + `color-scheme` and persists.
- [ ] Add favicon (`favicon.svg`) aligned with the app’s lightning bolt identity.

---

### Phase 4: Remove Geekdoc dependency + verification (~10%)

**Files:**
- `docs-site/themes/hugo-geekdoc/` — Delete (if approved)
- `docs-site/hugo.toml` — Modify (cleanup Geekdoc-only params)

**Tasks:**
- [ ] Remove (or at least stop relying on) Geekdoc-specific `params.*` in `hugo.toml` once the new theme is in place.
- [ ] Decide whether to delete `docs-site/themes/hugo-geekdoc/` from the repo now that the new theme works.
- [ ] Verify all five pages render correctly and navigation order is correct.
- [ ] Run `hugo --source docs-site --minify` and confirm 0 errors.

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `docs-site/hugo.toml` | Modify | Switch theme to `attractor` (and cleanup Geekdoc params) |
| `docs-site/themes/attractor/theme.toml` | Create | Declare theme metadata |
| `docs-site/themes/attractor/layouts/_default/baseof.html` | Create | Global page shell (header/sidebar/content) |
| `docs-site/themes/attractor/layouts/_default/single.html` | Create | Render leaf content pages |
| `docs-site/themes/attractor/layouts/_default/list.html` | Create | Render list pages / home |
| `docs-site/themes/attractor/layouts/index.html` | Create | Home page layout (fallback to list if desired) |
| `docs-site/themes/attractor/layouts/partials/*.html` | Create | Head/header/sidebar/footer partials |
| `docs-site/themes/attractor/static/css/attractor.css` | Create | Design tokens + component styling |
| `docs-site/themes/attractor/static/js/theme.js` | Create | Dark/light mode toggle with persistence |
| `docs-site/themes/attractor/static/favicon.svg` | Create | Favicon aligned with app branding |
| `docs-site/themes/hugo-geekdoc/` | Delete (optional) | Remove vendored third-party theme after migration |

## Definition of Done

- [ ] `docs-site/themes/attractor/` exists as a valid Hugo theme
- [ ] `docs-site/hugo.toml` sets `theme = "attractor"`
- [ ] `hugo --source docs-site --minify` builds successfully (exit 0)
- [ ] Rendered site matches the main app’s visual identity (tokens, font, spacing, radii)
- [ ] Dark/light mode toggle exists and persists via localStorage
- [ ] Sidebar navigation lists all five sections ordered by `weight`
- [ ] Header includes Attractor branding and favicon matches app identity
- [ ] Code blocks use dark background and monospace font
- [ ] Responsive layout (mobile-friendly sidebar)
- [ ] `docs-site/layouts/partials/microformats/` override remains present and effective
- [ ] `docs-site/public/` remains untracked (no build output committed)

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missing required templates causes Hugo build failures | Medium | High | Start with minimal `baseof/single/list/index` set; iterate until `hugo --minify` passes |
| Nav generation doesn’t match content structure | Low | Medium | Use `.Site.RegularPages.ByWeight` and validate active state on each page |
| Theme toggle flashes wrong theme on load | Medium | Medium | Apply theme attribute via an inline `<script>` in `<head>` before CSS loads |
| Markdown tables/code blocks look inconsistent | Medium | Medium | Add explicit table + pre/code styling in `attractor.css` |
| Removing Geekdoc breaks something subtle | Low | Medium | Keep deletion as an explicit phase gated by successful build + manual review |

## Security Considerations

- The theme toggle uses only `localStorage` for a `"dark"|"light"` string; no sensitive data stored.
- Avoid injecting untrusted HTML/JS; rely on Hugo’s normal Markdown rendering and keep any `safeHTML` usage out of templates.
- If loading Google Fonts, ensure only the needed font is requested and nothing else is pulled from third-party CDNs.

## Dependencies

- None (this sprint is self-contained and does not require new Gradle dependencies).

## Open Questions

1. Should we delete `docs-site/themes/hugo-geekdoc/` immediately once the new theme builds, or keep it temporarily for rollback?
2. Should we add search in v1 of the theme? (Current config has Geekdoc search disabled; simplest is to omit search entirely.)
3. Should the sidebar be fully auto-generated from page weights, or should we add an explicit nav config to `hugo.toml` for future flexibility?
4. Should dark mode default to `dark` (matching the main app’s look) or follow OS preference (`prefers-color-scheme`) on first visit?

