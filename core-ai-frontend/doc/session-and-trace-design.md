# Session & Trace Design

> This document defines the relationship between **Agent invocations, Sessions, and Traces** in the core-ai system. It serves as the alignment reference for backend data model and frontend UX.

---

## 1. System Positioning

```
          core-ai-server (Hub)
           ├── Agent/Tool/Skill/Prompt definitions
           ├── Session/Message/Trace data
           └── Multiple API entry points
                  ↑
   ┌──────┬──────┼──────┬──────┐
   │      │      │      │      │
 Web UI  CLI   API   A2A    Scheduled
 (Spoke) (Spoke)
```

- **core-ai-server**: backend platform / agent runtime, owner of all domain data
- **core-ai-frontend (Web)**: one of several clients, owns no data, UI only
- **core-ai-cli**: another client. Can run agents locally (no server involvement) or connect to server via A2A (unified path)
- **Developer API / A2A / Scheduled**: other trigger entry points

**Core principle: whoever executes the agent is responsible for recording. Only agents executed on core-ai-server produce session/message/trace.**

---

## 2. Two Kinds of Executables

| Type | Conversational context | Record I/O | Session | Trace |
|---|---|---|---|---|
| **Agent** | ✅ | ✅ | ✅ | ✅ |
| **LLM Call** | ❌ | — | ❌ | ✅ |

**Agent is a "conversation unit"; LLM Call is an "invocation unit".**

---

## 3. Five Agent Input Sources

| # | Entry | Session | Messages | Trace |
|---|---|---|---|---|
| 1 | Web chat page | ✅ long-lived, resumable | ✅ | ✅ |
| 2 | Web agent test run | ✅ recorded but hidden from chat sidebar | ✅ | ✅ |
| 3 | API call | ✅ client-managed | ✅ | ✅ |
| 4 | A2A | ✅ | ✅ | ✅ |
| 5 | Scheduled task | ✅ one session per run | ✅ | ✅ |

**All 5 sources produce session + messages + trace**, differentiated by the `source` field.

---

## 4. Two LLM Call Input Sources

| # | Entry | Session | Trace |
|---|---|---|---|
| 1 | Web LLM call test | ❌ | ✅ |
| 2 | API LLM call (`/api/llm/:id/call`) | ❌ | ✅ |

Trace only, no session/messages — there is no conversation concept.

---

## 5. Core Concept Relationships

```
Session      ──1:N──▶  Turn  ──1:1──▶  Trace
(container)           (bridge)         (execution record)
user view                               system view
```

- **Session** = a continuous agent invocation lifecycle (multi-turn or single-turn)
- **Turn** = one exchange: 1 user message + 1 agent response; inner LLM calls / tool calls belong to this turn
- **Trace** = the system-side execution of that turn (contains N spans: llm.call / tool.call / reasoning / ...)

### Invariants

| Relationship | Must hold? |
|---|---|
| Session has ≥1 Trace | ✅ session is created on first user message |
| Trace has a Session | ❌ LLM Calls and external litellm traces have no session |
| One Turn maps to one Trace | ✅ semantically required; currently broken into 2 traces due to missing traceparent propagation (see §9) |

---

## 6. Data Model

### 6.1 `chat_sessions`

> Name retained; semantics is actually "**any agent invocation session**", not only chat.

```java
@Id                              public String id;            // = sessionId
@Field("user_id")                public String userId;
@Field("agent_id")               public String agentId;
@Field("title")                  public String title;         // first 40 chars
@Field("message_count")          public Long messageCount;
@Field("created_at")             public ZonedDateTime createdAt;
@Field("last_message_at")        public ZonedDateTime lastMessageAt;
@Field("deleted_at")             public ZonedDateTime deletedAt;      // soft delete

// Planned fields
@Field("source")                 public String source;        // chat | test | api | a2a | scheduled
@Field("schedule_id")            public String scheduleId;    // only when source=scheduled
@Field("api_key_id")             public String apiKeyId;      // only when source=api
```

### 6.2 `chat_messages`

```java
@Id                              public String id;
@Field("session_id")             public String sessionId;
@Field("seq")                    public Long seq;              // monotonic
@Field("role")                   public String role;           // user | agent
@Field("content")                public String content;
@Field("thinking")               public String thinking;
@Field("tools")                  public List<ToolCallRecord> tools;
@Field("trace_id")               public String traceId;        // currently ActionLogContext.id; migrate to OTEL traceId
@Field("created_at")             public ZonedDateTime createdAt;
```

**Note**: for scheduled sessions, the first user message's content stores the **rendered prompt** (for auditability); role stays `user`.

### 6.3 `traces`

```java
@Id                              public String id;
@Field("trace_id")               public String traceId;
@Field("name")                   public String name;           // Target: agent.turn | llm.call. Current: CoreAI / litellm
@Field("agent_name")             public String agentName;
@Field("model")                  public String model;
@Field("session_id")             public String sessionId;      // extracted from root span attribute
@Field("user_id")                public String userId;
@Field("status")                 public TraceStatus status;
@Field("input")                  public String input;
@Field("output")                 public String output;
@Field("metadata")               public Map<String, String> metadata;
@Field("input_tokens")           public Long inputTokens;
@Field("output_tokens")          public Long outputTokens;
@Field("total_tokens")           public Long totalTokens;
@Field("duration_ms")            public Long durationMs;
@Field("started_at")             public ZonedDateTime startedAt;
@Field("completed_at")           public ZonedDateTime completedAt;

// Planned fields
@Field("type")                   public String type;           // agent | llm_call | external
@Field("source")                 public String source;         // chat | test | api | a2a | scheduled | llm_test | llm_api | external
@Field("schedule_id")            public String scheduleId;
@Field("api_key_id")             public String apiKeyId;
```

### 6.4 `spans`

Standard OTEL span structure, unchanged.

---

## 7. Entity Relationship Diagram

```
                        ┌─ (Executable) ─┐
                        │                 │
                 AgentDefinition        LLMCall
                        │                 │
              ┌─────────┴──────────┐     │
              │ 5 entry points     │     │ 2 entry points
              │ chat/test/api/     │     │ test/api
              │ a2a/scheduled      │     │
              ▼                    ▼     ▼
         ┌─────────────┐          ┌────────────┐
         │ ChatSession │          │ (no session)│
         │ source=...  │          └────────────┘
         └──────┬──────┘                │
                │ 1:N                   │
                ▼                       ▼
         ┌─────────────┐          ┌──────────┐
         │ ChatMessage │          │          │
         └──────┬──────┘          │          │
                │ 1:1 per turn    │ 1:1     │
                ▼                 ▼          ▼
         ┌─────────────────────────────────┐
         │          Trace                  │
         │   type=agent | llm_call         │
         │   source=... (8 values)         │
         └─────────┬───────────────────────┘
                   │ 1:N
                   ▼
         ┌─────────────────────────────────┐
         │   Span (llm.call/tool.call/...) │
         └─────────────────────────────────┘
```

---

## 8. UI Routing (where each source shows up in Web)

| UI location | Data source | Filter |
|---|---|---|
| **Chat page sidebar** | `chat_sessions` | `user_id = self AND source IN (chat, a2a) AND deleted_at IS NULL` |
| **Agent detail → Test Runs tab** | `chat_sessions` | `agent_id = X AND source = test` |
| **Scheduler → Runs tab** | `chat_sessions` | `schedule_id = X` |
| **Traces page** | `traces` | multi-dim filter by type + source |
| **API Sessions** (future, if needed) | `chat_sessions` | `source = api AND api_key_id = X` |

**Key**: Chat sidebar only shows chat-like sources (chat + a2a); test/scheduled are routed elsewhere.

---

## 9. Traces Page Design

### 9.1 Page Layout (two columns)

```
┌─── Filter bar ───────────────────────────────────────────────┐
│ Tab: [All | Agent | LLM Call | External]                    │
│ Source [▾] · Agent [▾] · Model [▾] · Status [▾] · Time [▾]  │
├─────────────────────────┬────────────────────────────────────┤
│  Trace List (60%)       │  Trace Detail Panel (40%)         │
│                         │                                    │
│  rows                   │  Header: name · agent · session   │
│  pagination             │  Tabs: Overview/Spans/IO/Raw      │
└─────────────────────────┴────────────────────────────────────┘
```

### 9.2 Row anatomy

Agent trace:
```
[🌐 Chat] agent.turn   💬 Xander test 2   session: 8f2..  ─ COMPLETED
  "Hello, please summarize..."                           gpt-5.1
                                             426 tok · 1.2s · 3m ago
```

LLM Call trace:
```
[⚡ API] llm.call       gpt-5.1                           ─ COMPLETED
  "messages: [...]"                                    (no agent/session)
                                             230 tok · 0.8s · 5m ago
```

### 9.3 Columns

| Column | Content | Agent trace | LLM Call trace |
|---|---|---|---|
| Source icon+badge | chat/test/api/a2a/scheduled/llm_test/llm_api/external | ✅ | ✅ |
| Name + input preview | operation name + first user message (≤80 chars) | ✅ | ✅ |
| Agent pill | agent_name | ✅ | — (empty) |
| Session chip | abbreviated session_id, click to jump to Chat | ✅ | — (empty) |
| Model pill | gpt-5.1 / openrouter/... | ✅ | ✅ |
| Status badge | RUNNING/COMPLETED/ERROR | ✅ | ✅ |
| Tokens | in/out | ✅ | ✅ |
| Duration | ms/s | ✅ | ✅ |
| Time | relative | ✅ | ✅ |

### 9.4 Detail Panel

**Header**:
```
agent.turn  [🌐 Chat]  [💬 Xander test 2]     → Open in Chat
trace_id: 8f2a...  · session: 5abb6410
gpt-5.1 · 426 tokens · 1.2s · COMPLETED
```

**Tabs**:
| Tab | Content |
|---|---|
| Overview | token breakdown / cost / span count / latency histogram |
| Spans | hierarchical tree of all spans, color-coded by type (llm/tool/reasoning) |
| Input | root span input |
| Output | root span output |
| Raw | complete trace + spans JSON (debugging) |

**Actions**:
- **Open in Chat** — when session-bound, jumps to `/chat?sessionId=xxx`
- **Open in Agent Detail** — when source=test
- **Open in Scheduler** — when source=scheduled
- Copy trace ID
- View in Langfuse (if external OTLP configured)

---

## 10. Index Plan

### chat_sessions
```
{user_id:1, source:1, last_message_at:-1}      // Chat sidebar primary path
{agent_id:1, source:1, last_message_at:-1}     // Agent Test Runs tab
{schedule_id:1, last_message_at:-1}            // Scheduler Runs tab
```

### traces
```
{type:1, created_at:-1}                        // Traces page main tab
{type:1, source:1, created_at:-1}              // tab + source filter
{session_id:1, started_at:1}                   // existing, session view
{agent_name:1, created_at:-1}                  // agent filter
{model:1, created_at:-1}                       // model filter
```

---

## 11. Current State & Gaps

### Completed
- ✅ `chat_sessions` + `chat_messages` collections with Web chat pipeline
- ✅ Chat sidebar (Claude-style, session-first)
- ✅ Soft-delete session (messages retained for audit)
- ✅ Conversation persistence + LLM context restore on session rebuild (`Agent.restoreHistory`)
- ✅ Trace `model` field + one-off backfill
- ✅ Trace `agent_name` field + ingest-time population
- ✅ Legacy Sessions menu removed
- ✅ Delete session button in sidebar

### Pending

#### Phase 1 — Data model categorization
- [ ] `chat_sessions.source` field
- [ ] `traces.type` / `traces.source` fields
- [ ] `chat_sessions.schedule_id` / `api_key_id` (placeholders for future entries)
- [ ] Web chat entry writes `source=chat`
- [ ] Index updates

#### Phase 2 — Trace pipeline fix (key blocker)
- [ ] **traceparent injection into LLM HTTP client** — one turn → one traceId; litellm becomes a child span
- [ ] `AgentTracer` span.name → `agent.turn` (currently `CoreAI`)
- [ ] `LLMTracer` span.name → `llm.call` (currently `litellm`)

#### Phase 3 — Traces page redesign
- [ ] Top-level tabs (All / Agent / LLM Call / External)
- [ ] Filter bar (source / agent / model / status / time / session_id)
- [ ] Detail Panel (slide-out right column)
- [ ] Span tree view (Spans tab)
- [ ] Open in Chat / Test Run / Scheduler jumps

#### Phase 4 — Wiring other Agent entry points
- [ ] Web agent test run → `source=test`
- [ ] Scheduled agent run → `source=scheduled + schedule_id`
- [ ] API session endpoint → `source=api + api_key_id`
- [ ] A2A → `source=a2a`

---

## 12. Resolved Design Decisions (confirmed with user)

| Decision | Choice |
|---|---|
| Session semantics | "agent invocation" rather than "chat" (name retained) |
| Scheduled user_id | use the schedule creator |
| Test run session location | Agent detail Test tab, **not** in Chat sidebar |
| Scheduled user message content | store rendered prompt, role=user |
| `chat_sessions` naming | retain, differentiate via `source` |
| Message deletion | **no physical delete**; only soft-delete sessions; chat_messages preserved for audit |
| LLM context restore | on session rebuild, reload Agent.messages from DB; do not resume in-flight turn |
| Orphan user message (server crash mid-turn) | frontend tolerates; no auto-resume |

---

## 13. Open Questions / Long-term Considerations

1. **Cost attribution**: owner of cost per source (user / tenant / service account); trace may need `user_id` + `tenant_id`
2. **Data retention**: different TTL per source (chat long-term / scheduled 30 days / test cascaded on agent delete)
3. **Access control**: currently only `user_id`; future admin / agent owner views
4. **chat_messages.trace_id**: currently `ActionLogContext.id`, migrate to OTEL traceId (after traceparent propagation lands)
5. **Langfuse integration**: configure external OTLP endpoint so spans also ship to Langfuse; `session.id` / `user.id` are standard attributes, natively supported
6. **A2A user_id semantics**: originating user of calling agent? service identity? propagation mechanism TBD

---

## 14. References

- **Langfuse**: 3-column tracing view (List + Span Tree + Detail), separate Sessions page grouped by session_id
- **LangSmith**: project-level scope, flat trace list, detail with span tree
- **Phoenix (Arize)**: span-level aggregation for evaluation workflows
- **OpenTelemetry Gen AI semconv**: `gen_ai.*` attribute spec, partially adopted in core-ai (`gen_ai.agent.name` / `gen_ai.agent.id` / `gen_ai.prompt` / `gen_ai.completion`)
