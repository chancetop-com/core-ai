# Frontend UX & Interaction Design

> Companion to `session-and-trace-design.md`. The model doc covers **what data exists**; this doc covers **how users work with it in the UI**.

---

## 1. Who Are the Users, What Are They Doing

Three distinct audiences share our frontend. They overlap but have different primary tasks:

| Persona | Primary Task | Entry Page | Depth Needed |
|---|---|---|---|
| **End user** | Talk to an agent | Chat | Low — just the conversation |
| **Developer** | Debug a failing turn, optimize prompts, test agents | Traces / Agent Detail | High — span tree, I/O, errors |
| **Operator / Owner** | Monitor usage, attribute cost, audit runs | Traces / Scheduler | Mid — aggregates, filtering |

**Implication**: the Traces page is **not** the universal entry point. Each persona has their own natural first screen. Cross-linking is the key — every page should let users pivot when they need a different view.

---

## 2. Core Design Principles

Four principles that every page should follow:

1. **Trace detail over trace analytics**
   We are an agent platform with observability included, not an observability product. Deep aggregate analytics (p95 latency over time, cost trends) are better handled by Langfuse. Our UI should excel at **explaining one specific invocation**.

2. **Context over metrics**
   A user debugging a bad response wants to know "which agent, which session, what was the input" — not "what was p50 latency". Put context (agent/session/user) ahead of performance numbers in visual hierarchy.

3. **Three clicks to any trace detail**
   From any starting page (Chat, Agent, Scheduler, Traces), drilling into a specific trace should take at most 3 clicks. Every row that represents an invocation must have a clear drill-in affordance.

4. **Dual audience, separate flows, shared data**
   End users live in Chat. Developers live in Traces/Agent. Don't mix their concerns on the same surface, but make it easy to cross over (e.g., "View trace for this turn" button inside Chat detail).

---

## 3. Information Hierarchy — 5 Levels

Every piece of information should be placed at the right **visibility level**:

| Level | Surface | Use for |
|---|---|---|
| **L1 — Always visible** | Column value, pill, badge | Identity + status — 1-2 words or icon |
| **L2 — In-row preview** | Second line under primary | Input snippet, first 80-120 chars |
| **L3 — On hover** | Tooltip | Full timestamp, full IDs, low-priority attributes |
| **L4 — One click** | Side panel | Structured summary, key I/O, span count |
| **L5 — Tab / expand** | Full detail | Raw JSON, span tree, complete I/O |

The goal: users should complete **80% of tasks using L1–L2 only**, without clicking.

### Example — Trace list row

| Level | Content |
|---|---|
| L1 | `[🌐 Chat] agent.turn · 💬 Xander · gpt-5.1 · COMPLETED · 426 tok · 1.2s · 3m ago` |
| L2 | `"Hello, please summarize this document..."` (first 80 chars of input) |
| L3 | Hover on session chip → full session ID + message count |
| L4 | Click row → side panel with overview |
| L5 | Side panel Spans tab → full tree |

---

## 4. Page-by-Page Design

### 4.1 Chat Page

**Purpose**: end-user conversation. Not a debug surface.

**Layout**:
```
┌── Sidebar (240px) ──┬── Conversation (flex) ──┐
│ [+ New Chat]        │ [Agent selector ▾]      │
│                     │                         │
│ ● Current session   │ Messages scroll         │
│   Xander test 2     │                         │
│   5 min · 12 msg    │                         │
│                     │                         │
│ ○ 你好              │                         │
│   Default           │                         │
│   1h · 6 msg        │                         │
│                     │                         │
│ [hover: 🗑]          │ [Input box ──────────]  │
└─────────────────────┴─────────────────────────┘
```

**Interactions**:
- Click session in sidebar → hydrate history + reconnect SSE
- Hover session → trash icon appears; click → confirm + soft-delete
- `+ New Chat` → clear state, close current session
- Agent switch → warn if current session has messages, create new session on first send

**Hidden power**:
- Each message bubble has a quiet `View trace` action (hover or `⋯` menu) → jumps to Traces page filtered to that turn. This is how developers cross over into debug flow from Chat.

**What Chat deliberately does NOT show**:
- Trace metadata (latency, tokens)
- Span details
- Other users' sessions
- Test/scheduled runs (kept on dedicated pages)

### 4.2 Traces Page

**Purpose**: developer & operator view. Find + drill into invocations.

**Layout** (two columns, detail panel collapsible):
```
┌─── Filter bar ──────────────────────────────────────────────────┐
│ [All] [Agent] [LLM Call] [External]   ← top-level type tabs   │
│ Source [▾] Agent [▾] Model [▾] Status [▾] Time [▾] [+More]    │
├────────────────────────────┬──────────────────────────────────┤
│  List (60%)                │  Detail panel (40%, collapsible) │
│                            │                                   │
│  Virtualized rows          │  ┌─ Overview ──────────────┐     │
│                            │  │                         │     │
│  [← →] page nav            │  └─────────────────────────┘     │
│                            │                                   │
└────────────────────────────┴──────────────────────────────────┘
```

**Type tabs** (primary segmentation):
- **All** — everything
- **Agent** — `type=agent` only (Chat/Test/API/A2A/Scheduled)
- **LLM Call** — `type=llm_call` (Web test + API `/llm/:id/call`)
- **External** — orphan litellm spans, unknown origin

Tabs are more discoverable than a filter dropdown because most users care about "Agent traces I own" vs "raw LLM calls I made".

**Secondary filters** (below tabs):
- **Source** (cross-cutting within a type)
- **Agent** (dropdown, populated from observed agents)
- **Model** (dropdown, populated from observed models)
- **Status** (All / Running / Completed / Error)
- **Time range** (Last 15m / 1h / 24h / 7d / Custom)
- **+More**: session ID, user ID, trace ID (exact match)

Filters are URL-persisted — refreshing keeps the view; sharing a URL shares the filter.

**Row layout** (2-line dense, accessible at a glance):

```
Line 1: [source icon] operation.name  [agent pill]  [session chip]   status  model  tokens  latency  time
Line 2: input preview (80 chars, muted)
```

**Row states**:
- Default: subtle background
- Hover: highlighted, click target obvious
- Selected: strong border accent on left, background darkened
- Failed: red left border (priority signal — red is scarce, use only for errors)

**Empty / error states**:

| State | Message | CTA |
|---|---|---|
| No traces yet | "No traces in the last 24h. Agents produce traces when invoked." | [Switch to Last 7d] |
| Filter yields 0 | "No traces match these filters." | [Clear filters] |
| Backend error | "Couldn't load traces — try again?" | [Retry] |
| Loading | Skeleton rows (not spinner — keep layout) | — |

**Detail panel header**:

```
┌──────────────────────────────────────────────────┐
│ agent.turn  [🌐 Chat]                      [⨯]  │ ← close panel
│ 💬 Xander test 2     session: 5abb6410  →       │ ← → = open in Chat
│ trace_id: 8f2a4c...                              │
│ gpt-5.1 · 426 tokens · 1.2s · COMPLETED         │
├──────────────────────────────────────────────────┤
│ [Overview] [Spans] [Input] [Output] [Raw]       │
│                                                  │
│ (tab content)                                    │
└──────────────────────────────────────────────────┘
```

**Detail Tabs**:
- **Overview**: 3-5 cards (tokens breakdown, latency breakdown, span count, cost placeholder, status timeline)
- **Spans**: indented tree, each span showing name + type chip + duration bar (like Langfuse). Click a span → sub-panel swaps to show that span's input/output
- **Input / Output**: the trace's top-level I/O, with collapse for long content, copy button, JSON toggle
- **Raw**: full trace + spans JSON for power users

**Detail panel actions**:
- `Open in Chat` — session-bound traces
- `Open in Agent Detail` — source=test
- `Open in Scheduler` — source=scheduled
- `Copy trace ID` — always
- `View in Langfuse` — if external OTLP configured

### 4.3 Agent Detail — Test Runs Tab

**Purpose**: developer iterating on an agent.

**Layout**: within Agent Detail page, add a Test Runs tab alongside existing tabs:

```
┌── Agent: Xander test 2 ────────────────────────────┐
│ [Info] [Tools] [Skills] [Test Runs] [Traces]      │ ← tabs
├────────────────────────────────────────────────────┤
│ [+ New Test Run]                                   │
│                                                    │
│ ● Test: "Summarize this..."   5 min ago   gpt-5.1 │
│   2 turns · 234 tokens                             │
│                                                    │
│ ○ Test: "Translate to English..." 1h ago          │
│   1 turn · 89 tokens                               │
└────────────────────────────────────────────────────┘
```

Clicking a test run → conversation view inline (reuse Chat components, read-only for historical runs, active session is editable).

**Traces tab** on the same Agent page: pre-filtered Traces view for `agent_name = this agent`.

### 4.4 Scheduler — Runs Tab

**Purpose**: operator monitoring scheduled invocations.

Within the existing Scheduler page, selecting a schedule shows:

```
┌── Schedule: Daily News Summary ─────────┐
│ Cron: 0 9 * * *                          │
│ Agent: News Bot                          │
│ [Enabled toggle]                         │
│                                          │
│ Recent Runs (last 30):                   │
│ ─────────────────────────────────────── │
│ 2026-04-16 09:00 · ✅ COMPLETED · 1.2s │ → drill into session
│ 2026-04-15 09:00 · ✅ COMPLETED · 1.4s │
│ 2026-04-14 09:00 · ❌ ERROR · 2.1s    │
│ ...                                      │
└──────────────────────────────────────────┘
```

Each row is a session (source=scheduled, schedule_id=this). Click → read-only conversation view.

---

## 5. Interaction Patterns

### 5.1 Filters and URL state

All filters are URL-persisted using `useSearchParams`:

```
/?tab=agent&source=chat&agent=Xander%20test%202&status=error&time=24h
```

Benefits:
- Refresh preserves view
- Bookmark a specific filter state
- Share a link that opens with the same filters
- Back button works intuitively

### 5.2 Navigation between pages

Cross-linking is essential. Every row / message / span should offer pivot actions when relevant:

| From | Action | To |
|---|---|---|
| Chat message (agent) | `View trace` | Traces, filtered by this turn's trace_id |
| Trace row (session-bound) | `Open in Chat` | `/chat?sessionId=X` (auto-hydrate) |
| Trace row (test source) | `Open in Agent Detail` | Agent editor's Test Runs tab |
| Trace row (scheduled source) | `Open in Scheduler` | Schedule detail → this run |
| Agent detail | `View all traces` | `/traces?agent=X` |
| Session in Chat sidebar | `View trace history` | `/traces?session=X` |

### 5.3 Keyboard shortcuts (Traces page)

Target power users debugging many traces:

| Key | Action |
|---|---|
| `j` / `↓` | Next trace in list |
| `k` / `↑` | Previous trace |
| `Enter` | Open selected trace detail |
| `Esc` | Close detail panel |
| `/` | Focus filter search |
| `⌘K` | Command palette (future) |

Focus ring must be visible (default blue outline is fine).

### 5.4 Real-time vs polling

- **Chat**: real-time via SSE (already implemented)
- **Traces list**: NOT auto-refresh (disruptive while scanning rows). Show a banner `"3 new traces — refresh"` and let user decide
- **Running trace detail**: auto-update while status=RUNNING; stop polling when COMPLETED/ERROR

### 5.5 Empty and loading

**Loading**: skeleton rows match final layout height. Avoid layout shift.
**Empty (no data)**: instructive — explain how to generate data, with a CTA button if possible.
**Empty (filter)**: suggest relaxing filters, with a one-click "Clear filters" button.
**Errored**: retry button + error code visible for ops.

---

## 6. What We Consciously Defer

Not doing (yet), to keep scope tight:

- **Aggregate analytics** (latency over time, token trends): defer to Langfuse if user needs it
- **Cost calculation**: no model→pricing map yet; show tokens, let external tools compute cost
- **Trace comparison** (diff 2 traces side by side): nice-to-have, not core
- **Saved filter presets**: only matters after heavy usage
- **Virtualized list**: adopt when a single filter returns >500 rows routinely
- **Mobile layout**: Traces page is desktop-first; Chat already mobile-friendly
- **Command palette / global search**: after basics land
- **Trace replay**: re-run an old input. Valuable but complex (agent definition may have changed)

---

## 7. Visual Design Decisions

These are small but compound:

| Element | Choice | Why |
|---|---|---|
| **Status colors** | Green/red/yellow — but always paired with icon | Colorblind-safe |
| **Source icons** | Emoji at first (🌐 chat / ⚡ api / ⏰ scheduled / 🤝 a2a / 🧪 test / 🔗 a2a / 🕸 external) | Easy to scan; swap to lucide-react icons later |
| **Pills** | Small rounded chips, neutral bg unless semantically important | Don't compete with primary content |
| **Dense rows** | 2-line rows, default 48-56px height | Scan-friendly; Langfuse-like density |
| **Detail panel width** | 40% on wide screens, full-screen modal on narrow | Degrade gracefully |
| **Chinese + English content** | Font stack must handle both (use system font via Tailwind default) | Input previews often mixed |
| **Monospace for IDs / JSON** | Tailwind `font-mono` | Readability |

---

## 8. Performance Guardrails

When things get slow, here's where to invest:

1. **Backend**: every filter column must have a composite index (see `session-and-trace-design.md` §10)
2. **Frontend**: limit initial fetch to 20–50 rows; paginate / cursor
3. **Detail fetch**: span tree only loaded when Spans tab is clicked (lazy)
4. **Raw JSON**: only render in a code viewer when user clicks Raw tab (don't include in default payload)
5. **Trace list API payload**: truncate `input`/`output` preview server-side to 200 chars; full content loaded on detail open

---

## 9. Accessibility

Minimum bar:

- Keyboard navigable (j/k/enter/esc at least)
- Focus indicators visible
- ARIA labels on icon-only buttons (`aria-label="Delete session"`)
- Color-blind: status never encoded in color alone
- Contrast ratios meet WCAG AA on both light and dark themes
- Screen reader: proper heading hierarchy (h1 per page, h2 per section)

---

## 10. Design-Decision Checklist

When designing a new surface related to session/trace, answer these:

- [ ] Which persona is this for primarily?
- [ ] What's the L1 (always visible) info? What's L5 (only on deep click)?
- [ ] What's the pivot action to other pages?
- [ ] How does it handle an empty state? A filter-empty state? An error?
- [ ] Is it keyboard navigable?
- [ ] Does filter/state persist to URL?
- [ ] What happens if the trace is still RUNNING when opened?
- [ ] Does it degrade for external / orphan traces?

---

## 11. Summary Diagram

```
                         USER TASK FLOW
                                                  
  Conversation            Debug a turn          Monitor runs
        │                       │                     │
        ▼                       ▼                     ▼
   ┌────────┐            ┌──────────┐         ┌────────────┐
   │  Chat  │            │  Traces  │         │ Scheduler  │
   └───┬────┘            └─────┬────┘         └─────┬──────┘
       │                       │                     │
       │ View trace            │ Open in Chat        │ View session
       └───────────────────────┼─────────────────────┘
                               │
                               ▼
                      ┌─────────────────┐
                      │ Trace Detail    │
                      │ (span tree, IO) │
                      └─────────────────┘
                               ▲
                               │ View all traces
                         ┌──────────┐
                         │   Agent  │
                         │  Detail  │
                         └──────────┘
```

Every arrow is a pivot action that keeps users in flow instead of hitting dead ends.
