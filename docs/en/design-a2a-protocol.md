# A2A Protocol Support Design

## 1. Background

### What is A2A

A2A (Agent-to-Agent) is an open protocol by Google for agent interoperability. It evolved from IBM's ACP (Agent Communication Protocol) which merged into A2A under the Linux Foundation in September 2025.

A2A defines how agents discover each other, exchange messages, delegate tasks, and stream results — regardless of framework or language.

### Why A2A for core-ai

core-ai currently supports two interaction modes:
- **TUI** (core-ai-cli) — terminal-based, local or remote
- **Server API** (core-ai-server) — proprietary REST+SSE, requires MongoDB

Adding A2A support enables:
- **Web UI** without deploying core-ai-server/MongoDB — `core-ai --serve` starts an embedded HTTP server
- **Third-party A2A clients** can use core-ai as an agent runtime
- **Agent-to-agent** — other A2A agents can delegate tasks to core-ai
- **Standard ecosystem** — compatible with Google, IBM, and other A2A implementations

### Why not A2A Java SDK

The official A2A Java SDK (`a2a-java`) requires Quarkus + Vert.x + gRPC + Protobuf + Jakarta CDI, which conflicts with core-ai's stack (core-ng + Jackson + Undertow). It would increase the CLI binary from ~30MB to 100MB+, slow startup, and risk GraalVM native-image compatibility.

Instead, core-ai implements A2A wire compatibility directly — same endpoints, same JSON schema, same SSE streaming — using the existing Undertow embedded server. This keeps the CLI lightweight while being fully interoperable with A2A clients.

## 2. Architecture

### Module Layout

```
core-ai-api/               A2A data models (ai.core.api.a2a)
core-ai/                   A2A runtime: RunManager, EventAdapter (ai.core.a2a)
core-ai-cli/               Embedded Undertow server + handlers (ai.core.cli.a2a)
core-ai-server/            core-ng web handlers (future, reuses core-ai models+runtime)
core-ai-frontend/          Shared web UI (React + Tailwind)
```

### Request Flow

```
                     core-ai --serve
┌─────────────────────────────────────────────┐
│  Undertow (localhost:3824)                  │
│                                             │
│  /.well-known/agent-card.json  → AgentCard  │  ← discovery
│  POST /message/send            → stream/sync│  ← A2A
│  GET  /tasks/{id}              → task state │
│  POST /tasks/{id}/cancel       → cancel     │
│  GET  /api/capabilities        → feature    │  ← internal (frontend)
│  GET  /*                       → web UI     │  ← bundled frontend
│                                             │
│  A2ARunManager                              │
│    └── InProcessAgentSession                │  ← reuse existing engine
│         └── Agent.run()                     │
└─────────────────────────────────────────────┘
         ↑                    ↑
    Browser (default)    A2A clients
```

### core-ai-server Integration (future)

```
core-ai-server also exposes A2A endpoints, reusing:
  - ai.core.api.a2a.*         (same data models)
  - ai.core.a2a.A2ARunManager (same runtime, different Supplier<Agent>)

Only the HTTP handler layer differs:
  - CLI: Undertow HttpHandler
  - Server: core-ng WebService
```

## 3. A2A Protocol Mapping

### Endpoints

| A2A Spec                         | core-ai Implementation             |
|----------------------------------|------------------------------------|
| `GET /.well-known/agent-card.json` | Agent discovery (name, skills, capabilities) |
| `POST /message/send`            | Create task from user message, sync response |
| `POST /message/send` (SSE)      | Create task, stream events via SSE |
| `GET /tasks/{taskId}`           | Get task state and output          |
| `POST /tasks/{taskId}/cancel`   | Cancel running task                |
| `POST /tasks/{taskId}/message/send` | Resume interrupted task (tool approval) |

### Agent Card

Served at `GET /.well-known/agent-card.json` (unauthenticated):

```json
{
  "name": "core-ai",
  "description": "AI coding assistant with file operations and shell access",
  "version": "1.0.0",
  "capabilities": {
    "streaming": true,
    "pushNotifications": false,
    "extensions": []
  },
  "skills": [
    { "name": "code-generation", "description": "Generate and modify code" },
    { "name": "file-operations", "description": "Read, write, search files" },
    { "name": "shell-execution", "description": "Execute shell commands" }
  ],
  "supportedInterfaces": ["http/rest"],
  "defaultInputModes": ["text/plain"],
  "defaultOutputModes": ["text/plain"]
}
```

### Task State Machine

```
A2A States              ←→  core-ai Internal
───────────────────────────────────────────
SUBMITTED               ←   session created
WORKING                 ←   agent.run() executing
INPUT_REQUIRED          ←   ToolApprovalRequestEvent (tool needs approval)
COMPLETED               ←   TurnCompleteEvent (success)
CANCELED                ←   TurnCompleteEvent (cancelled=true)
FAILED                  ←   ErrorEvent
```

Not used initially: `AUTH_REQUIRED`, `REJECTED` (may add later for multi-user scenarios).

### Message Format

A2A messages use `role` + `parts[]`:

```json
{
  "role": "user",
  "parts": [
    { "type": "text", "text": "Fix the bug in auth.js" }
  ]
}
```

Agent responses:

```json
{
  "role": "agent",
  "parts": [
    { "type": "text", "text": "I've fixed the authentication bug..." }
  ]
}
```

### SSE Streaming Events

When `Accept: text/event-stream`, the server streams `TaskStatusUpdateEvent` and `TaskArtifactUpdateEvent`:

```
data: {"type":"status","taskId":"...","status":{"state":"working"}}

data: {"type":"status","taskId":"...","status":{"state":"working","message":{"role":"agent","parts":[{"type":"text","text":"chunk"}]}}}

data: {"type":"status","taskId":"...","status":{"state":"input-required","message":{"role":"agent","parts":[{"type":"text","text":"Tool bash_run requires approval"}]}}}

data: {"type":"artifact","taskId":"...","artifact":{"parts":[{"type":"text","text":"final output"}]}}

data: {"type":"status","taskId":"...","status":{"state":"completed"}}
```

### Tool Approval as INPUT_REQUIRED

When an agent tool needs user approval, the task enters `INPUT_REQUIRED` state:

```
1. Agent wants to run bash_run("rm -rf /tmp/cache")
2. Server dispatches: TaskStatusUpdateEvent { state: "input-required", message: approval details }
3. Client shows approval UI
4. Client sends: POST /tasks/{id}/message/send { parts: [{ type: "text", text: "approve" }] }
5. Server calls session.approveToolCall() → task resumes to WORKING
```

## 4. Data Model (core-ai-api)

Package: `ai.core.api.a2a`

| Class | A2A Concept | Fields |
|-------|-------------|--------|
| `AgentCard` | Agent discovery | name, description, version, capabilities, skills |
| `Task` | Unit of work | id, contextId, status, artifacts, history |
| `TaskStatus` | Task state | state (enum), message (optional) |
| `TaskState` | State enum | SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, CANCELED, FAILED |
| `Message` | User/agent message | role, parts[], messageId, taskId |
| `Part` | Message content | Abstract: TextPart, FilePart, DataPart |
| `Artifact` | Agent output | name, parts[], metadata |
| `SendMessageRequest` | API request | message, configuration |
| `TaskIdParams` | API request | taskId |

## 5. Runtime (core-ai)

Package: `ai.core.a2a`

| Class | Responsibility |
|-------|---------------|
| `A2ARunManager` | Task lifecycle: create, get, resume, cancel. Uses `Supplier<Agent>` for agent creation. |
| `A2ATaskState` | Per-task state: status, output buffer, session reference, await info |
| `A2AEventAdapter` | `AgentEventListener` → A2A SSE events. Bridges TextChunkEvent→status update, ToolApprovalRequest→input-required, etc. |

### A2ARunManager API

```java
public class A2ARunManager {
    // Constructor takes agent factory (CLI provides CliAgent::of, server provides its own)
    public A2ARunManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore);

    public AgentCard getAgentCard();
    public Task createTask(SendMessageRequest request);                    // sync
    public A2ATaskState createStreamingTask(SendMessageRequest request,    // stream
                                             Consumer<String> sseSender);
    public Task getTask(String taskId);
    public void resumeTask(String taskId, Message message);               // input-required → working
    public void cancelTask(String taskId);
    public void close();
}
```

## 6. HTTP Layer (core-ai-cli)

Package: `ai.core.cli.a2a`

Embedded Undertow, same pattern as current implementation:

| File | Endpoint |
|------|----------|
| `A2AServer.java` | Undertow builder, routing, CORS, static files |
| `AgentCardHandler.java` | `GET /.well-known/agent-card.json` |
| `MessageHandler.java` | `POST /message/send` (sync + stream) |
| `TaskHandler.java` | `GET /tasks/{id}`, `POST /tasks/{id}/cancel`, `POST /tasks/{id}/message/send` |
| `CapabilitiesHandler.java` | `GET /api/capabilities` (internal, for frontend) |

## 7. Frontend Integration

The frontend communicates with the A2A endpoints:

```typescript
// Send message (streaming)
const res = await fetch('/message/send', {
  method: 'POST',
  headers: { 'Accept': 'text/event-stream', 'Content-Type': 'application/json' },
  body: JSON.stringify({
    message: { role: 'user', parts: [{ type: 'text', text: 'Hello' }] }
  })
});
// Parse SSE: TaskStatusUpdateEvent, TaskArtifactUpdateEvent

// Resume tool approval
await fetch(`/tasks/${taskId}/message/send`, {
  method: 'POST',
  body: JSON.stringify({
    message: { role: 'user', parts: [{ type: 'text', text: 'approve' }] }
  })
});
```

`GET /api/capabilities` remains an internal endpoint for the frontend to determine which nav items to show (chat-only in CLI mode, full dashboard in server mode).

## 8. CLI Interface

```
core-ai                          # TUI mode (unchanged)
core-ai --serve                  # A2A server + auto-open browser (default port 3824)
core-ai --serve --port 9090      # custom port
core-ai --serve --headless       # A2A server only, no browser (for third-party clients)
core-ai --server <url>           # remote mode (unchanged, connects to core-ai-server)
```

## 9. Migration Plan

### Phase 1: Rename + Restructure (current)

Rename existing `acp` packages to `a2a`:
- `ai.core.api.acp` → `ai.core.api.a2a`
- `ai.core.acp` → `ai.core.a2a`
- `ai.core.cli.acp` → `ai.core.cli.a2a`

Keep current endpoint paths working, add A2A-compatible aliases.

### Phase 2: Wire Format Alignment

Update data models to match A2A JSON schema:
- `AcpRun` → `Task` (with `id`, `contextId`, `status`, `artifacts`, `history`)
- `AcpMessage` → `Message` (with `parts` using `type` discriminator instead of `content_type`)
- `AcpRunStatus` → `TaskState` (SUBMITTED, WORKING, INPUT_REQUIRED, ...)
- Add `AgentCard` served at `/.well-known/agent-card.json`

Update endpoint paths:
- `POST /runs` → `POST /message/send`
- `GET /runs/{id}` → `GET /tasks/{id}`
- `POST /runs/{id}` → `POST /tasks/{id}/message/send`
- `POST /runs/{id}/cancel` → `POST /tasks/{id}/cancel`

### Phase 3: SSE Event Format

Align SSE events to A2A spec:
- Current: `{ run_id, status, output, metadata }`
- A2A: `{ type: "status"|"artifact", taskId, status: { state, message }, artifact: { parts } }`

### Phase 4: Frontend Update

Update frontend API client to use A2A endpoints and event format.

### Phase 5: Conformance Testing

Validate with A2A reference client or TCK (test compatibility kit) when available.

## 10. What We Intentionally Skip

| A2A Feature | Reason |
|-------------|--------|
| gRPC binding | Over-engineered for local CLI use |
| Push notifications | No use case for webhook-based notifications in local mode |
| Multi-tenancy | CLI serves single user |
| AUTH_REQUIRED state | No auth in local mode |
| Signed agent cards | Not needed for localhost |
| Extension mechanism | Premature; can add when needed |

These can be added to core-ai-server when enterprise multi-agent scenarios arise.
