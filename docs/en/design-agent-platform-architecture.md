# Core-AI Agent Platform Architecture

## 1. Overview

Core-AI Server is an **Enterprise Agent Platform** running in K8S where:

- Company staff create their own agents via Web or CLI, selecting from pre-configured company tools
- Agents run on **schedule** (cron) to automate daily work — fully autonomous, no human in the loop
- Users monitor agent runs, view output and status via Web or CLI
- Platform admins pre-register company internal infra resources as MCP servers and API tools
- Interactive sessions are still supported for debugging/ad-hoc use

This extends the original client/server architecture (`design-client-server-architecture.md`) from a "remote AI assistant" model to an "agent platform" model.

## 2. Design Decisions

| Decision | Choice | Reason |
|---|---|---|
| Database | MongoDB | Flexible schema for agent configs, run transcripts. `core-ng-mongo:9.4.2` available. |
| Tool approval | Always auto-approve | Autonomous agents can't wait for human approval. Tools are pre-vetted by admins. |
| CLI auth | API Key | Simple, user generates from Web dashboard, stores in `~/.core-ai/agent.properties`. |
| Web auth | Azure AD via Ingress | oauth2-proxy at ingress layer, server reads `X-Auth-Request-*` headers. |
| Scheduling | Internal scheduler | Built-in cron scheduler in core-ai-server. Simpler than K8S CronJob for v1. |

## 3. Module Layout

```
core-ai-api/       Contracts: agent/tool/schedule/run DTOs, web service interfaces
core-ai/           Agent framework (unchanged)
core-ai-server/    Agent Platform: REST API, scheduler, tool registry, run engine
core-ai-cli/       CLI: local agent (unchanged) + remote management commands
core-ai-web/       Browser dashboard (future)
```

### Dependency Graph

```
core-ai-api          pure contracts, no framework dependency
    ^
    |
core-ai              depends on core-ai-api
    ^
    |
core-ai-server       depends on core-ai, core-ai-api, core-ng-mongo
                      Agent Platform: REST API + scheduler + run engine

core-ai-cli           depends on core-ai, core-ai-api
                       local mode: in-process agent, interactive REPL (unchanged)
                       management mode: HTTP client for viewing agents/runs/schedules on server

core-ai-web           standalone frontend, pure HTTP consumer
```

### CLI Architecture Clarification

The CLI has two **independent** capabilities that do not conflict:

1. **Local Agent** (existing, unchanged) — `core-ai` starts a local in-process agent with interactive REPL. This is the current architecture: `CliApp` → `CliAgent` → `AgentSessionRunner` → `InProcessAgentSession`. No server needed.

2. **Remote Management** (new) — `core-ai agent list`, `core-ai run view <id>`, etc. These are **read/write management commands** that call core-ai-server's REST API via HTTP. No SSE, no interactive session, no streaming. Just simple request/response to manage and monitor server-side agents.

```
core-ai                          # local mode (existing, unchanged)
core-ai -m "fix the bug"        # single-shot local mode (existing)
core-ai agent list               # NEW: list agents on server
core-ai agent create ...         # NEW: create agent on server
core-ai run list <agent-id>     # NEW: list runs on server
core-ai run view <run-id>       # NEW: view run output from server
core-ai schedule create ...      # NEW: create schedule on server
```

## 4. Entity Model (MongoDB Collections)

### 4.1 users

```json
{
  "_id": "alice@company.com",
  "name": "Alice",
  "api_key": "cai_xxxxxxxxxxxx",
  "created_at": "2026-03-04T00:00:00Z",
  "last_login_at": "2026-03-04T10:00:00Z"
}
```

### 4.2 agent_definitions

```json
{
  "_id": "ObjectId",
  "user_id": "alice@company.com",
  "name": "daily-jira-report",
  "description": "Summarize yesterday's JIRA tickets and post to Slack",
  "system_prompt": "You are a project management assistant...",
  "model": "anthropic/claude-sonnet-4.6",
  "temperature": 0.7,
  "max_turns": 20,
  "tool_ids": ["jira-mcp", "slack-mcp", "builtin-web-fetch"],
  "input_template": "Generate a summary of JIRA project {{projectKey}} tickets updated yesterday and post to #{{slackChannel}}",
  "variables": { "projectKey": "CORE", "slackChannel": "daily-standup" },
  "created_at": "...",
  "updated_at": "..."
}
```

### 4.3 tool_registry

```json
{
  "_id": "jira-mcp",
  "name": "JIRA",
  "description": "Access JIRA tickets, boards, sprints via MCP",
  "type": "MCP",
  "category": "project-management",
  "config": {
    "transport": "STREAMABLE_HTTP",
    "url": "http://jira-mcp-server.infra:3000/mcp",
    "headers": { "Authorization": "Bearer {{service_token}}" }
  },
  "enabled": true,
  "created_at": "..."
}
```

Tool types:
- `MCP` — MCP server (STDIO, HTTP, SSE transport)
- `API` — REST API wrapped as tool calls via `ApiDefinition`
- `BUILTIN` — from `BuiltinTools` (file ops, web, code execution, etc.)

### 4.4 agent_schedules

```json
{
  "_id": "ObjectId",
  "agent_id": "ObjectId",
  "user_id": "alice@company.com",
  "cron_expression": "0 9 * * 1-5",
  "timezone": "Asia/Shanghai",
  "enabled": true,
  "input": "Run the daily report for today",
  "next_run_at": "2026-03-05T09:00:00+08:00",
  "created_at": "...",
  "updated_at": "..."
}
```

### 4.5 agent_runs

```json
{
  "_id": "ObjectId",
  "agent_id": "ObjectId",
  "user_id": "alice@company.com",
  "triggered_by": "SCHEDULE",
  "status": "COMPLETED",
  "input": "Run the daily report for today",
  "output": "Posted summary to #daily-standup with 12 tickets...",
  "transcript": [
    {"ts": "...", "role": "user", "content": "..."},
    {"ts": "...", "role": "assistant", "content": "..."},
    {"ts": "...", "role": "tool_call", "name": "jira_search", "args": "..."},
    {"ts": "...", "role": "tool_result", "result": "..."}
  ],
  "token_usage": { "input": 15000, "output": 3000 },
  "error": null,
  "started_at": "2026-03-04T09:00:00Z",
  "completed_at": "2026-03-04T09:02:30Z"
}
```

## 5. HTTP API

### 5.1 Authentication

All requests must include one of:
- `X-Auth-Request-Email` header (set by oauth2-proxy for Web)
- `Authorization: Bearer cai_xxxxxxxxxxxx` header (API Key for CLI)

### 5.2 Agent Definition CRUD

```
POST   /api/agents                    Create agent definition
GET    /api/agents                    List current user's agents
GET    /api/agents/:id                Get agent details
PUT    /api/agents/:id                Update agent
DELETE /api/agents/:id                Delete agent
```

### 5.3 Tool Registry

```
GET    /api/tools                     List available tools (?category=xxx)
GET    /api/tools/:id                 Get tool details
GET    /api/tools/categories          List tool categories
```

### 5.4 Schedule Management

```
POST   /api/agents/:agentId/schedules     Create schedule
GET    /api/agents/:agentId/schedules     List schedules for agent
PUT    /api/schedules/:id                  Update schedule
DELETE /api/schedules/:id                  Delete schedule
```

### 5.5 Agent Runs

```
POST   /api/agents/:agentId/runs          Trigger manual run
GET    /api/agents/:agentId/runs          List runs (?status=xxx&limit=20)
GET    /api/runs/:id                       Get run details
GET    /api/runs/:id/events                SSE stream for live run output
POST   /api/runs/:id/cancel               Cancel running agent
```

### 5.6 User

```
GET    /api/user/me                        Current user info
POST   /api/user/api-key                   Generate/regenerate API key
```

## 6. Server Internal Architecture

```
┌─────────────────────────────────────────────────────┐
│                   core-ai-server                     │
│                                                      │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │ AuthFilter│  │ AgentWebSvc  │  │ ToolWebSvc    │  │
│  │ (AzureAD │  │ (CRUD)       │  │ (Registry)    │  │
│  │ + APIKey) │  └──────┬───────┘  └───────────────┘  │
│  └──────────┘         │                              │
│                       ▼                              │
│  ┌────────────────────────────────────────────────┐  │
│  │            AgentDefinitionService              │  │
│  │  - CRUD agent definitions in MongoDB           │  │
│  └────────────────────────────────────────────────┘  │
│                                                      │
│  ┌──────────────┐     ┌───────────────────────────┐  │
│  │ ScheduleWSvc │     │    AgentScheduler          │  │
│  │ (CRUD)       │────▶│  - Cron evaluation loop    │  │
│  └──────────────┘     │  - Triggers AgentRunner    │  │
│                       └───────────┬───────────────┘  │
│                                   │                  │
│  ┌──────────────┐                 ▼                  │
│  │  RunWebSvc   │     ┌───────────────────────────┐  │
│  │ (list/trigger│────▶│      AgentRunner           │  │
│  │  /cancel)    │     │  - Builds Agent from def   │  │
│  └──────────────┘     │  - Resolves tools from     │  │
│                       │    ToolRegistry            │  │
│                       │  - Runs agent.execute()    │  │
│                       │  - Saves AgentRun to DB    │  │
│                       └───────────┬───────────────┘  │
│                                   │                  │
│                                   ▼                  │
│                       ┌───────────────────────────┐  │
│                       │    ToolRegistry            │  │
│                       │  - Pre-loaded MCP servers  │  │
│                       │  - Shared McpClientManager │  │
│                       │  - Builtin tool sets       │  │
│                       └───────────────────────────┘  │
│                                                      │
│                       ┌───────────────────────────┐  │
│                       │       MongoDB              │  │
│                       │  agent_definitions         │  │
│                       │  agent_schedules           │  │
│                       │  agent_runs                │  │
│                       │  tool_registry             │  │
│                       │  users                     │  │
│                       └───────────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

## 7. Key Components

### 7.1 ToolRegistry

Manages pre-configured company tools. Loads from MongoDB on startup, initializes shared MCP connections.

```java
public class ToolRegistry {
    Map<String, ToolRegistryEntry> tools;    // from MongoDB
    McpClientManager mcpClientManager;        // shared MCP connections

    void initialize() {
        // Load tool entries from MongoDB
        // For MCP tools: register server configs with McpClientManager
        // BUILTIN tools: map to BuiltinTools constants
    }

    // Called by AgentRunner when building an Agent
    List<ToolCall> resolveTools(List<String> toolIds) {
        List<ToolCall> result = new ArrayList<>();
        for (String toolId : toolIds) {
            var entry = tools.get(toolId);
            switch (entry.type) {
                case MCP -> result.addAll(McpToolCalls.of(mcpClientManager, entry.id));
                case BUILTIN -> result.addAll(entry.builtinTools());
                case API -> result.addAll(entry.apiTools());
            }
        }
        return result;
    }
}
```

Reuses: `McpClientManager`, `McpToolCalls`, `McpServerConfig`, `BuiltinTools`

### 7.2 AgentRunner

Builds Agent from AgentDefinition, executes, records run.

```java
public class AgentRunner {
    @Inject LLMProviders llmProviders;
    @Inject ToolRegistry toolRegistry;

    CompletableFuture<AgentRun> run(AgentDefinition def, String input, TriggerType trigger) {
        return CompletableFuture.supplyAsync(() -> {
            var run = createRunRecord(def, input, trigger);
            try {
                var agent = buildAgent(def);
                var result = agent.execute(input);
                run.status = "COMPLETED";
                run.output = result.output();
                run.transcript = captureTranscript(agent);
                run.tokenUsage = result.tokenUsage();
            } catch (Exception e) {
                run.status = "FAILED";
                run.error = e.getMessage();
            } finally {
                run.completedAt = Instant.now();
                agentRunRepository.save(run);
            }
            return run;
        }, executorService);
    }

    Agent buildAgent(AgentDefinition def) {
        var tools = toolRegistry.resolveTools(def.toolIds);
        return Agent.builder()
            .llmProvider(llmProviders.getProvider())
            .model(def.model)
            .systemPrompt(def.systemPrompt)
            .temperature(def.temperature)
            .maxTurn(def.maxTurns)
            .toolCalls(tools)
            .build();
    }
}
```

Reuses: `Agent`, `AgentBuilder`, `LLMProviders`

### 7.3 AgentScheduler

Internal scheduler using core-ng's built-in job scheduling.

```java
public class AgentScheduler {
    @Inject AgentScheduleRepository scheduleRepository;
    @Inject AgentDefinitionRepository agentRepository;
    @Inject AgentRunner runner;

    // Registered via schedule().fixedRate("agent-scheduler", Duration.ofMinutes(1))
    void evaluate() {
        var now = Instant.now();
        var dueSchedules = scheduleRepository.findDue(now);
        for (var schedule : dueSchedules) {
            var agent = agentRepository.get(schedule.agentId);
            runner.run(agent, schedule.input, TriggerType.SCHEDULE);
            schedule.nextRunAt = calculateNextRun(schedule.cronExpression, schedule.timezone);
            scheduleRepository.save(schedule);
        }
    }
}
```

### 7.4 AuthInterceptor

```java
public class AuthInterceptor implements Interceptor {
    @Inject UserRepository userRepository;

    String authenticate(Request request) {
        // 1. Azure AD header (from oauth2-proxy)
        var email = request.header("X-Auth-Request-Email");
        if (email != null) return email;

        // 2. API Key
        var auth = request.header("Authorization");
        if (auth != null && auth.startsWith("Bearer cai_")) {
            var user = userRepository.findByApiKey(auth.substring(7));
            if (user != null) return user.id;
        }

        throw new UnauthorizedException("authentication required");
    }
}
```

## 8. Execution Flows

### 8.1 Scheduled Run

```
AgentScheduler (every 1 min)
    |
    |  find due schedules where next_run_at <= now
    |
    v
AgentRunner.run(agentDefinition, input, SCHEDULE)
    |
    |  1. Create AgentRun record (status=RUNNING)
    |  2. Build Agent from definition
    |     - Resolve tools from ToolRegistry
    |     - Configure LLM provider, model, system prompt
    |  3. agent.execute(input)
    |     - LLM calls, tool calls (auto-approved), multi-turn
    |  4. Capture output, transcript, token usage
    |  5. Update AgentRun (status=COMPLETED/FAILED)
    |
    v
MongoDB: agent_runs collection updated
```

### 8.2 Manual Run (via API/CLI/Web)

```
POST /api/agents/:agentId/runs { "input": "..." }
    |
    v
AgentRunWebService
    |  validate user owns agent
    |
    v
AgentRunner.run(agentDefinition, input, MANUAL)
    |  (same flow as scheduled run)
    v
Return run ID → client polls for status via GET /api/runs/:id
```

### 8.3 CLI Local Mode (unchanged)

```
core-ai                            # starts local REPL
    |
    v
CliApp → CliAgent → AgentSessionRunner → InProcessAgentSession
    |  completely local, no server involved
    |  existing architecture, no changes needed
```

### 8.4 CLI Remote Management

```
core-ai agent list --server http://core-ai.company.com
    |
    v
HttpAgentClient
    |  GET /api/agents (with API Key header)
    |
    v
Print agent list to terminal

core-ai run view <run-id> --server http://core-ai.company.com
    |
    v
HttpAgentClient
    |  GET /api/runs/:id
    |
    v
Print run output/transcript to terminal
```

Simple HTTP request/response. No SSE, no streaming, no interactive session.

## 9. CLI Commands

The CLI has two independent modes:

### 9.1 Local Mode (existing, unchanged)

```bash
core-ai                          # interactive REPL with local agent
core-ai -m "fix the bug"        # single-shot local mode
core-ai --model gpt-4           # local mode with specific model
```

### 9.2 Remote Management Commands (new)

All remote commands require `--server` flag (or configured in `~/.core-ai/agent.properties`).

```bash
# Agent management
core-ai agent list
core-ai agent create --name "daily-report" --prompt "..." --tools jira-mcp,slack-mcp
core-ai agent view <agent-id>
core-ai agent delete <agent-id>

# Schedule management
core-ai schedule create --agent <id> --cron "0 9 * * 1-5" --input "Run daily report"
core-ai schedule list --agent <id>
core-ai schedule disable <schedule-id>

# Run management
core-ai run trigger <agent-id> --input "Run now"
core-ai run list <agent-id>
core-ai run view <run-id>          # show output
core-ai run logs <run-id>          # show full transcript

# Tool discovery
core-ai tool list                  # list available tools on server
core-ai tool list --category infra
```

## 10. Deployment

### K8S Resources

```yaml
# core-ai-server Deployment
apiVersion: apps/v1
kind: Deployment
metadata:
  name: core-ai-server
spec:
  replicas: 2
  template:
    spec:
      containers:
      - name: core-ai-server
        image: core-ai-server:latest
        ports:
        - containerPort: 8080
        env:
        - name: SYS_MONGO_URI
          valueFrom:
            secretKeyRef:
              name: core-ai-secrets
              key: mongo-uri
        - name: OPENAI_API_KEY
          valueFrom:
            secretKeyRef:
              name: core-ai-secrets
              key: openai-api-key
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "2000m"

---
# Ingress with oauth2-proxy for Azure AD
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: core-ai-server
  annotations:
    nginx.ingress.kubernetes.io/auth-url: "https://oauth2-proxy.infra/oauth2/auth"
    nginx.ingress.kubernetes.io/auth-signin: "https://oauth2-proxy.infra/oauth2/start"
    nginx.ingress.kubernetes.io/auth-response-headers: "X-Auth-Request-Email,X-Auth-Request-User"
spec:
  rules:
  - host: core-ai.company.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: core-ai-server
            port:
              number: 8080
```

### Resource Limits

- Each agent run has configurable `max_turns` (default 20) and execution timeout (default 10 min)
- Bounded thread pool for concurrent runs (default: 10 parallel runs)
- Token usage tracked per run for cost monitoring

## 11. Framework Reuse Map

| Existing Component | Platform Role | Changes |
|---|---|---|
| `Agent` + `AgentBuilder` | Execution engine inside AgentRunner | None |
| `McpClientManager` + `McpToolCalls` | Shared MCP connections for ToolRegistry | None |
| `BuiltinTools.ALL` | Selectable tool sets in ToolRegistry | None |
| `LLMProviders` | LLM backend for all agent runs | None |
| `StreamingCallback` | Capture output during runs | None |
| `MultiAgentModule` | Bootstrap LLM providers, MCP client | None |
| `AgentBootstrap` | Initialize providers from properties | None |
| `InProcessAgentSession` | CLI local mode only (unchanged) | None |

## 12. Known Issues & Future Considerations

### 12.1 Multi-Replica Scheduling (must fix before multi-replica deployment)

When core-ai-server runs with `replicas > 1`, the internal `AgentScheduler` in each replica will evaluate the same schedules, causing **duplicate triggers**.

Solutions (pick one during Phase 5):
- **MongoDB atomic lock**: use `findOneAndUpdate` with a `locked_by` field and TTL — only one replica claims each due schedule. Simple, no extra infra.
- **Leader election**: only one replica runs the scheduler. Can use K8S Lease API or a MongoDB-based lease.
- **Single scheduler replica**: deploy scheduler as a separate single-replica Deployment. Cleanest separation but adds operational complexity.

Recommendation: MongoDB atomic lock for v1 — minimal code, leverages existing MongoDB.

### 12.2 Concurrent Execution Policy

If a scheduled run is still executing when the next trigger fires, the system needs a policy:

| Policy | Behavior | Use case |
|---|---|---|
| **SKIP** (default) | Skip if previous run for same agent is still RUNNING | Most agents — avoid wasted resources |
| QUEUE | Queue the new run, execute after previous completes | Sequential dependency scenarios |
| PARALLEL | Run in parallel regardless | Independent stateless agents |

Implementation: add `concurrency_policy` field to `agent_schedules` (default: `SKIP`). AgentScheduler checks for existing RUNNING runs before triggering.

### 12.3 Run Timeout

`max_turns` limits the number of LLM rounds but does not cap wall-clock time. An agent can hang if an MCP server is unresponsive or a tool call blocks indefinitely.

Solution: add `timeout_seconds` field to `agent_definitions` (default: 600 = 10 min). AgentRunner wraps execution with `CompletableFuture.get(timeout, TimeUnit.SECONDS)` and cancels the agent thread on timeout. The run is recorded as `TIMEOUT` status.

### 12.4 Tool Registry Admin Management

In v1, the `tool_registry` collection is managed by platform admins. Two options:

- **v1**: Admin manages tools directly via MongoDB shell or a seed script on deployment. Simple, no extra API surface.
- **v2**: Add admin-only API endpoints (`POST/PUT/DELETE /api/admin/tools`) protected by an admin role check. Enables Web-based tool management.

### 12.5 Run Result Notifications

Users may not actively poll for results. When a scheduled run completes or fails, the system should notify the owner.

Future options (not in v1):
- **Webhook**: agent definition includes a `webhook_url`, server POSTs run result on completion.
- **Email**: integrate with company SMTP to send run summary.
- **Slack/Teams**: post to a configured channel via MCP tool (the agent itself can do this as part of its task).
- **Web push**: browser notifications for the Web dashboard.

For v1, users poll via `GET /api/agents/:id/runs` or `core-ai run list`. Notification support is deferred.

### 12.6 Transcript Size

`agent_runs.transcript` is an embedded array in the MongoDB document. MongoDB has a 16MB document size limit. For most runs (< 50 tool calls), this is sufficient.

Mitigation for large runs:
- AgentRunner truncates tool result content beyond a threshold (e.g., 10KB per tool result) before saving.
- If a run transcript exceeds a configurable limit, store the full transcript in MongoDB GridFS and keep only a summary in the `agent_runs` document.

### 12.7 MCP Connection Lifecycle

ToolRegistry initializes shared MCP connections on startup. Considerations:
- **Hot reload**: when admin adds a new tool to `tool_registry`, the server must restart to pick it up. For v2, add a reload mechanism (admin API or periodic refresh).
- **Connection failure**: if an MCP server goes down, all agents using that tool will fail. McpClientManager already has reconnect with exponential backoff, but agent runs should handle `McpConnectionException` gracefully and record it in the run error.
- **Connection pool**: multiple concurrent agent runs share the same MCP connections. Ensure McpClientManager handles concurrent `callTool` requests safely (it does — verified in existing code).

## 13. Implementation Phases

| Phase | Scope | Module |
|---|---|---|
| 1 | MongoDB + Entity Layer | core-ai-server |
| 2 | Tool Registry | core-ai-api, core-ai-server |
| 3 | Agent Definition CRUD | core-ai-api, core-ai-server |
| 4 | AgentRunner + Runs | core-ai-api, core-ai-server |
| 5 | Scheduler | core-ai-api, core-ai-server |
| 6 | Authentication | core-ai-server |
| 7 | CLI Extensions | core-ai-cli |
