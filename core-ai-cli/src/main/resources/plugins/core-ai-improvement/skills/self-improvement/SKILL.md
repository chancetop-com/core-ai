---
name: self-improvement
description: "Captures learnings, errors, and corrections to enable continuous improvement. Use when: (1) A command or operation fails unexpectedly, (2) User corrects Claude ('No, that's wrong...', 'Actually...'), (3) User requests a capability that doesn't exist, (4) An external API or tool fails, (5) Claude realizes its knowledge is outdated or incorrect, (6) A better approach is discovered for a recurring task. Also review learnings before major tasks."
---

# Self-Improvement Skill

Log learnings and errors to markdown files for continuous improvement. Coding agents can later process these into fixes, and important learnings get promoted to project memory.

## Core-AI Integration

**Plugin Location**: `{pluginDir}/skills/self-improvement/` (skill files & scripts bundled with plugin)
**Workspace Location**: `{workspace}/.core-ai/skills/self-improvement/` (learnings persist here)
**Hooks**: Automatically enabled via plugin hooks configuration

### How It Works

1. **Plugin Installation**: When the plugin is installed, skill files and scripts are deployed from the plugin
2. **Execution**: Hook scripts run from the plugin's scripts directory
3. **Storage**: Learning files are stored in the workspace's `.core-ai/skills/self-improvement/.learnings/`
4. **Promotion**: Important learnings get promoted to workspace memory files

## Quick Reference

| Situation | Action |
|-----------|--------|
| Command/operation fails | Log to `{workspace}/.core-ai/.learnings/ERRORS.md` |
| User corrects you | Log to `{workspace}/.core-ai/.learnings/LEARNINGS.md` with category `correction` |
| User wants missing feature | Log to `{workspace}/.core-ai/.learnings/FEATURE_REQUESTS.md` |
| API/external tool fails | Log to `{workspace}/.core-ai/.learnings/ERRORS.md` with integration details |
| Knowledge was outdated | Log to `{workspace}/.core-ai/.learnings/LEARNINGS.md` with category `knowledge_gap` |
| Found better approach | Log to `{workspace}/.core-ai/.learnings/LEARNINGS.md` with category `best_practice` |
| Simplify/Harden recurring patterns | Log to `{workspace}/.core-ai/.learnings/LEARNINGS.md` with `Source: simplify-and-harden` |
| Broadly applicable learning | Promote to `{workspace}/.core-ai/instructions.md` or `{workspace}/.core-ai/memory/*.md` |

## Directory Structure

```
{pluginDir}/skills/self-improvement/     # Plugin bundle (read-only)
├── SKILL.md                           # This skill file
└── scripts/                           # Hook scripts (executed from here)
    ├── activator.sh                  # UserPromptSubmit hook
    └── error-detector.sh              # PostToolUse hook

{workspace}/.core-ai/.learnings/          # Agent-level learning files (read-write)
├── LEARNINGS.md                      # Corrections, knowledge gaps, best practices
├── ERRORS.md                         # Command failures, exceptions
└── FEATURE_REQUESTS.md               # User-requested capabilities
```

## Create Learning Files

When the skill is first used, learning files are created in the workspace:

```bash
mkdir -p {workspace}/.core-ai/.learnings
```

Then create the log files (or copy from plugin assets if available):
- `{workspace}/.core-ai/.learnings/LEARNINGS.md` — corrections, knowledge gaps, best practices
- `{workspace}/.core-ai/.learnings/ERRORS.md` — command failures, exceptions
- `{workspace}/.core-ai/.learnings/FEATURE_REQUESTS.md` — user-requested capabilities

## Logging Format

### Learning Entry

Append to `{workspace}/.core-ai/.learnings/LEARNINGS.md`:

```markdown
## [LRN-YYYYMMDD-XXX] category

**Logged**: ISO-8601 timestamp
**Priority**: low | medium | high | critical
**Status**: pending
**Area**: frontend | backend | infra | tests | docs | config

### Summary
One-line description of what was learned

### Details
Full context: what happened, what was wrong, what's correct

### Suggested Action
Specific fix or improvement to make

### Metadata
- Source: conversation | error | user_feedback
- Related Files: path/to/file.ext
- See Also: LRN-20250110-001 (if related to existing entry)
- Pattern-Key: simplify.dead_code | harden.input_validation (optional, for recurring-pattern tracking)
- Recurrence-Count: 1 (optional)
- First-Seen: 2025-01-15 (optional)
- Last-Seen: 2025-01-15 (optional)

---
```

### Error Entry

Append to `{workspace}/.core-ai/.learnings/ERRORS.md`:

```markdown
## [ERR-YYYYMMDD-XXX] skill_or_command_name

**Logged**: ISO-8601 timestamp
**Priority**: high
**Status**: pending
**Area**: frontend | backend | infra | tests | docs | config

### Summary
Brief description of what failed

### Error
```
Actual error message or output
```

### Context
- Command/operation attempted
- Input or parameters used

### Suggested Fix
If identifiable, what might resolve this

### Metadata
- Reproducible: yes | no | unknown
- Related Files: path/to/file.ext
- See Also: ERR-20250110-001 (if recurring)

---
```

### Feature Request Entry

Append to `{workspace}/.core-ai/.learnings/FEATURE_REQUESTS.md`:

```markdown
## [FEAT-YYYYMMDD-XXX] capability_name

**Logged**: ISO-8601 timestamp
**Priority**: medium
**Status**: pending
**Area**: frontend | backend | infra | tests | docs | config

### Requested Capability
What the user wanted to do

### User Context
Why they needed it, what problem they're solving

### Complexity Estimate
simple | medium | complex

### Suggested Implementation
How this could be built, what it might extend

### Metadata
- Frequency: first_time | recurring
- Related Features: existing_feature_name

---
```

## ID Generation

Format: `TYPE-YYYYMMDD-XXX`
- TYPE: `LRN` (learning), `ERR` (error), `FEAT` (feature)
- YYYYMMDD: Current date
- XXX: Sequential number or random 3 chars (e.g., `001`, `A7B`)

Examples: `LRN-20250115-001`, `ERR-20250115-A3F`, `FEAT-20250115-002`

## Knowledge Lifecycle

Knowledge sections in `.learnings/` are **removed after being promoted to memory**. Knowledge the system decides is not yet worth promoting remains and may accumulate across sessions — recurrence is a signal to promote.

- Write freely — once a section is promoted to memory, it is removed from the document
- Knowledge not yet promoted stays for future review; if it recurs, bump `**Priority**` or increment `Recurrence-Count`
- Use `**Status**: wont_fix` to permanently exclude a section from auto-promotion

## Promoting to Project Memory

When a learning is broadly applicable (not a one-off fix), promote it to permanent project memory.
High-priority (`high` / `critical`) pending entries are also **auto-promoted** by the system at session start.

### When to Promote

- Learning applies across multiple files/features
- Knowledge any contributor (human or AI) should know
- Prevents recurring mistakes
- Documents project-specific conventions

### Promotion Targets

| Target | What Belongs There |
|--------|--------------------|
| `{workspace}/.core-ai/instructions.md` | Project facts, conventions, build commands, rules that apply to all interactions |
| `{workspace}/.core-ai/memory/` | Structured memories (feedback / user / project / reference) — auto-loaded into agent context |

### How to Promote

1. Distill the learning into a concise standalone statement
2. Write to the appropriate target using the agent's memory system (format and index are managed by the agent)
3. Remove the promoted section from the `.learnings/` source file

## Recurring Pattern Detection

If logging something similar to an existing entry:

1. **Search first**: `grep -r "keyword" {workspace}/.core-ai/.learnings/`
2. **Link entries**: Add `**See Also**: LRN-20250110-001` in Metadata
3. **Bump priority** if the issue keeps recurring
4. **Increment Recurrence-Count** and update `Last-Seen`
5. **Consider promotion** when `Recurrence-Count >= 3` across at least 2 sessions — recurring issues signal the knowledge belongs in permanent memory

## Detection Triggers

**Corrections** (→ record in LEARNINGS.md with category `correction`):
- "No, that's not right..."
- "Actually, it should be..."
- "You're wrong about..."
- "That's outdated..."

**Feature Requests** (→ record in FEATURE_REQUESTS.md):
- "Can you also..."
- "I wish you could..."
- "Is there a way to..."
- "Why can't you..."

**Knowledge Gaps** (→ record in LEARNINGS.md with category `knowledge_gap`):
- User provides information you didn't know
- Documentation you referenced is outdated
- API behavior differs from your understanding

**Errors** (→ record in ERRORS.md):
- Command returns non-zero exit code
- Exception or stack trace
- Unexpected output or behavior
- Timeout or connection failure

## Priority Guidelines

| Priority | When to Use |
|----------|-------------|
| `critical` | Blocks core functionality, data loss risk, security issue |
| `high` | Significant impact, affects common workflows, recurring issue |
| `medium` | Moderate impact, workaround exists |
| `low` | Minor inconvenience, edge case, nice-to-have |

## Area Tags

Use to filter learnings by codebase region:

| Area | Scope |
|------|-------|
| `frontend` | UI, components, client-side code |
| `backend` | API, services, server-side code |
| `infra` | CI/CD, deployment, Docker, cloud |
| `tests` | Test files, testing utilities, coverage |
| `docs` | Documentation, comments, READMEs |
| `config` | Configuration files, environment, settings |

## Best Practices

1. **Log immediately** - context is freshest right after the issue
2. **Be specific** - future agents need to understand quickly
3. **Include reproduction steps** - especially for errors
4. **Link related files** - makes fixes easier
5. **Suggest concrete fixes** - not just "investigate"
6. **Use consistent categories** - enables filtering
7. **Promote aggressively** - if in doubt, add to instructions.md or memory
8. **Review regularly** - stale learnings lose value

## Periodic Review

Review `{workspace}/.core-ai/.learnings/` at natural breakpoints:

### When to Review
- Before starting a new major task
- After completing a feature
- When working in an area with past learnings
- Weekly during active development

### Quick Status Check
```bash
# Count pending items
grep -h "Status**: pending" {workspace}/.core-ai/.learnings/*.md | wc -l

# List pending high-priority items
grep -B5 "Priority**: high" {workspace}/.core-ai/.learnings/*.md | grep "^## \["

# Find learnings for a specific area
grep -l "Area**: backend" {workspace}/.core-ai/.learnings/*.md
```

## Hook Scripts Reference

| Script | Hook Type | Purpose |
|--------|-----------|---------|
| `{pluginDir}/skills/self-improvement/scripts/activator.sh` | UserPromptSubmit | Reminds to evaluate learnings after tasks |
| `{pluginDir}/skills/self-improvement/scripts/error-detector.sh` | PostToolUse | Triggers on command errors |

These scripts are bundled with the plugin and executed from the plugin directory. Learning files are written to the workspace.
