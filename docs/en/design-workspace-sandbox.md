# Workspace & Sandbox Design

> Version: 1.0 | Date: 2026-03-24

## Overview

This document describes the design for **Workspace** (managed git repositories for agent analysis) and **Sandbox** (execution isolation layer for tools). The goal is to let server-side agents clone and analyze code repositories safely, while preserving the ability to add write/execute capabilities in the future.

---

## 1. Motivation

### Current State

- **core-ai-cli**: Tools operate directly on the local filesystem. The user's working directory is the workspace. No isolation needed — the user trusts the environment.
- **core-ai-server**: Tools also operate on the server's filesystem with no restrictions. There is no workspace concept — agents have no code repositories to analyze. There is no isolation — any file tool can read/write any path the process can access.

### Goals

1. Allow server-side agents to **clone and analyze git repositories** (read-only in v1).
2. Provide a **Sandbox abstraction** so tools are automatically isolated when running on the server, without agent awareness.
3. **Preserve the path to read-write + code execution** in a future version, with container-level isolation.

### Non-Goals (v1)

- Write access to workspace files.
- Docker/K8s container isolation.
- Per-user workspace access control (all users share all workspaces).

---

## 2. Core Concepts

### Workspace

A **Workspace** is a managed git repository clone available for agent analysis.

- **Scope**: Global — all agents and users can access all workspaces.
- **Lifecycle**: Persisted in MongoDB. Cloned to local disk. Manually created/refreshed/deleted via API.
- **Access mode**: Read-only (v1). The `WorkspaceMode` enum reserves `READ_WRITE` for future use.

### Sandbox

A **Sandbox** is an execution environment that intercepts tool calls and enforces isolation.

- **Scope**: Per-agent — injected via `ExecutionContext` at agent build time.
- **Transparency**: The agent does not know whether a sandbox is active. It calls the same tools (`read_file`, `run_bash_command`, etc.) regardless.
- **Decision point**: Whether tools go through sandbox depends on the **deployment environment**, not the agent's logic.

```
CLI agent   → ExecutionContext.sandbox = null  → tools execute directly
Server agent → ExecutionContext.sandbox = LocalSandbox → tools intercepted
```

---

## 3. Sandbox Architecture

### 3.1 Design Principle: Intercept at ToolExecutor, Not Inside Tools

Existing tools (`ReadFileTool`, `WriteFileTool`, `ShellCommandTool`, etc.) are **not modified**. Sandbox interception happens in `ToolExecutor.doExecute()`:

```
ToolExecutor.doExecute(functionCall, context)
  │
  ├── sandbox = context.getSandbox()
  │
  ├── if sandbox != null && sandbox.shouldIntercept(toolName):
  │     return sandbox.execute(toolName, arguments)    ← sandbox handles it
  │
  └── else:
        return tool.execute(arguments, context)         ← original behavior
```

**Why this approach:**
- Zero changes to existing tool implementations.
- Sandbox logic is centralized in one place.
- CLI is completely unaffected (sandbox is null → transparent pass-through).
- Swapping LocalSandbox for DockerSandbox requires zero tool changes.

### 3.2 Sandbox Interface

```java
package ai.core.sandbox;

public interface Sandbox {
    /**
     * Unique identifier for this sandbox instance.
     */
    String id();

    /**
     * Whether this sandbox should intercept the given tool.
     * Non-intercepted tools execute normally via the original ToolCall.
     */
    boolean shouldIntercept(String toolName);

    /**
     * Execute a tool call within the sandbox.
     * Called only when shouldIntercept() returns true.
     *
     * @param toolName   the tool name (e.g. "read_file", "run_bash_command")
     * @param arguments  the raw JSON arguments from the LLM
     * @return tool execution result
     */
    ToolCallResult execute(String toolName, String arguments);

    /**
     * Release resources held by this sandbox.
     */
    void cleanup();
}
```

### 3.3 SandboxProvider Interface

```java
package ai.core.sandbox;

public interface SandboxProvider {
    /**
     * Acquire a sandbox for the given workspace.
     */
    Sandbox acquire(String workspaceId);

    /**
     * Release a sandbox by ID.
     */
    void release(String sandboxId);

    /**
     * Shutdown all sandboxes. Called on application shutdown.
     */
    void shutdown();
}
```

### 3.4 LocalSandbox (v1 Implementation)

`LocalSandbox` is a thin layer that:
1. Validates all file paths are within the workspace root (prevents path traversal).
2. Delegates to existing tool static methods (`ShellCommandTool.exec()`, `Files.readString()`, etc.).
3. In read-only mode, rejects write operations (`write_file`, `edit_file`).

```java
public class LocalSandbox implements Sandbox {
    private final String id;
    private final Path rootPath;          // workspace root directory
    private final boolean readOnly;       // true in v1

    // Intercepted tools
    private static final Set<String> INTERCEPTED_TOOLS = Set.of(
        "read_file", "write_file", "edit_file",
        "glob_file", "grep_file", "run_bash_command"
    );

    @Override
    public boolean shouldIntercept(String toolName) {
        return INTERCEPTED_TOOLS.contains(toolName);
    }

    @Override
    public ToolCallResult execute(String toolName, String arguments) {
        // Parse arguments, validate paths, delegate to existing tool logic
        // For write tools in read-only mode: return ToolCallResult.failed("read-only workspace")
        // For read tools: validate path within rootPath, then execute
        // For shell: set workDir to rootPath, execute
    }

    private Path validatePath(String path) {
        Path resolved = rootPath.resolve(path).normalize();
        if (!resolved.startsWith(rootPath)) {
            throw new SandboxException("path traversal denied: " + path);
        }
        return resolved;
    }
}
```

```java
public class LocalSandboxProvider implements SandboxProvider {
    private final Path baseDir;  // e.g. data/workspaces/
    private final Map<String, LocalSandbox> sandboxes = new ConcurrentHashMap<>();

    @Override
    public Sandbox acquire(String workspaceId) {
        return sandboxes.computeIfAbsent(workspaceId, id -> {
            Path workspacePath = baseDir.resolve(id);
            if (!Files.isDirectory(workspacePath)) {
                throw new SandboxException("workspace not found: " + id);
            }
            return new LocalSandbox(id, workspacePath, true);  // readOnly=true
        });
    }

    @Override
    public void release(String sandboxId) {
        // LocalSandbox is lightweight, no-op for release
    }

    @Override
    public void shutdown() {
        sandboxes.clear();
    }
}
```

### 3.5 Future: DockerSandbox

When write + execute capability is needed:

```java
public class DockerSandbox implements Sandbox {
    private final String containerId;
    private final DockerClient docker;

    // shouldIntercept → same tool set
    // execute → translates tool calls to:
    //   read_file  → docker exec cat {path}
    //   write_file → docker cp or docker exec tee
    //   shell      → docker exec sh -c "{command}"
    // cleanup → docker rm -f {containerId}
}

public class DockerSandboxProvider implements SandboxProvider {
    // acquire → docker run -d -v workspaces/{id}:/workspace {image}
    // release → docker rm -f
}
```

The agent code, tool code, and ToolExecutor code remain unchanged. Only the `SandboxProvider` implementation is swapped via configuration.

---

## 4. Workspace Management

### 4.1 Data Model

```java
// MongoDB collection: workspaces
public class Workspace {
    public String id;
    public String name;              // display name, e.g. "core-ng-project"
    public String repoUrl;           // git@github.com:xxx/yyy.git
    public String branch;            // default: "main"
    public String deployKey;         // SSH private key content (nullable)
    public String localPath;         // clone location, e.g. "data/workspaces/{id}"
    public WorkspaceStatus status;   // PENDING, CLONING, READY, FAILED
    public String errorMessage;      // error details if FAILED
    public ZonedDateTime createdAt;
    public ZonedDateTime lastRefreshedAt;
}

public enum WorkspaceStatus {
    PENDING, CLONING, READY, FAILED
}

public enum WorkspaceMode {
    READ_ONLY,    // v1: agent-level shared, no write access
    READ_WRITE    // future: run-level isolation via git worktree + container
}
```

### 4.2 WorkspaceService

```java
public class WorkspaceService {
    /**
     * Create a new workspace: store in DB, trigger async clone.
     */
    public String create(String name, String repoUrl, String branch, String deployKey);

    /**
     * Refresh workspace: git fetch + reset to latest.
     */
    public void refresh(String workspaceId);

    /**
     * Delete workspace: remove from DB and disk.
     */
    public void delete(String workspaceId);

    /**
     * List all workspaces with READY status.
     */
    public List<Workspace> listReady();

    /**
     * Get workspace by ID.
     */
    public Workspace get(String workspaceId);
}
```

### 4.3 Git Operations

**Clone** (first time):
```
git clone --depth 1 --branch {branch} {repoUrl} {localPath}
```

**Refresh** (subsequent):
```
cd {localPath} && git fetch origin {branch} && git reset --hard origin/{branch}
```

**Deploy key handling**:
- If `deployKey` is provided, write to a temp file and use `GIT_SSH_COMMAND`:
  ```
  GIT_SSH_COMMAND="ssh -i /tmp/{workspaceId}_key -o StrictHostKeyChecking=no"
  ```
- If `deployKey` is null, use the server's default SSH configuration.

### 4.4 REST API

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/workspaces` | Create workspace (clone repo) |
| `GET` | `/api/workspaces` | List all workspaces |
| `GET` | `/api/workspaces/:id` | Get workspace details |
| `PUT` | `/api/workspaces/:id/refresh` | Pull latest from remote |
| `DELETE` | `/api/workspaces/:id` | Delete workspace and local files |

### 4.5 Deploy Key Security

- Deploy keys are stored in MongoDB. If the database is compromised, all repo access is exposed.
- **Acceptable for v1** (single-team deployment). For multi-tenant, migrate to encrypted storage or a secrets vault.
- Deploy keys on GitHub/GitLab are **read-only by default**, which matches our read-only workspace model.

---

## 5. Integration: How Agents Access Workspaces

### 5.1 Server-Side Agent Build

When building an agent (in `AgentRunner` or `AgentSessionManager`), inject all READY workspaces into the execution context:

```java
// AgentRunner.buildAgent / AgentSessionManager.buildAgent
var workspaces = workspaceService.listReady();
var sandboxProvider = new LocalSandboxProvider(WORKSPACES_BASE_DIR);

var contextBuilder = ExecutionContext.builder().userId(userId);

if (!workspaces.isEmpty()) {
    // Inject workspace metadata for system prompt
    contextBuilder.customVariable("workspaces", workspaces.stream()
        .map(w -> Map.of("name", w.name, "id", w.id, "path", w.localPath))
        .toList());

    // Acquire sandbox for the first/default workspace
    // (or let the agent choose via tool parameters)
    var sandbox = sandboxProvider.acquire(workspaces.getFirst().id);
    contextBuilder.sandbox(sandbox);
}

agent.setExecutionContext(contextBuilder.build());
```

### 5.2 System Prompt Injection

The agent's system prompt includes available workspace information:

```
You have access to the following code repositories (read-only):
- core-ng-project: /data/workspaces/abc123
- core-ai: /data/workspaces/def456

Use read_file, glob_file, grep_file, and run_bash_command to explore these repositories.
All file operations on workspace paths are automatically sandboxed.
```

### 5.3 CLI — No Change

CLI agents do not set `sandbox` on ExecutionContext. All tools execute directly as before. Zero impact on CLI behavior.

---

## 6. ToolExecutor Change

The only code change in the core framework:

```java
// ToolExecutor.doExecute — add ~5 lines
private ToolCallResult doExecute(FunctionCall functionCall, ExecutionContext context) {
    var tool = findTool(functionCall);

    // --- Sandbox interception (new) ---
    var sandbox = context.getSandbox();
    if (sandbox != null && sandbox.shouldIntercept(tool.getName())) {
        return sandbox.execute(tool.getName(), functionCall.function.arguments);
    }
    // --- End sandbox interception ---

    // Original logic unchanged
    if (Boolean.TRUE.equals(tool.isNeedAuth()) && !authenticated) { ... }
    return executeWithTimeout(tool, functionCall, context);
}
```

---

## 7. Phased Implementation

### Phase 1 — Read-Only Workspaces (Current)

| Component | Status |
|-----------|--------|
| `Sandbox` interface | New |
| `SandboxProvider` interface | New |
| `LocalSandbox` implementation | New |
| `LocalSandboxProvider` implementation | New |
| `ExecutionContext.sandbox` field | New |
| `ToolExecutor` sandbox interception | Modify (~5 lines) |
| `Workspace` domain + MongoDB | New |
| `WorkspaceService` (CRUD + clone/pull) | New |
| Workspace REST API | New |
| `AgentRunner` / `AgentSessionManager` workspace injection | Modify |

### Phase 2 — Read-Write Workspaces (Future)

| Component | Status |
|-----------|--------|
| `WorkspaceMode.READ_WRITE` | Enable |
| `LocalSandbox.readOnly = false` | Config change |
| Per-run workspace via `git worktree` | New |
| Run-level workspace cleanup | New |

### Phase 3 — Container Isolation (Future)

| Component | Status |
|-----------|--------|
| `DockerSandbox` implementation | New |
| `DockerSandboxProvider` implementation | New |
| Sandbox image (with tool HTTP API) | New |
| Configuration to switch provider | New |