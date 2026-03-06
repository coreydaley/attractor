# Sprint 025: Custom 'attractor' Hugo Theme for Docs-Site

## Overview

The attractor docs-site was created in Sprint 022 using the third-party `hugo-geekdoc` theme, vendored directly into `docs-site/themes/hugo-geekdoc/`. While functional, the geekdoc theme has its own visual identity — it doesn't match the polished dark/light-mode design of the main attractor web application. The docs site feels like a different product from the app itself.

This sprint creates a custom Hugo theme named `attractor` that faithfully reproduces the visual design language of the main attractor web application. The theme uses the same CSS custom properties (colors, fonts, border radii, spacing), the same Figtree typeface, and the same dark-first color palette. Dark and light modes are supported with a toggle stored in `localStorage`. The theme is a fully self-contained Hugo theme with no external dependencies beyond the Google Fonts CDN (already used by the main app).

After the theme is complete and `hugo.toml` is updated to `theme = "attractor"`, the large vendored `hugo-geekdoc` directory is removed, reducing repository size and eliminating a third-party maintenance dependency. The only Hugo-specific geekdoc customization that existed (`docs-site/layouts/partials/microformats/`) is a project-level override that lives outside the theme directory and remains untouched.

## Use Cases

1. **Visual consistency**: A user arrives at the docs site from the app. The header, colors, and font feel immediately familiar — they're on the same product's docs, not a generic theme.

2. **Dark mode by default**: The docs site opens in dark mode (matching the app's default). A sun/moon toggle in the header switches to light mode, persisted across page loads via `localStorage`.

3. **Sidebar navigation**: All five content sections (Web App, REST API, CLI, DOT Format, Docker) appear in a left sidebar, ordered by their `weight` front-matter value. The active page is highlighted.

4. **Code readability**: Code blocks render with the app's dark background (`#0d1117`), monospace font stack, and syntax highlighting via Hugo's built-in Chroma with the `github-dark` style.

5. **Mobile responsive**: On narrow screens the sidebar collapses; a hamburger menu reveals navigation.

6. **Smaller repo**: After the geekdoc vendor directory is removed, `git clone` is faster and the repo history is cleaner. The theme is now a first-party asset: ~15 files instead of ~hundreds.

## Architecture

```
docs-site/
├── hugo.toml                              ← theme = "attractor" (change from hugo-geekdoc)
├── content/                               ← unchanged Markdown pages
│   ├── _index.md
│   ├── web-app.md        (weight: 10)
│   ├── rest-api.md       (weight: 20)
│   ├── cli.md            (weight: 30)
│   ├── dot-format.md     (weight: 40)
│   └── docker.md         (weight: 50)
├── layouts/
│   └── partials/
│       └── microformats/                  ← preserved project-level override
└── themes/
    ├── attractor/                         ← NEW: custom theme
    │   ├── theme.toml                     ← theme metadata
    │   ├── layouts/
    │   │   ├── _default/
    │   │   │   ├── baseof.html            ← base template (HTML shell)
    │   │   │   ├── single.html            ← single content page
    │   │   │   └── list.html              ← list/section pages
    │   │   ├── index.html                 ← home page (_index.md)
    │   │   └── partials/
    │   │       ├── head.html              ← <head>: meta, fonts, CSS
    │   │       ├── header.html            ← top nav bar with branding + theme toggle
    │   │       ├── nav.html               ← sidebar navigation
    │   │       └── footer.html            ← footer
    │   └── static/
    │       ├── css/
    │       │   └── attractor.css          ← full theme stylesheet
    │       └── js/
    │           └── attractor.js           ← dark/light toggle + sidebar
    └── hugo-geekdoc/                      ← REMOVED after new theme is verified

Theme rendering flow:
  baseof.html
    ├── partials/head.html       (fonts, CSS, meta)
    ├── partials/header.html     (branding, theme toggle)
    ├── partials/nav.html        (sidebar: all pages sorted by weight)
    ├── block "main"             ← filled by single.html / list.html / index.html
    └── partials/footer.html     (minimal)

CSS variable map (mirrors WebMonitorServer.kt exactly):
  [data-theme="dark"] {
    --bg: #0d1117;
    --surface: #161b22;
    --surface-raised: #1c2128;
    --surface-muted: #21262d;
    --border: #30363d;
    --text: #c9d1d9;
    --text-strong: #f0f6fc;
    --text-muted: #8b949e;
    --text-faint: #6e7681;
    --accent: #388bfd;
    --code-bg: #0d1117;
    --code-text: #c9d1d9;
  }
  [data-theme="light"] {
    --bg: #f4f4f6;
    --surface: #ffffff;
    --surface-raised: #ededf0;
    --surface-muted: #e4e4e8;
    --border: #d1d1d8;
    --text: #27272a;
    --text-strong: #18181b;
    --text-muted: #3f3f46;
    --text-faint: #52525b;
    --accent: #4f46e5;
    --code-bg: #f0f0f3;
    --code-text: #27272a;
  }
```

## Implementation Plan

### Phase 1: Theme skeleton and CSS (~30%)

**Files:**
- `docs-site/themes/attractor/theme.toml` — Create
- `docs-site/themes/attractor/static/css/attractor.css` — Create
- `docs-site/themes/attractor/static/js/attractor.js` — Create

**Tasks:**
- [ ] Create `docs-site/themes/attractor/` directory tree (`layouts/_default/`, `layouts/partials/`, `static/css/`, `static/js/`)
- [ ] Create `docs-site/themes/attractor/theme.toml`:
  ```toml
  name = "Attractor"
  license = "MIT"
  licenselink = "https://github.com/coreydaley/attractor/blob/main/LICENSE"
  description = "Custom Hugo theme matching the Attractor web application design"
  homepage = "https://github.com/coreydaley/attractor"
  min_version = "0.110.0"

  [author]
    name = "Corey Daley"
  ```
- [ ] Create `docs-site/themes/attractor/static/css/attractor.css` with:
  - CSS reset: `* { box-sizing: border-box; margin: 0; padding: 0; }`
  - `[data-theme="dark"]` root variables (exact match to WebMonitorServer.kt dark theme)
  - `[data-theme="light"]` root variables (exact match to WebMonitorServer.kt light theme)
  - Body: `font-family: 'Figtree', 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text);`
  - Layout: CSS grid `header | (nav + main) | footer` — sticky header, sidebar left, content right
  - Header: `background: var(--surface); border-bottom: 1px solid var(--border); padding: 12px 20px;`
  - Header branding: SVG lightning bolt favicon matching the main app's `⚡` in a rounded rect + "Attractor Docs" text
  - Theme toggle button: `background: none; border: 1px solid var(--border); border-radius: 6px; padding: 4px 8px; cursor: pointer; color: var(--text-muted);` — shows sun/moon icon
  - Sidebar nav: `background: var(--surface); border-right: 1px solid var(--border); width: 240px; padding: 20px 0;`
  - Nav links: `padding: 8px 16px; color: var(--text-muted); text-decoration: none; display: block; border-left: 3px solid transparent; font-size: 0.88rem;`
  - Active nav link: `border-left-color: var(--accent); color: var(--text-strong); background: var(--surface-raised);`
  - Content area: `flex: 1; padding: 32px 40px; max-width: 900px; line-height: 1.7;`
  - Headings: `color: var(--text-strong);` — h1 `1.8rem`, h2 `1.3rem`, h3 `1.1rem`, with margin-bottom spacing
  - Paragraphs: `margin-bottom: 1rem; color: var(--text);`
  - Code inline: `background: var(--surface-muted); color: var(--accent); padding: 2px 5px; border-radius: 4px; font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.88em;`
  - Code blocks: `background: var(--code-bg); color: var(--code-text); padding: 16px; border-radius: 8px; overflow-x: auto; font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.82rem; line-height: 1.6; margin: 1rem 0; border: 1px solid var(--border);`
  - Tables: `width: 100%; border-collapse: collapse; margin: 1rem 0;` — `th` with `background: var(--surface-muted); color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em; font-size: 0.74rem; padding: 8px 12px;` — `td` with `padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 0.85rem;`
  - Blockquotes: `border-left: 3px solid var(--accent); padding: 8px 16px; margin: 1rem 0; background: var(--surface-muted); border-radius: 0 6px 6px 0; color: var(--text-muted); font-size: 0.88rem;`
  - `<a>` links: `color: var(--accent); text-decoration: none;` — hover: `text-decoration: underline;`
  - `<hr>`: `border: none; border-top: 1px solid var(--border); margin: 2rem 0;`
  - Footer: `background: var(--surface); border-top: 1px solid var(--border); padding: 16px 20px; font-size: 0.78rem; color: var(--text-faint); text-align: center;`
  - Responsive: `@media (max-width: 768px)` — sidebar hidden by default, hamburger button shown, `.nav-open` class shows sidebar as overlay
  - Hamburger button: shown only on mobile

- [ ] Create `docs-site/themes/attractor/static/js/attractor.js`:
  ```javascript
  // Theme toggle (dark/light)
  (function() {
    var stored = localStorage.getItem('attractor-theme');
    var theme = stored || 'dark';
    document.documentElement.setAttribute('data-theme', theme);
  })();

  function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme');
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    localStorage.setItem('attractor-theme', next);
    updateThemeToggle(next);
  }

  function updateThemeToggle(theme) {
    var btn = document.getElementById('themeToggle');
    if (btn) btn.textContent = theme === 'dark' ? '\u2600' : '\u263d'; // sun / moon
  }

  document.addEventListener('DOMContentLoaded', function() {
    updateThemeToggle(document.documentElement.getAttribute('data-theme') || 'dark');

    // Mobile nav toggle
    var toggle = document.getElementById('navToggle');
    var nav = document.getElementById('siteNav');
    if (toggle && nav) {
      toggle.addEventListener('click', function() {
        nav.classList.toggle('nav-open');
      });
    }
  });
  ```

---

### Phase 2: Hugo layout templates (~40%)

**Files:**
- `docs-site/themes/attractor/layouts/_default/baseof.html` — Create
- `docs-site/themes/attractor/layouts/_default/single.html` — Create
- `docs-site/themes/attractor/layouts/_default/list.html` — Create
- `docs-site/themes/attractor/layouts/index.html` — Create
- `docs-site/themes/attractor/layouts/partials/head.html` — Create
- `docs-site/themes/attractor/layouts/partials/header.html` — Create
- `docs-site/themes/attractor/layouts/partials/nav.html` — Create
- `docs-site/themes/attractor/layouts/partials/footer.html` — Create

**Tasks:**
- [ ] Create `layouts/partials/head.html`:
  ```html
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{ if .IsHome }}{{ .Site.Title }}{{ else }}{{ .Title }} — {{ .Site.Title }}{{ end }}</title>
  <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%234f46e5'/%3E%3Ctext x='50%25' y='50%25' font-size='20' text-anchor='middle' dominant-baseline='central'%3E%E2%9A%A1%3C/text%3E%3C/svg%3E">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Figtree:wght@300;400;500;600;700&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="{{ "css/attractor.css" | relURL }}">
  <script>
    // Apply theme before page renders to avoid flash
    (function() {
      var t = localStorage.getItem('attractor-theme') || 'dark';
      document.documentElement.setAttribute('data-theme', t);
    })();
  </script>
  {{ template "_internal/opengraph.html" . }}
  ```

- [ ] Create `layouts/partials/header.html`:
  ```html
  <header>
    <button id="navToggle" class="nav-toggle" aria-label="Toggle navigation">&#9776;</button>
    <a href="{{ "/" | relURL }}" class="site-brand">
      <svg width="24" height="24" viewBox="0 0 32 32" fill="none" xmlns="http://www.w3.org/2000/svg">
        <rect width="32" height="32" rx="8" fill="#4f46e5"/>
        <text x="50%" y="50%" font-size="20" text-anchor="middle" dominant-baseline="central">&#9889;</text>
      </svg>
      <span class="site-title">{{ .Site.Title }}</span>
    </a>
    <button id="themeToggle" class="theme-toggle" onclick="toggleTheme()" title="Toggle dark/light mode" aria-label="Toggle theme">&#9728;</button>
  </header>
  ```

- [ ] Create `layouts/partials/nav.html`:
  ```html
  <nav id="siteNav" class="site-nav">
    <ul>
      {{ range .Site.RegularPages.ByParam "weight" }}
      <li>
        <a href="{{ .RelPermalink }}" class="{{ if eq .RelPermalink $.RelPermalink }}nav-active{{ end }}">
          {{ .Title }}
        </a>
      </li>
      {{ end }}
    </ul>
  </nav>
  ```

- [ ] Create `layouts/partials/footer.html`:
  ```html
  <footer>
    <a href="https://github.com/coreydaley/attractor" target="_blank" rel="noopener">Attractor</a>
    &mdash; Documentation
  </footer>
  ```

- [ ] Create `layouts/_default/baseof.html`:
  ```html
  <!DOCTYPE html>
  <html lang="{{ .Site.Language.Lang }}" data-theme="dark">
  <head>
    {{- partial "head.html" . -}}
  </head>
  <body>
    {{- partial "header.html" . -}}
    <div class="layout">
      {{- partial "nav.html" . -}}
      <main class="content">
        {{- block "main" . }}{{- end }}
      </main>
    </div>
    {{- partial "footer.html" . -}}
    <script src="{{ "js/attractor.js" | relURL }}"></script>
  </body>
  </html>
  ```

- [ ] Create `layouts/_default/single.html`:
  ```html
  {{ define "main" }}
  <article class="page">
    <h1>{{ .Title }}</h1>
    {{ .Content }}
  </article>
  {{ end }}
  ```

- [ ] Create `layouts/_default/list.html`:
  ```html
  {{ define "main" }}
  <article class="page">
    <h1>{{ .Title }}</h1>
    {{ .Content }}
    {{ range .Pages.ByParam "weight" }}
    <section class="list-item">
      <h2><a href="{{ .RelPermalink }}">{{ .Title }}</a></h2>
      {{ .Summary }}
    </section>
    {{ end }}
  </article>
  {{ end }}
  ```

- [ ] Create `layouts/index.html`:
  ```html
  {{ define "main" }}
  <article class="page home">
    <h1>{{ .Site.Title }}</h1>
    {{ .Content }}
    <nav class="home-nav">
      {{ range .Site.RegularPages.ByParam "weight" }}
      <a href="{{ .RelPermalink }}" class="home-nav-card">
        <span class="home-nav-title">{{ .Title }}</span>
      </a>
      {{ end }}
    </nav>
  </article>
  {{ end }}
  ```

---

### Phase 3: Update hugo.toml and remove geekdoc (~10%)

**Files:**
- `docs-site/hugo.toml` — Modify
- `docs-site/themes/hugo-geekdoc/` — Remove

**Tasks:**
- [ ] Update `docs-site/hugo.toml`:
  - Change `theme = "hugo-geekdoc"` to `theme = "attractor"`
  - Remove the `[params]` block with `geekdocNav`, `geekdocSearch`, `geekdocEditPath` (geekdoc-specific params)
  - Keep `baseURL`, `title`, `pluralizeListTitles`, `[markup.highlight]`, `[minify]` unchanged
  - Optionally add a `[params]` block for the edit link if the theme supports it (add as a `editPath` param and expose in the theme's single.html partial)

- [ ] Run `hugo --source docs-site` to verify build passes with new theme before removing geekdoc
- [ ] Remove `docs-site/themes/hugo-geekdoc/` directory entirely (large vendored dependency)
- [ ] Run `hugo --source docs-site --minify` to verify final build passes

---

### Phase 4: CSS refinements for content fidelity (~15%)

**Files:**
- `docs-site/themes/attractor/static/css/attractor.css` — Modify

**Tasks:**
- [ ] Verify all five content pages render correctly:
  - `web-app.md` — verify tables, code blocks, tip blockquotes, lists
  - `rest-api.md` — verify bold HTTP method badges, code blocks, endpoint headings
  - `cli.md` — verify code blocks, definition-style lists
  - `dot-format.md` — verify code blocks (DOT language), tables, annotated examples
  - `docker.md` — verify code blocks, environment variable tables
- [ ] Add list styles: `ul`, `ol` in `.content` — indented, with `margin-bottom: 0.5rem`
- [ ] Add `strong`/`b`: `color: var(--text-strong); font-weight: 600;`
- [ ] Add `em`/`i`: `font-style: italic;`
- [ ] Home nav cards (`.home-nav-card`):
  ```css
  .home-nav { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin-top: 24px; }
  .home-nav-card { display: block; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px 18px; text-decoration: none; color: var(--text); transition: border-color 0.15s; }
  .home-nav-card:hover { border-color: var(--accent); color: var(--text-strong); }
  .home-nav-title { font-weight: 600; font-size: 0.9rem; }
  ```
- [ ] Mobile adjustments:
  ```css
  @media (max-width: 768px) {
    .layout { flex-direction: column; }
    .site-nav { position: fixed; top: 0; left: -260px; height: 100vh; width: 240px; z-index: 200;
      background: var(--surface); transition: left 0.2s; padding-top: 60px; }
    .site-nav.nav-open { left: 0; box-shadow: 4px 0 20px rgba(0,0,0,0.4); }
    .nav-toggle { display: block; }
    .content { padding: 20px 16px; }
  }
  @media (min-width: 769px) {
    .nav-toggle { display: none; }
  }
  ```
- [ ] Syntax highlight: Hugo injects `<div class="highlight"><pre ...>` — style to match code-bg
  ```css
  .highlight { background: var(--code-bg); border-radius: 8px; border: 1px solid var(--border); margin: 1rem 0; overflow: hidden; }
  .highlight pre { padding: 16px; overflow-x: auto; margin: 0; }
  .highlight code { background: none; padding: 0; border-radius: 0; color: var(--code-text); }
  ```

---

### Phase 5: Tests and docs update (~5%)

**Files:**
- `docs/sprints/README.md` — No change needed
- `docs-site/README.md` — Modify (if exists, note the custom theme)

**Tasks:**
- [ ] Run full Hugo build: `hugo --source docs-site --minify` — must exit 0
- [ ] Verify all five pages are present in `docs-site/public/` (do not commit)
- [ ] Verify `docs-site/themes/attractor/` is tracked in git
- [ ] Verify `docs-site/themes/hugo-geekdoc/` is no longer in the working tree
- [ ] Update `docs-site/README.md` if it mentions the geekdoc theme

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `docs-site/themes/attractor/theme.toml` | Create | Theme metadata |
| `docs-site/themes/attractor/layouts/_default/baseof.html` | Create | Base HTML shell template |
| `docs-site/themes/attractor/layouts/_default/single.html` | Create | Single content page template |
| `docs-site/themes/attractor/layouts/_default/list.html` | Create | Section list page template |
| `docs-site/themes/attractor/layouts/index.html` | Create | Home page template |
| `docs-site/themes/attractor/layouts/partials/head.html` | Create | `<head>` with fonts, CSS, favicon, theme FOUC prevention |
| `docs-site/themes/attractor/layouts/partials/header.html` | Create | Top header with branding and theme toggle |
| `docs-site/themes/attractor/layouts/partials/nav.html` | Create | Sidebar navigation from content pages by weight |
| `docs-site/themes/attractor/layouts/partials/footer.html` | Create | Site footer |
| `docs-site/themes/attractor/static/css/attractor.css` | Create | Full theme stylesheet (design tokens, layout, components) |
| `docs-site/themes/attractor/static/js/attractor.js` | Create | Dark/light toggle + mobile nav |
| `docs-site/hugo.toml` | Modify | `theme = "attractor"`, remove geekdoc params |
| `docs-site/themes/hugo-geekdoc/` | Remove | Eliminate large vendored dependency |

## Definition of Done

### Theme
- [ ] `docs-site/themes/attractor/theme.toml` exists
- [ ] All 9 template files exist (`baseof.html`, `single.html`, `list.html`, `index.html`, 4 partials + 1 more)
- [ ] `attractor.css` defines both `[data-theme="dark"]` and `[data-theme="light"]` variable sets matching `WebMonitorServer.kt` exactly
- [ ] `attractor.js` implements FOUC-free theme initialization from `localStorage` (default `dark`)
- [ ] Dark/light mode toggle works in browser (click sun/moon icon → theme switches → persists on reload)

### Build
- [ ] `hugo --source docs-site --minify` exits 0 with zero errors or warnings
- [ ] All five content sections render: `web-app`, `rest-api`, `cli`, `dot-format`, `docker`
- [ ] Home page (`/`) renders with navigation cards to all five sections
- [ ] `docs-site/layouts/partials/microformats/` override is preserved and not overridden by the theme
- [ ] `docs-site/public/` is NOT committed (gitignored)

### Design fidelity
- [ ] Background color matches `--bg: #0d1117` in dark mode
- [ ] Accent color `#388bfd` (dark) / `#4f46e5` (light) used for links and active nav
- [ ] Font is Figtree (loaded from Google Fonts)
- [ ] Code blocks use dark monospace background matching the main app
- [ ] Tables render with `--surface-muted` headers and `--border` row separators
- [ ] Sidebar nav shows all five sections ordered by weight
- [ ] Active page highlighted in sidebar

### Cleanup
- [ ] `docs-site/themes/hugo-geekdoc/` directory removed from repo
- [ ] `hugo.toml` references only `theme = "attractor"` (no geekdoc params)
- [ ] No geekdoc-specific front matter left in content files (geekdoc front matter keys were not used in the content)

### Quality
- [ ] No new Gradle dependencies
- [ ] No `docs-site/public/` committed
- [ ] CSS and JS have no inline secrets or external calls beyond Google Fonts

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missing template type causes Hugo build error | Medium | High | Hugo requires at minimum `_default/single.html` and `_default/list.html`; both are in the plan. Test build early (after Phase 1 + minimal Phase 2). |
| Syntax highlighting requires Hugo extended binary | Low | Medium | `markup.highlight` is Hugo built-in; does not require extended. The CI action already uses `extended: true` as a safety margin. |
| FOUC (flash of wrong theme) on page load | Low | Medium | Inline script in `<head>` applies `data-theme` before CSS renders; standard technique. |
| `nav.html` uses `.Site.RegularPages` which may include `_index.md` | Low | Low | `RegularPages` excludes section/home pages (like `_index.md`) by Hugo convention; only leaf pages are included. |
| Mobile nav overlay blocks content | Low | Low | Fixed sidebar with `left: -260px` default, slides in on `.nav-open`; content remains interactive when nav is closed. |
| Removing geekdoc leaves orphaned override in `docs-site/layouts/partials/microformats/` | Low | Low | The microformats override is a project-level layout (not inside `themes/`); Hugo's lookup order means it takes precedence over theme partials regardless of which theme is active. Harmless. |
| Google Fonts unavailable in CI build | None | None | Hugo builds HTML/CSS; font loading is browser-side. CI build will pass without internet access since `<link>` is just markup. |

## Security Considerations

- No user-controlled input reaches the Hugo templates — all content is static Markdown
- `attractor.js` reads/writes `localStorage` only; no network requests
- Google Fonts CDN is the only third-party resource (same as the main app's approach)
- No API keys, credentials, or secrets in theme files
- `markup.goldmark.renderer.unsafe` is not enabled — raw HTML in Markdown is not rendered

## Dependencies

- Sprint 022 (completed) — created the Hugo docs-site foundation that this sprint builds on
- Hugo 0.157.0 extended (already used in CI; no version change needed)
- Google Fonts CDN (already used by the main app)
- No new Gradle or Go dependencies

## Open Questions

1. **Remove geekdoc?** The geekdoc vendor directory is large. This draft assumes it's removed after the new theme is verified. If there's a preference to keep it as a fallback, the removal step can be skipped — Hugo will simply never load it.

2. **Edit link?** The `hugo.toml` currently has `geekdocEditPath` for an edit-on-GitHub link. The new theme can expose this via a `[params] editPath` key and render it in `single.html`. This is a nice-to-have; the draft can include it or defer to a follow-up.

3. **Search?** geekdoc search was explicitly disabled (`geekdocSearch = false`). The new theme can omit search entirely for v1. If Lunr.js or Hugo's built-in search is wanted later, that's a follow-up sprint.

4. **Breadcrumbs?** Not in the current geekdoc setup. Omitted from the new theme. Could be added later.
