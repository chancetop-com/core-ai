# Memory System

core-ai-cli has a 4-layer persistent memory system that accumulates knowledge across sessions.

## Architecture

```
Session → daily-logs → episodes → knowledge wiki pages
```

| Layer | Location | Content |
|-------|----------|---------|
| daily-logs | `.core-ai/daily-logs/{date}/{task}.md` | Per-task summaries (Context, Actions, Obstacles, etc.) |
| episodes | `.core-ai/episodes/{date}.md` | Daily index mapping tasks to daily-logs |
| knowledge | `.core-ai/knowledge/MEMORY.md` | Master index (always loaded) |
| knowledge | `.core-ai/knowledge/{type}/{name}.md` | Wiki-style pages (project/user/feedback/reference) |
| knowledge | `.core-ai/knowledge/log.md` | Change log (auto-pruned to 30 days) |

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `agent.memory.enabled` | `true` | Master switch |
| `agent.memory.daily.logs.enabled` | `false` | Enable 4-layer pipeline (vs. direct wiki-only) |
| `agent.memory.prompt.extraction` | `false` | Incremental extraction after each prompt |
| `agent.memory.timezone` | system | Timezone for timestamps (e.g. `Asia/Shanghai`) |

## Two Modes

### Full Pipeline (daily.logs.enabled=true)

```
Session close
  ├── fork "session-close" agent
  ├── create daily-logs (per-task)
  ├── create lock file (.core-ai/daily-logs/{date}.lock)
  └── next startup → MemoryTriggerService
        ├── read lock
        ├── deep extract → episodes
        ├── deep extract → knowledge wiki pages
        └── delete lock
```

### Direct Mode (daily.logs.enabled=false)

```
Knowledge extracted directly to wiki pages
No intermediate daily-logs or episodes
```

When `daily.logs.enabled=false`, the memory spec tool returns DIRECT_EXTRACTION_SPEC
— a simplified spec without daily-logs/episodes layers. The agent skips daily-log
creation and writes directly to knowledge wiki pages.

## Enabling Memory

```properties
# Full pipeline
agent.memory.enabled=true
agent.memory.daily.logs.enabled=true

# Direct wiki-only mode
agent.memory.enabled=true
agent.memory.daily.logs.enabled=false
```

Also configurable at runtime via `/memory enable` and `/memory disable` commands.

## Memory Commands

| Command | Action |
|---------|--------|
| `/memory` | Show memory sub-menu |
| `/memory edit` | Edit a memory file |
| `/memory search <k>` | Search memories |
| `/memory open` | Open memory folder |
| `/memory clear` | Delete all knowledge wiki pages |
| `/memory enable` | Set `agent.memory.enabled=true` |
| `/memory disable` | Set `agent.memory.enabled=false` |

## Knowledge Types

| Type | Purpose | Example |
|------|---------|---------|
| project | Architecture, key files, design rationale | "Why this tech stack?" |
| user | Preferences, workflow habits | "Always use local wrapper" |
| feedback | Corrections, validated approaches | "Don't use X, use Y instead" |
| reference | External docs, APIs, upstream specs | "External API at https://..." |

## How LLM Uses Memory

The agent always loads `knowledge/MEMORY.md` (the index). It then reads specific
knowledge pages on demand via `read_file` when relevant to the current task.
Cross-reference via `source:` links to trace back to daily-log evidence.
