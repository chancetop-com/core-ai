# Client/Server Architecture Design

## 1. Goals

Core-AI currently works as an embedded library. This design extends it to also work as a standalone service with remote clients, enabling:

- **Enterprise deployment**: shared server, multiple users, Web dashboard
- **Personal use**: local CLI mode, zero infrastructure
- **API integration**: third-party systems call the agent over HTTP
- **Multi-client**: same agent backend serves CLI, Web, and programmatic clients

The core constraint: **the agent framework (core-ai) does not change.** All C/S behavior is layered on top through existing extension points (StreamingCallback, AbstractLifecycle, PersistenceProvider).

## 2. Module Layout

```
core-ai-api/       Contracts: AgentSession interface, event types, HTTP DTOs
core-ai/           Agent framework (unchanged)
core-ai-server/    HTTP service: REST + SSE, wraps InProcessAgentSession
core-ai-cli/       Terminal client: local mode (in-process) or remote mode (HTTP)
core-ai-web/       Browser client: calls core-ai-server (future)
```

### Dependency Graph

```
core-ai-api          pure contracts, no framework dependency
    ^
    |
core-ai              depends on core-ai-api
    ^
    |
core-ai-server       depends on core-ai, core-ai-api
                      applies "app" plugin (core-ng -service module)

core-ai-cli           depends on core-ai, core-ai-api
                       local mode: uses core-ai directly (InProcessAgentSession)
                       remote mode: HTTP client only (HttpAgentSession)

core-ai-web           standalone frontend, pure HTTP/SSE consumer
```

## 3. Core Abstraction: AgentSession

One interface, two implementations. CLI and Web never touch Agent directly.

### 3.1 Interface

```java
package ai.core.api.session;

// in core-ai-api
public interface AgentSession {
    String id();
    void sendMessage(String message);
    void onEvent(AgentEventListener listener);
    void approveToolCall(String callId, ApprovalDecision decision);
    void close();
}
```

### 3.2 Events

All agent-to-client communication flows through a sealed event hierarchy. This model works identically for in-process callbacks and SSE serialization.

```java
package ai.core.api.session;

public sealed interface AgentEvent {
    String sessionId();
}

// LLM streaming token
public record TextChunkEvent(String sessionId, String chunk)
    implements AgentEvent {}

// extended thinking token
public record ReasoningChunkEvent(String sessionId, String chunk)
    implements AgentEvent {}

// reasoning phase complete
public record ReasoningCompleteEvent(String sessionId, String reasoning)
    implements AgentEvent {}

// tool execution started
public record ToolStartEvent(
    String sessionId, String callId, String toolName, String arguments
) implements AgentEvent {}

// tool execution finished
public record ToolResultEvent(
    String sessionId, String callId, String toolName,
    String status, String result
) implements AgentEvent {}

// tool needs user approval before executing
public record ToolApprovalRequestEvent(
    String sessionId, String callId, String toolName, String arguments
) implements AgentEvent {}

// agent turn complete
public record TurnCompleteEvent(String sessionId, String output)
    implements AgentEvent {}

// error occurred
public record ErrorEvent(String sessionId, String message, String detail)
    implements AgentEvent {}

// agent status changed
public record StatusChangeEvent(String sessionId, String status)
    implements AgentEvent {}
```

### 3.3 Listener

```java
package ai.core.api.session;

public interface AgentEventListener {
    default void onTextChunk(TextChunkEvent event) {}
    default void onReasoningChunk(ReasoningChunkEvent event) {}
    default void onReasoningComplete(ReasoningCompleteEvent event) {}
    default void onToolStart(ToolStartEvent event) {}
    default void onToolResult(ToolResultEvent event) {}
    default void onToolApprovalRequest(ToolApprovalRequestEvent event) {}
    default void onTurnComplete(TurnCompleteEvent event) {}
    default void onError(ErrorEvent event) {}
    default void onStatusChange(StatusChangeEvent event) {}
}
```

### 3.4 Supporting Types

```java
public enum ApprovalDecision {
    APPROVE,          // allow this call
    APPROVE_ALWAYS,   // allow and remember for this session
    DENY              // reject
}

public class SessionConfig {
    public String model;
    public Double temperature;
    public String systemPrompt;
    public Integer maxTurns;
    public boolean autoApproveAll;
    public String workingDirectory;
    public Map<String, Object> mcpServers;
}
```

## 4. InProcessAgentSession

Location: `core-ai` module (depends on Agent directly).

Used by **both** CLI local mode and core-ai-server internally. This guarantees one agent execution path.

```java
package ai.core.session;

public class InProcessAgentSession implements AgentSession {
    private final String sessionId;
    private final Agent agent;
    private final ExecutorService executor;
    private final List<AgentEventListener> listeners;
    private final PermissionGate permissionGate;

    @Override
    public void sendMessage(String message) {
        executor.submit(() -> {
            try {
                agent.run(message);
            } catch (Exception e) {
                dispatch(new ErrorEvent(sessionId, e.getMessage(), ...));
            }
        });
    }

    @Override
    public void approveToolCall(String callId, ApprovalDecision decision) {
        permissionGate.respond(callId, decision);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        // cleanup agent, MCP connections, etc.
    }
}
```

### Key Internal Components

**SessionStreamingCallback** (implements `StreamingCallback`):

Bridges the existing StreamingCallback interface to AgentEvent dispatch without modifying the Agent.

```java
class SessionStreamingCallback implements StreamingCallback {
    @Override public void onChunk(String chunk) {
        dispatch(new TextChunkEvent(sessionId, chunk));
    }
    @Override public void onReasoningChunk(String chunk) {
        dispatch(new ReasoningChunkEvent(sessionId, chunk));
    }
    // ... maps each callback method to its AgentEvent type
}
```

**ServerPermissionLifecycle** (extends `AbstractLifecycle`):

Replaces the CLI's direct `ui.askPermission()` with a transport-agnostic blocking mechanism.

```java
class ServerPermissionLifecycle extends AbstractLifecycle {
    @Override
    public void beforeTool(FunctionCall call, ExecutionContext ctx) {
        if (autoApproved(call.function.name)) return;

        String callId = call.id;
        dispatch(new ToolApprovalRequestEvent(sessionId, callId, ...));

        // blocks agent thread until client responds
        ApprovalDecision decision = permissionGate.waitForApproval(callId, 300_000);

        if (decision == ApprovalDecision.DENY) {
            throw new ToolCallDeniedException("denied: " + call.function.name);
        }
    }
}
```

**PermissionGate**:

A `CompletableFuture`-based gate that blocks the agent thread on `waitForApproval()` and unblocks when the client calls `approveToolCall()`.

```java
class PermissionGate {
    private final ConcurrentMap<String, CompletableFuture<ApprovalDecision>> pending
        = new ConcurrentHashMap<>();

    // called on agent thread - blocks
    ApprovalDecision waitForApproval(String callId, long timeoutMs) {
        var future = new CompletableFuture<ApprovalDecision>();
        pending.put(callId, future);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return ApprovalDecision.DENY;
        } finally {
            pending.remove(callId);
        }
    }

    // called on client thread - unblocks
    void respond(String callId, ApprovalDecision decision) {
        var future = pending.get(callId);
        if (future != null) future.complete(decision);
    }
}
```

## 5. HttpAgentSession

Location: `core-ai-cli` module (for CLI remote mode) or a shared `core-ai-client` module.

```java
package ai.core.session;

public class HttpAgentSession implements AgentSession {
    private final String sessionId;
    private final HttpClient httpClient;
    private final String serverUrl;

    @Override
    public void sendMessage(String message) {
        // POST {serverUrl}/api/sessions/{id}/messages
        // body: { "message": "..." }
    }

    @Override
    public void approveToolCall(String callId, ApprovalDecision decision) {
        // POST {serverUrl}/api/sessions/{id}/approve
        // body: { "callId": "...", "decision": "APPROVE" }
    }

    @Override
    public void onEvent(AgentEventListener listener) {
        // connect to SSE: GET {serverUrl}/api/sessions/{id}/events
        // parse SSE events, dispatch to listener
    }

    @Override
    public void close() {
        // DELETE {serverUrl}/api/sessions/{id}
    }
}
```

## 6. Server Module (core-ai-server)

### 6.1 HTTP API

```
POST   /api/sessions                     Create session (returns sessionId)
GET    /api/sessions/{id}/events          SSE event stream
POST   /api/sessions/{id}/messages        Send user message
POST   /api/sessions/{id}/approve         Tool approval response
DELETE /api/sessions/{id}                 Close session
GET    /api/sessions/{id}/status          Current status
GET    /api/sessions/{id}/history         Conversation history
```

### 6.2 SSE Event Format

```
event: text_chunk
data: {"sessionId":"s1","chunk":"Hello"}

event: tool_approval_request
data: {"sessionId":"s1","callId":"tc_2","toolName":"edit_file","arguments":"{...}"}

event: tool_result
data: {"sessionId":"s1","callId":"tc_2","toolName":"edit_file","status":"completed"}

event: turn_complete
data: {"sessionId":"s1","output":"Done."}
```

### 6.3 Internal Wiring

```
SessionController (HTTP endpoints)
    |
    v
AgentSessionManager
    |  - Map<sessionId, InProcessAgentSession>
    |  - Session lifecycle (create, get, close, timeout cleanup)
    v
InProcessAgentSession
    |  - Agent.run() on background thread
    |  - SessionStreamingCallback -> event dispatch -> SSE push
    |  - ServerPermissionLifecycle -> PermissionGate -> wait for client
    v
Agent (core-ai framework, unchanged)
```

### 6.4 AgentSessionManager

```java
package ai.core.server.session;

public class AgentSessionManager {
    private final ConcurrentMap<String, InProcessAgentSession> sessions;
    private final AgentSessionFactory factory;

    public String createSession(SessionConfig config) {
        var sessionId = UUID.randomUUID().toString();
        var session = factory.create(sessionId, config);
        sessions.put(sessionId, session);
        return sessionId;
    }

    public InProcessAgentSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void closeSession(String sessionId) {
        var session = sessions.remove(sessionId);
        if (session != null) session.close();
    }

    // scheduled cleanup of idle sessions
    public void cleanupIdleSessions(Duration maxIdle) { ... }
}
```

### 6.5 Build Configuration

`core-ai-server` follows the existing project convention for `-service` modules. The root `build.gradle.kts` automatically applies the `app` plugin and `core.framework:core-ng` dependency for modules ending in `-service`:

```kotlin
// already handled by existing convention:
//   configure(subprojects.filter { it.name.endsWith("-service") }) {
//       apply(plugin = "app")
//       ...
//   }
```

So the module should be named `core-ai-server-service` (or use a custom configure block if the name `core-ai-server` is preferred).

## 7. Session Management

Inspired by OpenClaw's session architecture, the session layer handles identity, lifecycle, persistence, and cleanup.

### 7.1 Session Key Hierarchy

Sessions are identified by a structured key that encodes scope and ownership:

```
agent:<agentId>:<scope>:<identifier>

Examples:
  agent:core-ai-assistant:user:u_12345           # user-scoped, server mode
  agent:core-ai-assistant:local:cli_abc123        # local CLI session
  agent:core-ai-assistant:api:client_xyz          # API integration session
```

- `agentId`: identifies the agent configuration (model, tools, system prompt)
- `scope`: isolation boundary — `user` (server multi-user), `local` (CLI single-user), `api` (programmatic)
- `identifier`: unique within scope — user ID, CLI instance ID, or API client ID

The `AgentSessionManager` uses this key as the primary index. Session lookup, persistence, and cleanup all operate on this key structure.

### 7.2 Session Lifecycle and Expiry

Each session has a creation time, last-active time, and expiry policy. Expiry is evaluated lazily — no background timer, checked on access and during periodic cleanup sweeps.

```java
public class SessionMetadata {
    String sessionKey;                    // structured key
    Instant createdAt;
    Instant lastActiveAt;
    SessionExpiryPolicy expiryPolicy;
    int messageCount;
    long estimatedTokens;
}

public record SessionExpiryPolicy(
    Duration idleTimeout,                 // close after inactivity (default: 30min)
    Duration maxAge,                      // absolute max lifetime (default: 24h)
    boolean dailyReset                    // auto-expire at midnight (for CLI daily workflow)
) {
    public static SessionExpiryPolicy serverDefault() {
        return new SessionExpiryPolicy(Duration.ofMinutes(30), Duration.ofHours(24), false);
    }
    public static SessionExpiryPolicy cliDefault() {
        return new SessionExpiryPolicy(Duration.ofHours(4), Duration.ofHours(24), true);
    }
}
```

Expiry check:

```java
boolean isExpired(SessionMetadata meta, Instant now) {
    var policy = meta.expiryPolicy;
    if (policy.dailyReset() && !meta.createdAt.toLocalDate().equals(now.toLocalDate())) {
        return true;
    }
    if (Duration.between(meta.lastActiveAt, now).compareTo(policy.idleTimeout()) > 0) {
        return true;
    }
    if (Duration.between(meta.createdAt, now).compareTo(policy.maxAge()) > 0) {
        return true;
    }
    return false;
}
```

### 7.3 Transcript Persistence

Session transcripts are stored in JSONL format — one JSON object per line, append-only. This provides crash resilience (partial writes lose at most one line) and efficient streaming reads.

```
~/.core-ai/sessions/
  agent_core-ai-assistant_local_cli_abc123/
    transcript.jsonl       # full message history
    metadata.json          # session metadata
    artifacts/             # tool output files (optional)
```

Each line in `transcript.jsonl`:

```json
{"ts":"2026-02-26T10:30:00Z","role":"user","content":"fix the null pointer in UserService"}
{"ts":"2026-02-26T10:30:01Z","role":"assistant","content":"","streaming":true}
{"ts":"2026-02-26T10:30:02Z","role":"tool_call","name":"grep_file","args":{"pattern":"UserService","path":"src/"}}
{"ts":"2026-02-26T10:30:03Z","role":"tool_result","name":"grep_file","status":"success","result":"..."}
{"ts":"2026-02-26T10:30:15Z","role":"assistant","content":"I found the issue in UserService.java:42..."}
```

For server mode, transcripts are stored via `PersistenceProvider` (Redis or database) rather than local files. The format remains JSONL for consistency.

### 7.4 Session Maintenance

Long-running sessions accumulate large transcripts. Maintenance policies prevent unbounded growth:

```java
public record SessionMaintenancePolicy(
    int maxEntries,                // max transcript lines before compaction (default: 2000)
    long maxBytes,                 // max transcript size before rotation (default: 10MB)
    Duration pruneAfter            // delete session data after this period (default: 30 days)
) {
    public static SessionMaintenancePolicy defaultPolicy() {
        return new SessionMaintenancePolicy(2000, 10_000_000, Duration.ofDays(30));
    }
}
```

Maintenance actions (triggered on session close or periodic sweep):

1. **Compaction**: when `maxEntries` exceeded, the existing `Compression` lifecycle summarizes old turns and replaces them with a summary entry. This reuses the agent's built-in context compression.
2. **Rotation**: when `maxBytes` exceeded, archive current transcript to `transcript-{timestamp}.jsonl.gz` and start fresh with a summary preamble.
3. **Pruning**: sessions with `lastActiveAt` older than `pruneAfter` have their data directories deleted entirely.

### 7.5 Memory Flush Before Compaction

Before compacting or closing a long session, a "memory flush" step extracts important information the agent learned during the session and persists it to long-term memory. This prevents knowledge loss when transcripts are compacted.

Integration with the existing `Memory` and `MemoryLifecycle`:

```java
// In session close / compaction flow:
if (agent.memory != null && session.messageCount > FLUSH_THRESHOLD) {
    // Silent internal turn: ask agent to summarize what it learned
    String flushPrompt = "Summarize key facts, decisions, and user preferences "
        + "from this session that should be remembered for future sessions.";
    var memories = agent.memory.extract(session.getTranscript(), flushPrompt);
    agent.memory.store(memories);
}
```

This runs before compaction so the full transcript is available for extraction. The extracted memories are stored via the unified `Memory` interface (same as the existing memory system).

### 7.6 Multi-User Isolation (Server Mode)

In server mode, the session key's `scope:identifier` ensures isolation:

- Each user gets a separate `InProcessAgentSession` with its own `Agent` instance
- Agent message history, tool state, and MCP connections are per-session
- `PersistenceProvider` keys are namespaced by session key — no cross-user data leakage
- Session cleanup on user disconnect releases all resources

```
User A  -->  session agent:assistant:user:alice   -->  Agent instance A
User B  -->  session agent:assistant:user:bob     -->  Agent instance B
```

Both agents share the same LLM provider and tool definitions, but maintain fully isolated conversation state.

## 8. CLI Module (core-ai-cli)

### 7.1 Dual Mode

```bash
core-ai                          # local mode (default, in-process agent)
core-ai --server http://host:8080  # remote mode (HTTP client)
core-ai -m "fix the bug"        # single-shot local mode
```

### 7.2 Unified Client Code

CliApp works through AgentSession only. Switching between local and remote is a one-line factory change:

```java
public class CliApp {
    AgentSession createSession(CliConfig config) {
        if (config.serverUrl != null) {
            // remote: HTTP client
            return HttpAgentSession.connect(config.serverUrl, toSessionConfig(config));
        }
        // local: in-process
        return AgentSessionFactory.create(UUID.randomUUID().toString(), toSessionConfig(config));
    }

    void repl(TerminalUI ui, AgentSession session) {
        session.onEvent(new CliEventListener(ui, session));
        while (true) {
            String input = ui.readInput();
            if (input == null || "/exit".equals(input)) break;
            session.sendMessage(input);
        }
    }
}
```

### 7.3 CliEventListener

Translates AgentEvents into terminal operations:

```java
class CliEventListener implements AgentEventListener {
    private final TerminalUI ui;
    private final AgentSession session;
    private final PermissionPolicy policy;

    @Override public void onTextChunk(TextChunkEvent e) {
        ui.printStreamingChunk(e.chunk());
    }

    @Override public void onToolApprovalRequest(ToolApprovalRequestEvent e) {
        // display tool info, prompt Y/n/always
        String response = ui.askPermissionRaw(e.toolName(), e.arguments());
        ApprovalDecision decision = parseDecision(response);
        session.approveToolCall(e.callId(), decision);
    }

    @Override public void onTurnComplete(TurnCompleteEvent e) {
        ui.endStreaming();
    }

    @Override public void onError(ErrorEvent e) {
        ui.showError(e.message());
    }
}
```

## 9. Tool Approval Flow

### Local Mode

```
CLI Main Thread                     Agent Background Thread
    |                                     |
    |  session.sendMessage("fix bug")     |
    | ----------------------------------> |
    |                                     |  agent.run("fix bug")
    |                                     |    LLM -> tool_call: edit_file
    |                                     |    ServerPermissionLifecycle.beforeTool()
    |                                     |    PermissionGate.waitForApproval()  [BLOCKS]
    |                                     |
    | <-- ToolApprovalRequestEvent ------ |
    |                                     |
    |  user types "y"                     |
    |                                     |
    |  session.approveToolCall(APPROVE)   |
    | ----------------------------------> |
    |                                     |    PermissionGate unblocks
    |                                     |    tool executes
    | <-- ToolResultEvent --------------- |
    | <-- TextChunkEvent ... ------------ |
    | <-- TurnCompleteEvent ------------- |
```

### Remote Mode

```
CLI                               Server                          Agent Thread
 |                                   |                                  |
 | POST /messages {"fix bug"}        |                                  |
 | --------------------------------> |  session.sendMessage()           |
 |                                   | ------------------------------> |
 |                                   |                                  | agent.run()
 |                                   |                                  |   beforeTool()
 |                                   |                                  |   PermissionGate.wait()
 |                                   |                                  |
 | SSE: tool_approval_request        | <------ event dispatch -------- |
 | <-------------------------------- |                                  |
 |                                   |                                  |
 | user types "y"                    |                                  |
 |                                   |                                  |
 | POST /approve {APPROVE}           |                                  |
 | --------------------------------> |  session.approveToolCall()       |
 |                                   | ------------------------------> |
 |                                   |                                  | PermissionGate unblocks
 |                                   |                                  | tool executes
 | SSE: tool_result                  | <------ event dispatch -------- |
 | <-------------------------------- |                                  |
 | SSE: turn_complete                | <------------------------------- |
 | <-------------------------------- |                                  |
```

Both flows use the same PermissionGate mechanism. The only difference is transport.

## 10. Framework Reuse Map

What exists in core-ai today and how it maps to the C/S architecture:

| Existing Component | C/S Role | Changes |
|---|---|---|
| `Agent` + `AgentBuilder` | Execution engine inside InProcessAgentSession | None |
| `StreamingCallback` | Bridge to AgentEvent via SessionStreamingCallback | New impl only |
| `AbstractLifecycle` | Base for ServerPermissionLifecycle | New subclass only |
| `BuiltinTools.ALL` | Default tool set | None |
| `LiteLLMProvider` / `OpenAIProvider` | LLM backend | None |
| `Compression` + `ToolCallPruning` | Context management | None |
| `PersistenceProvider` (File/Redis) | Session state persistence | None |
| `AgentPersistence` | Agent save/load | None |
| `McpClientManager` | MCP integration | None |
| `NodeStatus` | Status tracking | None |
| `ExecutionContext` | Session/user context | None |
| `Task` / `TaskMessage` | Conversation history model | None |
| `Skills` system | Domain knowledge injection | None |

## 11. New Components Summary

### core-ai-api (contracts)

| Component | Type |
|---|---|
| `AgentSession` | Interface |
| `AgentEvent` (sealed, 9 subtypes) | Records |
| `AgentEventListener` | Interface |
| `SessionConfig` | DTO |
| `ApprovalDecision` | Enum |
| `CreateSessionRequest/Response` | HTTP DTOs |
| `SendMessageRequest` | HTTP DTO |
| `ApproveToolCallRequest` | HTTP DTO |

### core-ai (framework additions)

| Component | Type |
|---|---|
| `InProcessAgentSession` | AgentSession impl |
| `PermissionGate` | Internal concurrency primitive |
| `SessionStreamingCallback` | StreamingCallback impl |
| `ServerPermissionLifecycle` | AbstractLifecycle subclass |
| `AgentSessionFactory` | Factory |

### core-ai-server (new module)

| Component | Type |
|---|---|
| `AgentSessionManager` | Session lifecycle manager |
| `SessionController` | HTTP controller (REST + SSE) |
| `SseEventDispatcher` | AgentEvent -> SSE bridge |
| `SessionMetadata` | Session state and timestamps |
| `SessionExpiryPolicy` | Expiry rules (idle, maxAge, dailyReset) |
| `SessionMaintenancePolicy` | Compaction, rotation, pruning rules |
| `TranscriptStore` | JSONL transcript read/write |

### core-ai-cli (modifications)

| Component | Type |
|---|---|
| `HttpAgentSession` | AgentSession impl (HTTP client) |
| `CliEventListener` | AgentEventListener impl |
| `CliApp` refactored | Uses AgentSession instead of Agent |

## 12. Implementation Phases

```
Phase 1: Contracts                          (core-ai-api)
  AgentSession, AgentEvent, AgentEventListener, DTOs

Phase 2: In-Process Session                 (core-ai)
  PermissionGate, SessionStreamingCallback,
  ServerPermissionLifecycle, InProcessAgentSession, AgentSessionFactory

Phase 3: CLI Refactor                       (core-ai-cli)
  CliEventListener, refactor CliApp to use AgentSession
  Verify local mode works end-to-end

Phase 4: Server                             (core-ai-server)
  AgentSessionManager, SessionController (REST + SSE)
  Verify with curl / httpie

Phase 5: CLI Remote Mode                    (core-ai-cli)
  HttpAgentSession, --server flag
  Verify CLI -> Server -> Agent round-trip

Phase 6: Web Frontend                       (core-ai-web, future)
```

## 13. Deployment Scenarios

| Scenario | What runs | Config |
|---|---|---|
| Developer local | `core-ai-cli` alone | No server, in-process agent |
| Team shared | `core-ai-server` + N x `core-ai-cli --server` | Server manages sessions |
| Web dashboard | `core-ai-server` + `core-ai-web` | Browser SSE client |
| Business API | `core-ai-server` | Custom HTTP client |
| Agent-to-Agent | `core-ai-server` | Extend via A2A protocol + Task system |

## 14. Recommendations & Future Considerations

### 14.1 Connection Robustness
- **SSE Reconnection**: `HttpAgentSession` should implement automatic exponential backoff for SSE reconnections.
- **State Synchronization**: Add a `GET /api/sessions/{id}/state` endpoint to allow clients to re-sync the agent's current status (thinking, awaiting_approval, idle) after a reconnect.

### 14.2 Scalability & Concurrency
- **Distributed Sessions**: For horizontal scaling, `AgentSessionManager` should support sticky sessions or external state synchronization for `PermissionGate` blocking states.
- **Task Quotas**: Implement bounded execution queues in the server to prevent LLM request spikes from exhausting server resources.

### 14.3 Security
- **Authentication**: Integrate API Key or OAuth2 for the `core-ai-server` in enterprise deployment scenarios.
- **Data Masking**: Sensitive parameters in `ToolApprovalRequestEvent` (e.g., passwords or tokens) should be masked before being sent to the client.

### 14.4 Performance Optimization
- **Event Batching**: Micro-batch small `TextChunkEvent` packets (e.g., every 50ms) to reduce network overhead during high-speed LLM generation.
- **Artifact Offloading**: Large binary outputs (images, logs) should be served via a dedicated `/api/sessions/{id}/artifacts/{fileId}` endpoint rather than embedded in the SSE stream.

### 14.5 Observability
- **Distributed Tracing**: Carry `traceId` through `AgentEvent` to correlate CLI actions with server-side LLM calls.
- **Metrics**: Track active session counts, token consumption rates, and average approval latency for operational monitoring.
