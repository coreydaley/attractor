# Sprint 025 Merge Notes

## Claude Draft Strengths
- Thorough CSS specification matching the main app's WebMonitorServer.kt design tokens exactly
- Complete template file set with concrete implementation snippets
- Phase 3 (content fidelity) explicitly walks through all five content pages
- Strong security section (noting safeHTML avoidance, localStorage only)
- Identified edit link as a needed feature (confirmed by interview)

## Codex Draft Strengths
- Cleaner phase structure (4 phases vs 5 in Claude draft)
- Correctly used `.ByWeight` instead of `.ByParam "weight"` — a real Hugo API distinction
- `favicon.svg` as a static file in the theme (separate from inline data URI in head)
- Named JS file as `theme.js` — slightly cleaner separation of concerns
- Noted that `microformats/schema.html` partial should be explicitly invoked in `head.html`
- Noted that `.Site.RegularPages.ByWeight` is the correct Hugo sort method

## Valid Critiques Accepted

1. **`ByParam "weight"` → `ByWeight`**: Use `.Site.RegularPages.ByWeight` and `.Pages.ByWeight` throughout all templates. The `ByParam` variant reads `.Params.weight`, not `.Weight`, and is not reliable for first-class Hugo fields.

2. **DoD wording on geekdoc front matter**: Changed to "Content renders correctly without requiring front-matter modifications" — leftover inert keys like `geekdocNav: false` in `_index.md` are harmless and the sprint's constraints explicitly say "no front-matter changes required unless unavoidable."

3. **Code blocks clarification**: Code blocks follow per-theme `--code-bg` token (dark in dark mode, `#f0f0f3` in light mode). This matches the main app's behavior exactly. The success criteria is updated to say "uses the app's per-theme `--code-bg` token" rather than "always dark."

4. **`color-scheme` CSS property**: Theme init/toggle should set `document.documentElement.style.colorScheme` alongside `data-theme` attribute for parity with browser native controls (scrollbars, form inputs).

5. **Duplicate theme init**: Inline `<head>` script does FOUC prevention only (sets `data-theme` attribute). The external `attractor.js` handles toggle logic and `color-scheme` updates. No duplication.

6. **Microformats partial**: `head.html` explicitly includes `partial "microformats/schema.html" .` so the project-level override at `docs-site/layouts/partials/microformats/` continues to function and suppress schema output.

7. **Favicon as static SVG**: Use a `static/favicon.svg` file with a fill color that works at both accent values (using the indigo `#4f46e5` as a neutral choice that reads well in both modes — same as the main app's data-URI favicon uses `#4f46e5` regardless of theme).

## Critiques Rejected (with reasoning)

- **None**: All Codex findings were valid and accepted.

## Interview Refinements Applied

1. **Remove hugo-geekdoc**: The geekdoc theme directory is definitively removed after the new theme is verified. Phase 3 in the final sprint explicitly includes deletion.

2. **Include edit link**: `single.html` renders an "Edit this page" link using `[params] editPath` in `hugo.toml`. The existing `geekdocEditPath` param is renamed to `editPath` in the new theme's config.

## Final Decisions

- Hugo template sort: `ByWeight` everywhere (not `ByParam "weight"`)
- Geekdoc removal: yes, definitively
- Edit link: yes, in single pages via `[params] editPath`
- Search: omitted in v1
- Code block background: per-theme token (matches app exactly)
- Favicon: static SVG at `themes/attractor/static/favicon.svg` with `#4f46e5` fill
- FOUC prevention: inline `<head>` script only; toggle in `attractor.js`
- JS file name: `attractor.js` (keeping Claude's name for consistency with CSS naming)
- `color-scheme` CSS property: set in both init and toggle
- Microformats: explicitly included in `head.html`
- DoD on front matter: no content changes required; inert geekdoc keys are fine
