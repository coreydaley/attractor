# Sprint Management

This directory contains sprint planning documents for structured, iterative development.

## Quick Reference

```bash
# View sprint stats
python3 docs/sprints/ledger.py stats

# List all sprints
python3 docs/sprints/ledger.py list

# List by status
python3 docs/sprints/ledger.py list --status completed

# Sync ledger from .md files (run after creating new sprints)
python3 docs/sprints/ledger.py sync

# Start a sprint
python3 docs/sprints/ledger.py start 001

# Complete a sprint
python3 docs/sprints/ledger.py complete 001

# Add a new sprint manually
python3 docs/sprints/ledger.py add 002 "Sprint Title"
```

## File Structure

```
docs/sprints/
├── README.md           # This file
├── ledger.tsv          # Sprint tracking database (TSV format)
├── ledger.py           # CLI tool for sprint management
├── drafts/             # Working drafts during planning
│   ├── SPRINT-NNN-INTENT.md
│   └── SPRINT-NNN-DRAFT.md
├── SPRINT-001.md       # Sprint documents (zero-padded 3 digits)
├── SPRINT-002.md
└── ...
```

## Creating a New Sprint

### Option 1: Using the /megaplan command

```
/megaplan <description of what you want to build>
```

This will guide you through a structured planning workflow.

### Option 2: Manual creation

1. **Determine the next sprint number**:
   ```bash
   ls docs/sprints/SPRINT-*.md | tail -1
   ```

2. **Create the sprint document** using the template below

3. **Sync the ledger**:
   ```bash
   python3 docs/sprints/ledger.py sync
   ```

## Sprint Document Template

```markdown
# Sprint NNN: Title

## Overview

Brief description of the sprint goals and motivation (2-3 paragraphs on the "why").

## Use Cases

1. **Use case name**: Description
2. ...

## Architecture

Diagrams (ASCII art), component descriptions, data flow.

## Implementation Plan

### Phase 1: Name (~X%)

**Files:**
- `path/to/file` - Description

**Tasks:**
- [ ] Task 1
- [ ] Task 2

### Phase 2: ...

## Files Summary

| File | Action | Purpose |
|------|--------|---------|
| `path/to/file` | Create/Modify | Description |

## Definition of Done

- [ ] Criterion 1
- [ ] Criterion 2
- [ ] Tests pass
- [ ] Code reviewed

## Risks & Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| ... | ... | ... | ... |

## Security Considerations

- Item 1
- Item 2

## Dependencies

- Sprint NNN (if any)
- External requirements

## Open Questions

Uncertainties needing resolution.
```

## Sprint Statuses

| Status | Meaning |
|--------|---------|
| `planned` | Sprint is defined but not started |
| `in_progress` | Actively being worked on |
| `completed` | All Definition of Done items met |
| `skipped` | Decided not to implement |

## Conventions

### Naming
- Files: `SPRINT-NNN.md` (zero-padded 3 digits)
- Title format: `# Sprint NNN: Short Descriptive Title`

### Content
- **Overview**: 1-2 paragraphs explaining the "why"
- **Use Cases**: Concrete scenarios this sprint enables
- **Implementation Plan**: Break into phases with percentage estimates
- **Definition of Done**: Checkboxes for acceptance criteria
- **Files Summary**: Table of files to create/modify

### Lifecycle
1. Create sprint doc with status `planned`
2. Run `ledger.py sync` to add to ledger
3. When starting: `ledger.py start NNN`
4. When done: `ledger.py complete NNN`

## For AI Assistants

When asked to create a sprint:
1. Check the highest existing sprint number
2. Create `SPRINT-{N+1}.md` using the template
3. Run `python3 docs/sprints/ledger.py sync`
4. Update status if starting immediately

When completing work:
1. Ensure all Definition of Done items are checked
2. Run `python3 docs/sprints/ledger.py complete NNN`
3. Commit and push the changes
