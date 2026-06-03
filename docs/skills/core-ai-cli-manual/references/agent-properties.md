# agent.properties Reference

Complete reference for all configurable properties in `agent.properties`.

## File Locations

| Scope | Path |
|-------|------|
| Global | `~/.core-ai/agent.properties` |
| Workspace override | `<workspace>/.core-ai/agent.properties` |

Workspace values override global values. If a property is set in both files, the workspace value wins.

## Core Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `agent.max.turn` | int | `100` | Maximum turns per agent run |
| `active.provider` | String | auto-detected | Active LLM provider key (e.g. `litellm`, `openrouter`, `openai`, `deepseek`, `azure`) |
| `core.appName` | String | `core-ai-cli` | Application name |
| `username` | String | — | Username for the agent |

## Feature Gates

| Property | Interactive Default | Serve Default | ACP Default | Description |
|----------|-------------------|---------------|-------------|-------------|
| `agent.memory.enabled` | `false` | `true` | `false` | Enable memory extraction system |
| `agent.memory.daily.logs.enabled` | `false` | `false` | `false` | Enable daily logs (session-close extraction) |
| `agent.memory.prompt.extraction` | `false` | n/a | n/a | Enable prompt-level extraction triggers |
| `agent.coding.enabled` | `false` | `false` | `false` | Enable coding mode |
| `agent.todo.v2.enabled` | `false` | `false` | n/a | Enable todo v2 feature |

## Memory Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `agent.memory.enabled` | boolean | `false` | Master switch for memory system |
| `agent.memory.daily.logs.enabled` | boolean | `false` | Enable daily logs — when false, memory switches to direct wiki-only extraction |
| `agent.memory.prompt.extraction` | boolean | `false` | Enable incremental extraction after each prompt |
| `agent.memory.timezone` | string | system default | Timezone for memory timestamps (e.g. `Asia/Shanghai`, `America/New_York`) |

### Memory Mode Interaction

When `agent.memory.enabled=true` and `agent.memory.daily.logs.enabled=true`:
- Session close creates daily-logs → episodes → knowledge wiki pages (full 4-layer pipeline)

When `agent.memory.enabled=true` and `agent.memory.daily.logs.enabled=false`:
- Direct mode: knowledge extracted directly to wiki pages, no daily-logs/episodes

When `agent.memory.enabled=false`:
- Memory system is completely off

## LLM Provider Configuration

Providers are auto-detected when their `<provider>.api.key` is set. For `litellm`, `openai`, and `azure`, `<provider>.api.base` is also required.

### Base (Shared) Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `llm.model` | String | — | Default model name |
| `llm.model.multimodal` | String | — | Multimodal model name |
| `llm.temperature` | double | `0.7` | Default temperature |
| `llm.embeddings.model` | String | `text-embedding-3-large` | Embedding model |
| `llm.request.extra_body` | JSON | — | Extra body for API requests |
| `llm.timeout.seconds` | long | `300` | Request timeout |
| `llm.connect.timeout.seconds` | long | `3` | Connection timeout |
| `llm.stream.buffer.size` | int | `0` | Stream buffer size |

### Provider-Specific Properties

Replace `<provider>` with: `litellm`, `openrouter`, `openai`, `deepseek`, `azure`

| Property | Type | Required | Description |
|----------|------|----------|-------------|
| `<provider>.api.base` | String | Yes (litellm, openai, azure) | API base URL |
| `<provider>.api.key` | String | Yes (all) | API key |
| `<provider>.model` | String | No | Model override |
| `<provider>.model.multimodal` | String | No | Multimodal model override |
| `<provider>.temperature` | double | No | Temperature override |
| `<provider>.embeddings.model` | String | No | Embedding model override |
| `<provider>.request.extra_body` | JSON | No | Extra body override |
| `<provider>.timeout.seconds` | long | No | Timeout override |
| `<provider>.connect.timeout.seconds` | long | No | Connect timeout override |
| `<provider>.stream.buffer.size` | int | No | Stream buffer override |
| `<provider>.models` | CSV | No | Comma-separated model names for `/model` picker |

Hardcoded base URLs:
- `openrouter`: `https://openrouter.ai/api/v1`
- `deepseek`: `https://api.deepseek.com/v1`

## Sub-Agent Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `agent.sub.<name>.model` | String | — | Model for sub-agent `<name>` |
| `agent.sub.<name>.provider` | String | — | LLM provider for sub-agent `<name>` |

Sub-agents are auto-discovered from any property starting with `agent.sub.`. The `.<name>.` segment identifies the sub-agent.

## MCP Configuration

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `mcp.servers.json` | JSON | — | MCP server configurations (see [mcp.md](mcp.md)) |

## A2A Remote Agent Configuration

### Individual Remote Agents

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `a2a.remoteAgents` | CSV | — | Comma-separated agent IDs |
| `a2a.remoteAgents.<id>.enabled` | boolean | `true` | Enable agent |
| `a2a.remoteAgents.<id>.url` | String | required | Agent server URL |
| `a2a.remoteAgents.<id>.agentId` | String | — | Agent identifier |
| `a2a.remoteAgents.<id>.apiKey` | String | — | API key |
| `a2a.remoteAgents.<id>.apiKeyEnv` | String | — | Env var for API key |
| `a2a.remoteAgents.<id>.name` | String | auto | Tool name (alphanumeric + underscores, max 64) |
| `a2a.remoteAgents.<id>.displayName` | String | auto | Display name |
| `a2a.remoteAgents.<id>.description` | String | auto | Tool description |
| `a2a.remoteAgents.<id>.discoverable` | boolean | `false` | Agent discoverable |
| `a2a.remoteAgents.<id>.timeout` | String | `60s` | Timeout (`ms`/`s`/`m`/`h`) |
| `a2a.remoteAgents.<id>.contextPolicy` | String | `SESSION` | `SESSION` or `GLOBAL` |
| `a2a.remoteAgents.<id>.invocationMode` | String | `STREAM_BLOCKING` | Invocation mode |
| `a2a.remoteAgents.<id>.maxInputChars` | int | system default | Max input chars |
| `a2a.remoteAgents.<id>.maxOutputChars` | int | system default | Max output chars |

### Remote Servers (Discovery)

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `a2a.remoteServers` | CSV | — | Server IDs |
| `a2a.remoteServers.<id>.url` | String | required | Server URL |
| `a2a.remoteServers.<id>.apiKey` | String | — | API key |
| `a2a.remoteServers.<id>.apiKeyEnv` | String | — | Env var for API key |
| `a2a.remoteServers.<id>.discovery.enabled` | boolean | `true` | Enable discovery |
| `a2a.remoteServers.<id>.discovery.required` | boolean | `false` | Required discovery |
| `a2a.remoteServers.<id>.toolPrefix` | String | `<id>` | Tool name prefix |
| `a2a.remoteServers.<id>.includeAgents` | CSV | all | Agent whitelist |
| `a2a.remoteServers.<id>.excludeAgents` | CSV | — | Agent blacklist |
| `a2a.remoteServers.<id>.timeout` | String | `60s` | Timeout |
| `a2a.remoteServers.<id>.contextPolicy` | String | `SESSION` | Context policy |
| `a2a.remoteServers.<id>.invocationMode` | String | `STREAM_BLOCKING` | Invocation mode |

## System / Infrastructure

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `sys.redis.host` | String | — | Redis host (presence enables Redis persistence) |
| `sys.persistence.file.directory` | String | — | File persistence directory |
| `sys.milvus.uri` | String | — | Milvus vector store URI |
| `sys.milvus.token` | String | — | Milvus auth token |
| `sys.milvus.database` | String | — | Milvus database |
| `sys.milvus.username` | String | — | Milvus username |
| `sys.milvus.password` | String | — | Milvus password |
| `sys.hnswlib.path` | String | — | HnswLib vector store path |

## Telemetry / Tracing

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `trace.otlp.endpoint` | String | — | OTLP endpoint (set to `"local"` for local mode) |
| `trace.service.name` | String | `core-ai` | Service name |
| `trace.service.version` | String | `1.0.0` | Service version |
| `trace.environment` | String | `production` | Deployment environment |
| `trace.otlp.public.key` | String | — | OTLP public key |
| `trace.otlp.secret.key` | String | — | OTLP secret key |

## Langfuse Prompts

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `langfuse.prompt.base.url` | String | — | Langfuse base URL |
| `langfuse.prompt.public.key` | String | — | Langfuse public key |
| `langfuse.prompt.secret.key` | String | — | Langfuse secret key |
| `langfuse.prompt.timeout.seconds` | int | — | Langfuse timeout |
