# Sprint 025: Custom 'attractor' Hugo Theme for Docs-Site

## Overview

Sprint 022 introduced the Hugo docs microsite at `docs-site/` using the third-party `hugo-geekdoc` theme, vendored directly into `docs-site/themes/hugo-geekdoc/`. While functional, this creates two problems: the docs site no longer matches the main Attractor web application's visual identity, and the repository carries a large third-party dependency with hundreds of files we don't own or maintain.

This sprint creates a first-party Hugo theme named `attractor` that faithfully reproduces the visual design language of the main Attractor web application. The theme uses the same CSS custom properties (dark and light color palettes), the same Figtree typeface, the same border radii and spacing conventions, and the same code block styling from `WebMonitorServer.kt`. Dark mode is the default (matching the app), with a light mode toggle persisted via `localStorage`. After the theme is verified, the vendored `hugo-geekdoc` directory is deleted entirely, dramatically reducing repository size.

The result is a docs site that looks and feels like the product, is fully owned and maintainable within this repo, and builds with `hugo --source docs-site --minify` using only Hugo and no external tooling or Go modules.

## Use Cases

1. **Brand consistency**: A user arrives at the docs site from the Attractor web app. The header, colors, font, and layout feel immediately familiar — they are looking at the same product's documentation, not a generic third-party theme.

2. **Dark mode by default**: The docs site opens in dark mode (`#0d1117` background, `#388bfd` blue accent) matching the app's default. A sun/moon toggle in the header switches to light mode, persisted across page loads via `localStorage`.

3. **Sidebar navigation**: All five content sections (Web App, REST API, CLI, DOT Format, Docker) appear in a left sidebar, ordered by their `weight` front-matter value. The active page is highlighted with the accent color.

4. **Edit on GitHub**: Each content page includes an "Edit this page" link at the bottom, pointing to the file in the GitHub repository for easy contribution.

5. **Repo hygiene**: Removing `docs-site/themes/hugo-geekdoc/` eliminates hundreds of third-party files from the repo, making `git clone` faster and history cleaner.

6. **Mobile responsive**: On narrow screens the sidebar collapses; a hamburger button reveals it as an overlay.

## Architecture

```
docs-site/
├── hugo.toml                              ← theme = "attractor"; editPath param
├── content/                               ← unchanged Markdown pages
│   ├── _index.md                          ← home page (inert geekdocNav key is harmless)
│   ├── web-app.md        (weight: 10)
│   ├── rest-api.md       (weight: 20)
│   ├── cli.md            (weight: 30)
│   ├── dot-format.md     (weight: 40)
│   └── docker.md         (weight: 50)
├── layouts/
│   └── partials/
│       └── microformats/                  ← preserved project-level override (unaffected)
└── themes/
    └── attractor/                         ← NEW: custom theme
        ├── theme.toml
        ├── layouts/
        │   ├── _default/
        │   │   ├── baseof.html            ← global HTML shell
        │   │   ├── single.html            ← single content page (with edit link)
        │   │   └── list.html              ← list/section page
        │   ├── index.html                 ← home page
        │   └── partials/
        │       ├── head.html              ← fonts, CSS, favicon, FOUC-free theme init
        │       ├── header.html            ← branding + dark/light toggle
        │       ├── nav.html               ← sidebar (ByWeight, active highlighting)
        │       └── footer.html
        └── static/
            ├── css/attractor.css          ← full stylesheet
            ├── js/attractor.js            ← toggle logic + mobile nav
            └── favicon.svg               ← ⚡ in rounded rect, #4f46e5 fill

CSS variable sets (mirrors WebMonitorServer.kt exactly):

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

Template rendering flow:
  baseof.html
    ├── partials/head.html    (fonts, CSS, favicon, FOUC-prevention inline script)
    ├── partials/header.html  (branding, theme toggle)
    ├── partials/nav.html     (sidebar: RegularPages.ByWeight, active highlight)
    ├── block "main"          ← filled by single.html / list.html / index.html
    └── partials/footer.html
```

## Implementation Plan

### Phase 1: Theme scaffold + CSS (~30%)

**Files:**
- `docs-site/themes/attractor/theme.toml` — Create
- `docs-site/themes/attractor/static/css/attractor.css` — Create
- `docs-site/themes/attractor/static/js/attractor.js` — Create
- `docs-site/themes/attractor/static/favicon.svg` — Create

**Tasks:**
- [ ] Create all theme subdirectories: `layouts/_default/`, `layouts/partials/`, `static/css/`, `static/js/`
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
- [ ] Create `docs-site/themes/attractor/static/favicon.svg`:
  ```svg
  <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32">
    <rect width="32" height="32" rx="8" fill="#4f46e5"/>
    <text x="50%" y="50%" font-size="20" text-anchor="middle" dominant-baseline="central">⚡</text>
  </svg>
  ```
- [ ] Create `docs-site/themes/attractor/static/css/attractor.css` with:
  - CSS reset
  - `[data-theme="dark"]` and `[data-theme="light"]` variable blocks (exact match to `WebMonitorServer.kt`)
  - Body: `font-family: 'Figtree', 'Segoe UI', system-ui, -apple-system, sans-serif; background: var(--bg); color: var(--text);`
  - Page layout: flexbox column — sticky header, `div.layout` as row (nav 240px + main flex-1), footer
  - Header: `background: var(--surface); border-bottom: 1px solid var(--border); padding: 12px 20px; display: flex; align-items: center; gap: 12px;`
  - `.site-brand`: flex row, gap 8px, text-decoration none, color var(--text-strong), font-weight 600
  - `.site-title`: font-size 1.05rem
  - `.theme-toggle`: background none, border 1px solid var(--border), border-radius 6px, padding 4px 8px, cursor pointer, color var(--text-muted); hover: color var(--text)
  - `.nav-toggle`: hidden on desktop; shown on mobile (hamburger)
  - `.site-nav`: width 240px, background var(--surface), border-right 1px solid var(--border), padding 20px 0, height calc(100vh - 48px), position sticky, top 48px, overflow-y auto
  - `.site-nav ul`: list-style none
  - `.site-nav a`: display block, padding 8px 16px, color var(--text-muted), text-decoration none, font-size 0.88rem, border-left 3px solid transparent
  - `.site-nav a:hover`: color var(--text), background var(--surface-raised)
  - `.site-nav a.nav-active`: border-left-color var(--accent), color var(--text-strong), background var(--surface-raised), font-weight 600
  - `.content`: flex 1, padding 32px 40px, max-width 900px, line-height 1.7, overflow-wrap break-word
  - Headings: h1 color var(--text-strong) 1.8rem font-weight 700 margin-bottom 1rem; h2 1.3rem 600 margin 1.5rem 0 0.75rem; h3 1.1rem 600 margin 1.2rem 0 0.5rem; h4/h5/h6 1rem 600
  - `p { margin-bottom: 1rem; }`
  - `a { color: var(--accent); text-decoration: none; } a:hover { text-decoration: underline; }`
  - `strong { color: var(--text-strong); font-weight: 600; }`
  - `ul, ol { margin: 0.5rem 0 1rem 1.5rem; } li { margin-bottom: 0.25rem; }`
  - `hr { border: none; border-top: 1px solid var(--border); margin: 2rem 0; }`
  - `blockquote { border-left: 3px solid var(--accent); padding: 8px 16px; margin: 1rem 0; background: var(--surface-muted); border-radius: 0 6px 6px 0; color: var(--text-muted); font-size: 0.88rem; }`
  - `code (inline) { background: var(--surface-muted); color: var(--accent); padding: 2px 5px; border-radius: 4px; font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.88em; }`
  - `pre { background: var(--code-bg); color: var(--code-text); padding: 16px; border-radius: 8px; border: 1px solid var(--border); overflow-x: auto; margin: 1rem 0; }` `pre code { background: none; padding: 0; border-radius: 0; color: inherit; font-size: 0.82rem; line-height: 1.6; }`
  - `.highlight { background: var(--code-bg); border-radius: 8px; border: 1px solid var(--border); margin: 1rem 0; overflow: hidden; }` `.highlight pre { padding: 16px; margin: 0; overflow-x: auto; }` `.highlight code { background: none; padding: 0; border-radius: 0; color: var(--code-text); font-family: 'Consolas','Cascadia Code','Courier New',monospace; font-size: 0.82rem; line-height: 1.6; }`
  - `table { width: 100%; border-collapse: collapse; margin: 1rem 0; font-size: 0.85rem; }` `th { background: var(--surface-muted); color: var(--text-muted); text-transform: uppercase; letter-spacing: 0.06em; font-size: 0.74rem; font-weight: 600; padding: 8px 12px; text-align: left; }` `td { padding: 8px 12px; border-bottom: 1px solid var(--border); }` `tr:last-child td { border-bottom: none; }`
  - `.edit-link { margin-top: 3rem; padding-top: 1rem; border-top: 1px solid var(--border); font-size: 0.8rem; color: var(--text-faint); }` `.edit-link a { color: var(--text-muted); }` `.edit-link a:hover { color: var(--accent); }`
  - `.home-nav { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin-top: 24px; }`
  - `.home-nav-card { display: block; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px 18px; text-decoration: none; color: var(--text); transition: border-color 0.15s; }` `.home-nav-card:hover { border-color: var(--accent); }` `.home-nav-title { font-weight: 600; font-size: 0.9rem; display: block; }`
  - Footer: `background: var(--surface); border-top: 1px solid var(--border); padding: 16px 20px; font-size: 0.78rem; color: var(--text-faint); text-align: center;` `footer a { color: var(--text-muted); }`
  - Responsive mobile (`@media (max-width: 768px)`):
    - `.layout { flex-direction: column; }`
    - `.site-nav { position: fixed; top: 0; left: -260px; height: 100vh; width: 240px; z-index: 200; padding-top: 60px; transition: left 0.2s; }` `.site-nav.nav-open { left: 0; box-shadow: 4px 0 20px rgba(0,0,0,0.4); }`
    - `.nav-toggle { display: block; }`
    - `.content { padding: 20px 16px; }`
  - `@media (min-width: 769px) { .nav-toggle { display: none; } }`

- [ ] Create `docs-site/themes/attractor/static/js/attractor.js`:
  ```javascript
  // FOUC prevention is handled by the inline script in head.html.
  // This file handles toggle interaction and mobile nav.

  function toggleTheme() {
    var current = document.documentElement.getAttribute('data-theme') || 'dark';
    var next = current === 'dark' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    document.documentElement.style.colorScheme = next;
    localStorage.setItem('attractor-theme', next);
    var btn = document.getElementById('themeToggle');
    if (btn) btn.textContent = next === 'dark' ? '\u263d' : '\u2600'; // moon / sun
  }

  document.addEventListener('DOMContentLoaded', function() {
    // Sync toggle icon to current theme
    var theme = document.documentElement.getAttribute('data-theme') || 'dark';
    var btn = document.getElementById('themeToggle');
    if (btn) btn.textContent = theme === 'dark' ? '\u263d' : '\u2600';

    // Mobile nav toggle
    var navToggle = document.getElementById('navToggle');
    var nav = document.getElementById('siteNav');
    if (navToggle && nav) {
      navToggle.addEventListener('click', function() {
        nav.classList.toggle('nav-open');
      });
      // Close nav when a link is clicked (mobile UX)
      nav.querySelectorAll('a').forEach(function(link) {
        link.addEventListener('click', function() {
          nav.classList.remove('nav-open');
        });
      });
    }
  });
  ```

---

### Phase 2: Layout templates (~40%)

**Files:**
- `docs-site/themes/attractor/layouts/partials/head.html` — Create
- `docs-site/themes/attractor/layouts/partials/header.html` — Create
- `docs-site/themes/attractor/layouts/partials/nav.html` — Create
- `docs-site/themes/attractor/layouts/partials/footer.html` — Create
- `docs-site/themes/attractor/layouts/_default/baseof.html` — Create
- `docs-site/themes/attractor/layouts/_default/single.html` — Create
- `docs-site/themes/attractor/layouts/_default/list.html` — Create
- `docs-site/themes/attractor/layouts/index.html` — Create

**Tasks:**
- [ ] Create `layouts/partials/head.html`:
  ```html
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>{{ if .IsHome }}{{ .Site.Title }}{{ else }}{{ .Title }} &mdash; {{ .Site.Title }}{{ end }}</title>
  <link rel="icon" type="image/svg+xml" href="{{ "favicon.svg" | relURL }}">
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Figtree:wght@300;400;500;600;700&display=swap" rel="stylesheet">
  <link rel="stylesheet" href="{{ "css/attractor.css" | relURL }}">
  <script>
    // Apply stored theme before CSS renders to prevent flash
    (function() {
      var t = localStorage.getItem('attractor-theme') || 'dark';
      document.documentElement.setAttribute('data-theme', t);
      document.documentElement.style.colorScheme = t;
    })();
  </script>
  {{ partial "microformats/schema.html" . }}
  ```

- [ ] Create `layouts/partials/header.html`:
  ```html
  <header>
    <button id="navToggle" class="nav-toggle" aria-label="Toggle navigation">&#9776;</button>
    <a href="{{ "/" | relURL }}" class="site-brand">
      <img src="{{ "favicon.svg" | relURL }}" width="24" height="24" alt="Attractor">
      <span class="site-title">{{ .Site.Title }}</span>
    </a>
    <button id="themeToggle" class="theme-toggle" onclick="toggleTheme()" title="Toggle dark/light mode" aria-label="Toggle theme">&#9728;</button>
  </header>
  ```

- [ ] Create `layouts/partials/nav.html`:
  ```html
  <nav id="siteNav" class="site-nav">
    <ul>
      {{- range .Site.RegularPages.ByWeight }}
      <li>
        <a href="{{ .RelPermalink }}"{{ if eq .RelPermalink $.RelPermalink }} class="nav-active"{{ end }}>
          {{ .Title }}
        </a>
      </li>
      {{- end }}
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
  <html lang="{{ .Site.Language.Lang | default "en" }}" data-theme="dark">
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
  <article>
    <h1>{{ .Title }}</h1>
    {{ .Content }}
    {{- with .Site.Params.editPath }}
    <div class="edit-link">
      <a href="{{ . }}/{{ $.File.Path }}" target="_blank" rel="noopener">Edit this page on GitHub</a>
    </div>
    {{- end }}
  </article>
  {{ end }}
  ```

- [ ] Create `layouts/_default/list.html`:
  ```html
  {{ define "main" }}
  <article>
    <h1>{{ .Title }}</h1>
    {{ .Content }}
    {{ range .Pages.ByWeight }}
    <section style="margin-top: 1.5rem;">
      <h2><a href="{{ .RelPermalink }}">{{ .Title }}</a></h2>
      <p>{{ .Summary }}</p>
    </section>
    {{ end }}
  </article>
  {{ end }}
  ```

- [ ] Create `layouts/index.html`:
  ```html
  {{ define "main" }}
  <article>
    <h1>{{ .Site.Title }}</h1>
    {{ .Content }}
    <nav class="home-nav">
      {{ range .Site.RegularPages.ByWeight }}
      <a href="{{ .RelPermalink }}" class="home-nav-card">
        <span class="home-nav-title">{{ .Title }}</span>
      </a>
      {{ end }}
    </nav>
  </article>
  {{ end }}
  ```

---

### Phase 3: Config update and geekdoc removal (~15%)

**Files:**
- `docs-site/hugo.toml` — Modify
- `docs-site/themes/hugo-geekdoc/` — Remove

**Tasks:**
- [ ] Update `docs-site/hugo.toml`:
  - Change `theme = "hugo-geekdoc"` to `theme = "attractor"`
  - Remove the `[params]` block with `geekdocNav`, `geekdocSearch`, `geekdocEditPath`
  - Add `[params]` block with `editPath`:
    ```toml
    [params]
      editPath = "https://github.com/coreydaley/attractor/edit/main/docs-site/content"
    ```
  - Keep `baseURL`, `title`, `pluralizeListTitles`, `[markup.highlight]`, `[minify]` unchanged
- [ ] Run `hugo --source docs-site` to verify build passes with new theme (before removing geekdoc)
- [ ] Verify all five content pages render without errors
- [ ] Verify the home page navigation cards appear
- [ ] Remove `docs-site/themes/hugo-geekdoc/` directory: `rm -rf docs-site/themes/hugo-geekdoc`
- [ ] Run `hugo --source docs-site --minify` again to confirm final build passes after removal

---

### Phase 4: Content fidelity verification (~15%)

**Files:**
- `docs-site/themes/attractor/static/css/attractor.css` — Modify if needed

**Tasks:**
- [ ] Visually inspect (or build locally and diff the output) all five content pages:
  - `web-app.md` — tables, code blocks, blockquotes (tip boxes), unordered lists
  - `rest-api.md` — bold HTTP method labels (`**GET**`), code blocks, nested headings
  - `cli.md` — code blocks, command examples, nested lists
  - `dot-format.md` — fenced code blocks (DOT language), tables, annotated examples
  - `docker.md` — code blocks, environment variable tables
- [ ] Confirm `docs-site/layouts/partials/microformats/` override is present and renders without error
- [ ] Confirm edit link appears on single pages and points to the correct GitHub URL
- [ ] Add any missing CSS rules discovered during inspection (e.g., definition lists, nested blockquotes)

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `docs-site/hugo.toml` | Modify | `theme = "attractor"`, add `editPath` param, remove geekdoc params |
| `docs-site/themes/attractor/theme.toml` | Create | Theme metadata |
| `docs-site/themes/attractor/layouts/_default/baseof.html` | Create | Global HTML shell |
| `docs-site/themes/attractor/layouts/_default/single.html` | Create | Single content page (with edit link) |
| `docs-site/themes/attractor/layouts/_default/list.html` | Create | Section list page |
| `docs-site/themes/attractor/layouts/index.html` | Create | Home page with nav cards |
| `docs-site/themes/attractor/layouts/partials/head.html` | Create | `<head>`: fonts, CSS, favicon, FOUC-free theme init, microformats partial |
| `docs-site/themes/attractor/layouts/partials/header.html` | Create | Branding + dark/light toggle |
| `docs-site/themes/attractor/layouts/partials/nav.html` | Create | Sidebar nav (ByWeight, active highlight) |
| `docs-site/themes/attractor/layouts/partials/footer.html` | Create | Footer |
| `docs-site/themes/attractor/static/css/attractor.css` | Create | Full theme stylesheet |
| `docs-site/themes/attractor/static/js/attractor.js` | Create | Dark/light toggle + mobile nav |
| `docs-site/themes/attractor/static/favicon.svg` | Create | Lightning bolt favicon matching app branding |
| `docs-site/themes/hugo-geekdoc/` | Remove | Eliminate large vendored third-party theme |

## Definition of Done

### Theme files
- [ ] `docs-site/themes/attractor/theme.toml` exists
- [ ] All 8 template files exist: `baseof.html`, `single.html`, `list.html`, `index.html`, `head.html`, `header.html`, `nav.html`, `footer.html`
- [ ] `attractor.css` defines both `[data-theme="dark"]` and `[data-theme="light"]` variable sets matching `WebMonitorServer.kt` exactly
- [ ] `attractor.js` implements dark/light toggle setting both `data-theme` attribute and `color-scheme` CSS property; mobile nav toggle
- [ ] `favicon.svg` present with lightning bolt design

### Build
- [ ] `hugo --source docs-site --minify` exits 0 with zero errors
- [ ] All five content pages render: `web-app`, `rest-api`, `cli`, `dot-format`, `docker`
- [ ] Home page (`/`) renders with navigation cards to all five sections
- [ ] Edit link appears on each content page and points to correct GitHub URL
- [ ] `docs-site/layouts/partials/microformats/` override is preserved and invoked via `head.html`
- [ ] `docs-site/public/` is NOT committed (gitignored)

### Design fidelity
- [ ] Default theme is dark (`--bg: #0d1117`)
- [ ] Dark mode accent: `#388bfd`; light mode accent: `#4f46e5`
- [ ] Font is Figtree (loaded from Google Fonts)
- [ ] Code blocks: `--code-bg` / `--code-text` per theme, monospace font, dark background in dark mode
- [ ] Tables: `--surface-muted` header, `--border` row separators
- [ ] Sidebar nav lists all five sections ordered by weight
- [ ] Active page highlighted (accent left border, bold text, raised background)
- [ ] Dark/light toggle persists across page loads (stored in `localStorage['attractor-theme']`)
- [ ] FOUC-free: inline `<head>` script applies theme before CSS renders
- [ ] `color-scheme` CSS property set alongside `data-theme` on init and toggle

### Cleanup
- [ ] `docs-site/themes/hugo-geekdoc/` removed from repository
- [ ] `hugo.toml` uses `theme = "attractor"` only; no geekdoc-specific params
- [ ] Content Markdown files are unchanged (inert `geekdocNav: false` in `_index.md` is harmless)

### Quality
- [ ] No new Gradle dependencies
- [ ] No `docs-site/public/` committed
- [ ] No safeHTML usage for dynamic content in templates
- [ ] Google Fonts is the only third-party CDN resource

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Missing template type causes Hugo build error | Medium | High | Hugo requires at minimum `_default/single.html` and `_default/list.html` plus `index.html` for the home page; all three are in scope. Test build early after minimal Phase 1+2 scaffolding. |
| `nav.html` active-link detection broken when page URL has trailing slash differences | Low | Medium | Use `eq .RelPermalink $.RelPermalink` — Hugo normalizes trailing slashes consistently. |
| Flash of wrong theme (FOUC) on page load | Low | Medium | Inline `<script>` in `<head>` (before CSS) applies `data-theme` attribute from `localStorage`. This is the standard prevention technique. |
| Sidebar uses `.ByWeight` but some pages have no `weight` — wrong order | Low | Low | All five content pages already have explicit `weight` front matter (10–50). Unweighted pages sort to the end; harmless. |
| `microformats/schema.html` partial missing from theme causes build error | Low | High | The project-level override at `docs-site/layouts/partials/microformats/schema.html` satisfies the lookup; calling the partial in `head.html` is safe. |
| Removing geekdoc reveals a missing CSS class or template | Low | Medium | Verify build passes before deletion (Phase 3 explicitly tests before removing). |
| Google Fonts unavailable in CI build environment | None | None | Hugo emits a `<link>` tag; actual font download is browser-side. CI build is unaffected. |
| Edit link renders on home page (`_index.md`) | Low | Low | `single.html` is not used for home page — `index.html` is used instead. The `editPath` param is only read in `single.html`. |

## Security Considerations

- No user-controlled input reaches Hugo templates — all content is static Markdown
- `attractor.js` reads and writes only `localStorage` key `'attractor-theme'` (a `"dark"` or `"light"` string); no network requests
- No `safeHTML` or `safeJS` Hugo functions used for dynamic content in templates
- Google Fonts CDN is the only third-party resource (consistent with the main app's existing approach)
- No API keys, credentials, or secrets in theme files

## Dependencies

- Sprint 022 (completed) — created the Hugo docs-site foundation this sprint builds on
- Hugo 0.157.0 extended (already used in CI via `peaceiris/actions-hugo@v3`; no version change)
- Google Fonts CDN (already used by the main Attractor web app)
- No new Gradle or Go dependencies

## Open Questions

1. **Search in v1?** Search was explicitly disabled in geekdoc (`geekdocSearch = false`). The new theme omits search entirely. If Lunr.js or Hugo's built-in search index is wanted, that's a follow-up sprint.

2. **Breadcrumbs?** Not used in the current geekdoc setup. Omitted in v1. Could be added later.

3. **`color-scheme` support in `baseof.html`?** The `data-theme="dark"` in the `<html>` tag serves as a fallback for initial server-rendered HTML before JS runs. The FOUC-prevention inline script overwrites it immediately, so the default is cosmetic. Considered acceptable.
