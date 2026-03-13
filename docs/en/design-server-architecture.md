# Core AI Server — Architecture & Design

## 1. Positioning

Core AI Server is the **runtime platform** for the Core AI framework. It turns the core-ai library from an embeddable SDK into a managed service where agents are defined, scheduled, executed, and observed.

```
core-ai (library)        — build agents in code
core-ai-server (platform) — run agents as a service
core-ai-cli (tool)        — interact with agents locally or remotely
```

## 2. Design Principles

### 2.1 Framework Agnosticism

The server never modifies the core-ai agent framework. All platform behavior is layered on top via existing extension points (`StreamingCallback`, `AbstractLifecycle`, `PersistenceProvider`, `AgentEventListener`). This means:

- A bug fix in the agent loop benefits all deployment modes automatically.
- The server can always run the latest core-ai version without integration patches.

### 2.2 Draft/Published Separation

Agent definitions have two states:

```
┌─────────────┐    publish()    ┌──────────────────┐
│  Draft      │ ──────────────> │  Published        │
│  (editable) │                 │  (immutable snap) │
└─────────────┘                 └──────────────────┘
```

- **Draft** is the working copy. Users edit system prompts, select tools, adjust parameters freely.
- **Published** is an immutable snapshot captured at publish time. All runs, schedules, and webhooks use this snapshot.
- This guarantees reproducibility — a running agent never sees mid-edit config changes.

### 2.3 Async-First Execution

Agent runs are inherently long-running (seconds to minutes). The server never blocks HTTP requests on agent execution:

- `POST /api/runs/agent/:id/trigger` returns `202 Accepted` with a `runId` immediately.
- Execution happens on a background thread pool (fixed size: 10).
- Clients poll `GET /api/runs/:id` for results, or listen via SSE for real-time sessions.

### 2.4 Transport-Agnostic Sessions

Interactive sessions use the `AgentSession` interface. The same `InProcessAgentSession` runs behind both:

- **CLI local mode** — events go directly to the terminal renderer.
- **Server mode** — events are bridged to SSE via `SseEventBridge`.

This means the agent-side code is identical regardless of client type.

### 2.5 Zero-Trust Tool Approval

Two complementary models coexist:

| Mode | Approval | Use Case |
|------|----------|----------|
| Interactive sessions | User approves each tool call (or sets auto-approve) | Debugging, exploration |
| Scheduled/triggered runs | All tools auto-approved | Automation, agents run autonomously |

For scheduled runs, tools are pre-vetted by platform admins when registering them in the tool registry.

### 2.6 Standard Protocol for Observability

Tracing uses the **OpenTelemetry** standard — the same protocol used by Langfuse, Jaeger, Grafana Tempo, etc. The server includes a built-in OTLP receiver so it can be its own observability backend:

```
core-ai agent execution
  → OpenTelemetry SDK (already in core-ai)
    → OtlpHttpSpanExporter
      → core-ai-server /v1/traces (protobuf)
```

No custom exporter, no proprietary protocol. If the user prefers Langfuse or Jaeger, they point the OTLP endpoint there instead — zero code change.

## 3. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                           HTTP Layer                                  │
│                                                                       │
│  AuthInterceptor ─── API Key (Bearer coreai_xxx)                     │
│       │               Azure AD (X-Auth-Request-Email)                │
│       ▼                                                               │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │                    WebService Layer                               │ │
│  │  Sessions │ Agents │ Runs │ Schedules │ Tools │ Auth │ Files    │ │
│  └─────────────┬───────────────────────────────────────────────────┘ │
│                │                                                      │
│  ┌─────────────▼───────────────────────────────────────────────────┐ │
│  │                    Service Layer                                  │ │
│  │                                                                   │ │
│  │  AgentDefinitionService     Define agents (CRUD + publish)       │ │
│  │  AgentRunner                Execute agents (async thread pool)   │ │
│  │  AgentSessionManager        Interactive sessions (SSE)           │ │
│  │  AgentScheduler             Cron-based triggers                  │ │
│  │  ToolRegistryService        Builtin + MCP tool management        │ │
│  │  AuthService                Registration, login, API keys        │ │
│  │  OTLPIngestService          Trace ingestion                      │ │
│  │  PromptService              Prompt template versioning           │ │
│  └─────────────┬───────────────────────────────────────────────────┘ │
│                │                                                      │
│  ┌─────────────▼───────────────────────────────────────────────────┐ │
│  │                    Data Layer (MongoDB)                           │ │
│  │                                                                   │ │
│  │  users │ agents │ agent_runs │ agent_schedules │ tool_registries │ │
│  │  files │ traces │ spans │ prompt_templates │ schema_versions     │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  SSE Push: InProcessAgentSession → SseEventBridge → Channel    │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────────────┘
```

## 4. Domain Model

### 4.1 Entity Relationships

```
User ──1:N──> AgentDefinition ──1:N──> AgentRun
                    │                      │
                    ├──1:N──> AgentSchedule │
                    │                      │
                    └── N:M ──> ToolRegistry
                                           │
Trace (via OTLP) ──1:N──> Span            │
                                           │
PromptTemplate (independent versioning)    │
```

### 4.2 Core Entities

**User** — account with API key authentication

| Field | Description |
|-------|-------------|
| id | Normalized email (lowercase) |
| api_key | `coreai_` + Base64(32 random bytes) |
| role | `admin` or `user` |
| status | `active` or `pending` (requires admin approval) |

**AgentDefinition** — declarative agent configuration

| Field | Description |
|-------|-------------|
| system_prompt | Agent instructions |
| model | LLM model identifier |
| tool_ids | References to ToolRegistry entries |
| input_template | `{{variable}}` template for run input |
| type | `AGENT` (multi-turn with tools) or `LLM_CALL` (single call) |
| published_config | Immutable snapshot used by runs/schedules |
| webhook_secret | `whk_` + UUID, for webhook authentication |

**AgentRun** — single execution record

| Field | Description |
|-------|-------------|
| status | PENDING → RUNNING → COMPLETED / FAILED / TIMEOUT / CANCELLED |
| triggered_by | MANUAL, SCHEDULE, API, WEBHOOK |
| transcript | Full message history (user, assistant, tool_call, tool_result) |
| token_usage | {input: N, output: M} |

**AgentSchedule** — cron trigger

| Field | Description |
|-------|-------------|
| cron_expression | 5-field: `minute hour dom month dow` |
| concurrency_policy | SKIP (default) or PARALLEL |
| next_run_at | Pre-calculated, used for atomic distributed locking |

**ToolRegistry** — available tools

| Type | Source |
|------|--------|
| BUILTIN | File ops, web, code execution, multimodal |
| MCP | Model Context Protocol servers (STDIO/HTTP/SSE transport) |
| API | External REST APIs (future) |

### 4.3 Trace Entities

**Trace** — one complete agent execution chain

| Field | Description |
|-------|-------------|
| trace_id | OpenTelemetry trace ID |
| session_id, user_id | Links to server entities (from span attributes) |
| total_tokens | Aggregated token count |
| duration_ms | Wall clock time |

**Span** — individual operation within a trace, tree structure via parent_span_id

| Type | Description |
|------|-------------|
| LLM | Single LLM API call (model, tokens, duration) |
| AGENT | Agent execution scope |
| TOOL | Tool/function call |
| FLOW | Flow node execution |
| GROUP | Multi-agent group coordination |

**PromptTemplate** — versioned prompt management

| Field | Description |
|-------|-------------|
| template | Text with `{{variable}}` placeholders |
| version | Auto-incremented on each update |
| published_version | The version available for runtime use |
| status | DRAFT → PUBLISHED → ARCHIVED |

## 5. Execution Flows

### 5.1 Scheduled Run

```
AgentSchedulerJob (every 1 min)
    │
    │  MongoDB atomic update: claim schedule where next_run_at <= now
    │  (distributed lock — only one replica wins)
    │
    ▼
Check concurrency policy
    │  SKIP → abort if agent has RUNNING run
    │  PARALLEL → proceed regardless
    │
    ▼
AgentRunner.run(definition.publishedConfig, input, SCHEDULE)
    │
    │  1. Create AgentRun record (status=RUNNING)
    │  2. Submit to thread pool (return immediately)
    │  3. Build Agent from published config
    │     - Resolve tools from ToolRegistry
    │     - Configure LLM provider, model, system prompt
    │  4. agent.execute(input) — multi-turn loop
    │  5. Capture output, transcript, token usage
    │  6. Update AgentRun (COMPLETED / FAILED / TIMEOUT)
    │
    ▼
Update schedule.next_run_at for next trigger
```

### 5.2 Webhook Trigger

```
POST /api/webhooks/:agentId
    │
    │  Authorization: Bearer whk_<secret>
    │
    ▼
Validate: agent exists, published, webhook enabled, secret matches
    │
    ▼
Process input template:
    │  {{payload}} → full request body
    │  {{field}}   → extract from JSON body
    │
    ▼
AgentRunner.run(publishedConfig, processedInput, WEBHOOK)
    │
    ▼
Return { runId, status: "RUNNING" }
```

### 5.3 Interactive Session (SSE)

```
Client                          Server                          Agent Thread
  │                               │                                  │
  │ POST /api/sessions            │                                  │
  │ ───────────────────────────>  │                                  │
  │ { sessionId }                 │  create InProcessAgentSession    │
  │ <───────────────────────────  │                                  │
  │                               │                                  │
  │ PUT /api/sessions/events      │                                  │
  │   ?sessionId=xxx              │                                  │
  │ ─── SSE connection ────────>  │  register SseEventBridge         │
  │                               │                                  │
  │ POST /messages { "hello" }    │                                  │
  │ ───────────────────────────>  │  session.sendMessage()           │
  │                               │ ─────────────────────────────>   │
  │                               │                                  │ agent.run()
  │ SSE: text_chunk               │ <── event dispatch ────────────  │ LLM streaming
  │ <───────────────────────────  │                                  │
  │ SSE: tool_approval_request    │ <── PermissionGate.wait() ─────  │ needs approval
  │ <───────────────────────────  │                                  │ [BLOCKED]
  │                               │                                  │
  │ POST /approve { APPROVE }     │                                  │
  │ ───────────────────────────>  │  PermissionGate.respond()        │
  │                               │ ─────────────────────────────>   │ unblocked
  │ SSE: tool_result              │ <── event dispatch ────────────  │ tool executed
  │ SSE: turn_complete            │ <── event dispatch ────────────  │ done
  │ <───────────────────────────  │                                  │
```

### 5.4 OTLP Trace Ingestion

```
core-ai Agent execution
    │
    │  AgentTracer / LLMTracer / FlowTracer
    │  (OpenTelemetry spans with GenAI semantic conventions)
    │
    ▼
OtlpHttpSpanExporter (batched, protobuf)
    │
    │  POST /v1/traces (or /api/public/otel/v1/traces)
    │
    ▼
OTLPController
    │  ExportTraceServiceRequest.parseFrom(body)
    │
    ▼
OTLPIngestService
    │  For each ResourceSpans → ScopeSpans → Span:
    │    - Extract traceId, spanId, parentSpanId (hex)
    │    - Extract attributes (gen_ai.*, langfuse.*, session.*, user.*)
    │    - Resolve span type from langfuse.observation.type
    │    - Root span (no parent) → upsert Trace record
    │    - All spans → insert Span record
    │
    ▼
MongoDB: traces + spans collections
```

## 6. Authentication Model

```
                    ┌──────────────────┐
                    │  AuthInterceptor │
                    └────────┬─────────┘
                             │
              ┌──────────────┴──────────────┐
              ▼                              ▼
    Azure AD (Enterprise)           API Key (Programmatic)
    X-Auth-Request-Email            Authorization: Bearer coreai_xxx
    Auto-create user                Validate key in MongoDB
    on first login                  Check user.status == active
              │                              │
              └──────────────┬───────────────┘
                             ▼
                    userId → request context
```

**Public routes** (no auth): `/api/auth/register`, `/api/auth/login`, `/api/webhooks/*`

**Webhook auth**: Separate from user auth. Uses agent-specific `whk_` secret.

**User lifecycle**: Register (pending) → Admin approves (active) → Generate API key

## 7. Distributed Scheduling

The scheduler runs in every server replica. To prevent duplicate triggers:

```
AgentScheduler.evaluate()
    │
    ▼
MongoDB atomic update:
    filter:  { _id: scheduleId, next_run_at: { $lte: now } }
    update:  { $set: { next_run_at: <next cron time> } }
    │
    │  Only one replica succeeds (compare-and-set)
    │  Losers get updateCount == 0, skip silently
    │
    ▼
Winner proceeds to trigger AgentRunner
```

This pattern requires no external coordination (no ZooKeeper, no Redis lock). MongoDB's atomic `findOneAndUpdate` provides the distributed mutex.

## 8. Tool Resolution

```
AgentDefinition.toolIds = ["builtin-file-ops", "jira-mcp", "slack-mcp"]
                                    │
                                    ▼
                          ToolRegistryService.resolveTools()
                                    │
                    ┌───────────────┼───────────────┐
                    ▼               ▼               ▼
               BUILTIN            MCP             API
           BuiltinTools.      McpToolCalls.    (future)
           FILE_OPERATIONS    of(manager, id)
                    │               │
                    └───────┬───────┘
                            ▼
                    List<ToolCall> → Agent.builder().toolCalls()
```

MCP servers are registered by admins, loaded on startup, and shared across all agent runs via `McpClientManager`.

## 9. SSE Event Types

| Event | Direction | Payload |
|-------|-----------|---------|
| `text_chunk` | Server → Client | `{ chunk }` — streaming LLM output |
| `reasoning_chunk` | Server → Client | `{ chunk }` — thinking/reasoning content |
| `reasoning_complete` | Server → Client | `{ reasoning }` — full reasoning text |
| `tool_start` | Server → Client | `{ callId, toolName, arguments }` |
| `tool_result` | Server → Client | `{ callId, toolName, status, result }` |
| `tool_approval_request` | Server → Client | `{ callId, toolName, arguments }` |
| `turn_complete` | Server → Client | `{ output, inputTokens, outputTokens }` |
| `error` | Server → Client | `{ message, detail }` |
| `status_change` | Server → Client | `{ status }` — IDLE / RUNNING / ERROR |

## 10. Configuration Reference

### sys.properties

| Property | Default | Description |
|----------|---------|-------------|
| `sys.http.port` | 8080 | HTTP server port |
| `sys.mongo.uri` | (required) | MongoDB connection string |
| `sys.admin.email` | admin@example.com | Initial admin email |
| `sys.admin.password` | admin | Initial admin password |
| `sys.admin.name` | Admin | Initial admin display name |
| `sys.file.storagePath` | ./data/files | File upload storage directory |

### agent.properties

| Property | Default | Description |
|----------|---------|-------------|
| `litellm.api.base` | — | LiteLLM proxy URL |
| `litellm.api.key` | — | LiteLLM API key |
| `openai.api.base` | — | OpenAI API base URL |
| `openai.api.key` | — | OpenAI API key |
| `trace.otlp.endpoint` | — | OTLP export endpoint (set to self for built-in tracing) |
| `trace.service.name` | core-ai | Service name in traces |
| `trace.service.version` | 1.0.0 | Service version in traces |
| `trace.environment` | production | Deployment environment |
| `trace.otlp.public.key` | — | Langfuse public key (for Basic Auth) |
| `trace.otlp.secret.key` | — | Langfuse secret key (for Basic Auth) |

### Runtime Limits

| Parameter | Value | Location |
|-----------|-------|----------|
| Max concurrent runs | 10 | AgentRunner thread pool |
| Default run timeout | 600s (10 min) | AgentRunner |
| Transcript truncation | 10,240 chars per tool result | AgentRunner |
| Scheduler poll interval | 1 minute | AgentSchedulerJob |
| OTLP batch size | 512 spans | TelemetryConfig |
| OTLP batch delay | 1 second | TelemetryConfig |

## 11. Database Collections

| Collection | Description | Key Indexes |
|------------|-------------|-------------|
| `users` | User accounts | `api_key` (unique, sparse) |
| `agents` | Agent definitions | `user_id` |
| `agent_runs` | Execution records | `agent_id`, `status`, `started_at` |
| `agent_schedules` | Cron schedules | `agent_id`, `next_run_at` |
| `tool_registries` | Tool/MCP registry | — |
| `files` | File metadata | `user_id` |
| `schema_versions` | Migration tracking | — |
| `traces` | Trace records | `trace_id` (unique), `session_id`, `created_at` |
| `spans` | Span records | `trace_id`, `span_id` (unique), `parent_span_id` |
| `prompt_templates` | Prompt versions | `name`, `status`, `created_at` |

## 12. Deployment

### Single Instance

```bash
# Configure
cat > sys.properties << 'EOF'
sys.mongo.uri=mongodb://localhost:27017/core-ai
EOF

cat > agent.properties << 'EOF'
openai.api.base=https://api.openai.com/v1
openai.api.key=sk-xxx
trace.otlp.endpoint=http://localhost:8080
EOF

# Run
./gradlew :core-ai-server:run
```

### Kubernetes (Multi-Replica)

- Replicas share MongoDB — scheduling uses atomic locking, no duplicate triggers.
- Sessions are in-memory — clients must reconnect to the same replica (sticky session) or use a single replica for interactive sessions.
- Stateless API requests (runs, agents, tools) work across any replica.

### Connecting core-ai to server tracing

```properties
# In core-ai's agent.properties
trace.otlp.endpoint=http://core-ai-server:8080

# If using Langfuse path (default):
# → sends to http://core-ai-server:8080/api/public/otel/v1/traces

# If useLangfusePath=false:
# → sends to http://core-ai-server:8080/v1/traces
```

No code changes to core-ai required.
