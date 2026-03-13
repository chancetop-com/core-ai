# Core AI Server

Core AI Server is the backend service for the Core AI platform, providing REST APIs for agent management, execution, scheduling, real-time streaming, observability tracing, and prompt management.

## Architecture

```
HTTP Request
    │
    ▼
AuthInterceptor (API Key / Azure AD)
    │
    ▼
WebService Layer (REST API)
    │
    ▼
Service Layer
    ├── AgentDefinitionService    Agent CRUD + publish
    ├── AgentRunService           Trigger + query runs
    ├── AgentRunner               Async execution (thread pool)
    ├── AgentSessionManager       Interactive sessions
    ├── AgentScheduler            Cron-based scheduling
    ├── ToolRegistryService       Builtin + MCP tools
    ├── OTLPIngestService         OpenTelemetry trace ingestion
    └── PromptService             Prompt template management
    │
    ▼
MongoDB (persistence) + SSE (real-time events)
```

## Quick Start

### Prerequisites

- Java 25+
- MongoDB

### Configuration

Create `sys.properties`:

```properties
sys.http.port=8080
sys.mongo.uri=mongodb://localhost:27017/core-ai
```

Create `agent.properties`:

```properties
# LLM provider (at least one required)
litellm.api.base=http://localhost:4000
litellm.api.key=sk-xxx

# or OpenAI directly
openai.api.base=https://api.openai.com/v1
openai.api.key=sk-xxx

# Tracing (optional, export to self or Langfuse)
trace.otlp.endpoint=http://localhost:8080
trace.service.name=core-ai

# Admin account
sys.admin.email=admin@example.com
sys.admin.password=admin
sys.admin.name=Admin

# File storage
sys.file.storagePath=./data/files
```

### Run

```bash
./gradlew :core-ai-server:run
```

## Authentication

Two authentication methods are supported:

### API Key

```
Authorization: Bearer coreai_<key>
```

API keys are generated on registration or via `POST /api/user/api-key`. Format: `coreai_` + Base64-encoded 32 random bytes.

### Azure AD (SSO)

For enterprise deployments behind an Azure AD proxy:

```
X-Auth-Request-Email: user@company.com
X-Auth-Request-User: User Name
```

Users are auto-created on first login.

### Public Endpoints (no auth required)

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/webhooks/:agentId` (validated via webhook secret)

---

## API Reference

### Auth

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/api/auth/register` | Register new user | 201 |
| POST | `/api/auth/login` | Login | 200 |
| POST | `/api/auth/invite` | Invite user (admin only) | 200 |
| GET | `/api/auth/users` | List users (admin only) | 200 |

### User

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| GET | `/api/user/me` | Get current user info | 200 |
| POST | `/api/user/api-key` | Generate new API key | 200 |

### Agent Definitions

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/api/agents` | Create agent | 201 |
| GET | `/api/agents` | List agents | 200 |
| GET | `/api/agents/:id` | Get agent | 200 |
| PUT | `/api/agents/:id` | Update agent | 200 |
| DELETE | `/api/agents/:id` | Delete agent | 200 |
| POST | `/api/agents/:id/publish` | Publish agent | 200 |
| POST | `/api/agents/from-session` | Create agent from session | 201 |
| POST | `/api/agents/:id/webhook/enable` | Enable webhook | 200 |
| POST | `/api/agents/:id/webhook/disable` | Disable webhook | 200 |

**Draft/Published model**: Agents have draft config (editable) and published config (immutable snapshot). Only published agents can be triggered for runs.

### Agent Runs

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/api/runs/agent/:agentId/trigger` | Trigger async run | 202 |
| GET | `/api/runs/agent/:agentId/list` | List runs | 200 |
| GET | `/api/runs/:id` | Get run detail (with transcript) | 200 |
| POST | `/api/runs/:id/cancel` | Cancel run | 200 |
| POST | `/api/llm/:id/call` | Direct LLM call (sync) | 200 |

**Run statuses**: `RUNNING` → `COMPLETED` / `FAILED` / `TIMEOUT` / `CANCELLED`

**Execution config**: 10 concurrent runs, 600s default timeout (overridable per agent).

### Interactive Sessions (SSE)

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/api/sessions` | Create session | 201 |
| POST | `/api/sessions/:id/messages` | Send message | 200 |
| POST | `/api/sessions/:id/approve` | Approve/deny tool call | 200 |
| POST | `/api/sessions/:id/cancel` | Cancel current turn | 200 |
| GET | `/api/sessions/:id/history` | Get message history | 200 |
| GET | `/api/sessions/:id/status` | Get session status | 200 |
| POST | `/api/sessions/:id/generate-agent-draft` | Generate agent from session | 200 |
| DELETE | `/api/sessions/:id` | Close session | 200 |
| PUT | `/api/sessions/events?sessionId=xxx` | SSE event stream | - |

**SSE Event Types**:

| Event | Description |
|-------|-------------|
| `text_chunk` | Streaming text output |
| `reasoning_chunk` | Reasoning/thinking content |
| `reasoning_complete` | Reasoning finished |
| `tool_start` | Tool execution started |
| `tool_result` | Tool execution completed |
| `tool_approval_request` | Tool needs user approval |
| `turn_complete` | Agent turn finished |
| `error` | Error occurred |
| `status_change` | Session status changed (IDLE/RUNNING/ERROR) |

**Approval decisions**: `APPROVE`, `APPROVE_ALWAYS`, `DENY`, `DENY_ALWAYS`

### Schedules

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/api/schedules` | Create schedule | 201 |
| GET | `/api/schedules/agent/:agentId/list` | List schedules | 200 |
| PUT | `/api/schedules/:id` | Update schedule | 200 |
| DELETE | `/api/schedules/:id` | Delete schedule | 200 |

**Cron format**: Standard 5-field `minute hour dayOfMonth month dayOfWeek`

```
# Examples
*/15 * * * *      Every 15 minutes
0 9 * * 1-5       Weekdays at 9:00
0 0 1 * *         First day of month at midnight
30 */2 * * *      Every 2 hours at :30
```

**Concurrency policies**: `SKIP` (skip if already running), `PARALLEL` (allow concurrent)

### Tools

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| GET | `/api/tools` | List tools | 200 |
| GET | `/api/tools/categories` | List categories | 200 |
| GET | `/api/tools/:id` | Get tool detail | 200 |
| POST | `/api/tools/mcp-servers` | Register MCP server | 201 |
| PUT | `/api/tools/mcp-servers/:id` | Update MCP server | 200 |
| DELETE | `/api/tools/mcp-servers/:id` | Remove MCP server | 200 |
| PUT | `/api/tools/mcp-servers/:id/enable` | Enable MCP server | 200 |
| PUT | `/api/tools/mcp-servers/:id/disable` | Disable MCP server | 200 |

**Tool types**: `BUILTIN` (built-in tools), `MCP` (Model Context Protocol servers), `API` (external APIs)

### Webhooks

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/webhooks/:agentId` | Trigger agent via webhook |

**Authentication**: `Authorization: Bearer <webhookSecret>` (not the API key, uses agent-specific webhook secret)

**Input template**: Supports `{{payload}}` (full body) or `{{field}}` (JSON field extraction).

### Files

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| POST | `/api/files` | Upload file | 200 |
| GET | `/api/files/:id` | Get file metadata | 200 |
| GET | `/api/files/:id/content` | Download file content | 200 |
| DELETE | `/api/files/:id` | Delete file | 200 |

---

## Observability & Tracing

The server includes a built-in OTLP-compatible trace receiver, providing functionality similar to Langfuse/LangSmith.

### OTLP Ingestion

| Method | Path | Description |
|--------|------|-------------|
| POST | `/v1/traces` | Standard OTLP HTTP protobuf |
| POST | `/api/public/otel/v1/traces` | Langfuse-compatible path |
| POST | `/api/ingest/spans` | JSON format ingestion |

To send traces from core-ai to this server, configure `agent.properties`:

```properties
trace.otlp.endpoint=http://localhost:8080
```

Set `useLangfusePath=false` in `TelemetryConfig` when targeting this server directly via `/v1/traces`, or keep the default to use the Langfuse-compatible path.

### Trace Query APIs

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/traces?offset=0&limit=20` | List traces |
| GET | `/api/traces/:traceId` | Get trace detail |
| GET | `/api/traces/:traceId/spans` | Get spans (tree structure) |

### Prompt Template Management

| Method | Path | Description | Status |
|--------|------|-------------|--------|
| GET | `/api/prompts?offset=0&limit=20` | List prompts | 200 |
| POST | `/api/prompts` | Create prompt | 201 |
| GET | `/api/prompts/:id` | Get prompt | 200 |
| PUT | `/api/prompts/:id` | Update prompt (bumps version) | 200 |
| DELETE | `/api/prompts/:id` | Delete prompt | 200 |
| POST | `/api/prompts/:id/publish` | Publish current version | 200 |

**Prompt features**: Version tracking, `{{variable}}` template syntax, Draft/Published lifecycle, tag-based organization.

### Data Model

**Trace** — One agent execution session:
- `trace_id`, `name`, `status` (RUNNING/COMPLETED/ERROR)
- `input`, `output`, `total_tokens`, `duration_ms`
- `session_id`, `user_id` (links to server entities)

**Span** — Individual operation within a trace (tree structure via `parent_span_id`):
- Types: `LLM`, `AGENT`, `TOOL`, `FLOW`, `GROUP`
- `model`, `input`, `output`, `input_tokens`, `output_tokens`
- OpenTelemetry attributes preserved in `attributes` map

---

## Database

MongoDB collections:

| Collection | Description |
|------------|-------------|
| `users` | User accounts |
| `agents` | Agent definitions |
| `agent_runs` | Execution records with transcripts |
| `agent_schedules` | Cron schedules |
| `tool_registries` | Tool/MCP server registry |
| `files` | File metadata |
| `schema_versions` | Migration tracking |
| `traces` | Trace records |
| `spans` | Span records (tree structure) |
| `prompt_templates` | Prompt template versions |

---

## Frontend

The trace UI is located in `core-ai-server/frontend/` (React + TypeScript + Tailwind + Vite).

```bash
cd core-ai-server/frontend
npm install
npm run dev    # http://localhost:3000, proxies to :8080
```

Pages:
- **Traces** — Trace list with waterfall span visualization
- **Prompts** — Prompt template editor with variable extraction
- **Dashboard** — Token usage and status distribution charts
