# Attractor Docs Site

Hugo microsite published to GitHub Pages at https://attractor.coreydaley.dev.

## Requirements

- [Hugo extended](https://gohugo.io/installation/) v0.157.0+
- [Node.js](https://nodejs.org/) (for Pagefind search indexing)

## Common tasks

```bash
make serve          # live-reload dev server on http://localhost:1313 (no search)
make build          # production build into public/
make index          # run Pagefind on public/ to generate the search index
make serve-search   # build + index + serve static site with search working
make clean          # delete public/ and resources/
```

Run from this directory (`docs/site/`) or from the repo root with `make -C docs/site <target>`.

## Content

All pages live in `docs/site/content/`. Each file is plain Markdown with a front matter block:

```
---
title: "Page Title"
weight: 10
---
```

`weight` controls the order pages appear in the sidebar.

## Deployment

Pushes to `main` that touch any file under `docs/site/` automatically trigger the
[Docs workflow](../../.github/workflows/docs.yml), which builds, indexes with Pagefind,
and deploys to GitHub Pages.

---

Full documentation: https://attractor.coreydaley.dev
