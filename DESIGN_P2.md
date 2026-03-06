# P2 Development Design Document

> Version: 1.0 | Branch: vidingcode | Date: 2026-03-06

This document provides implementation-level guidance for all 9 P2 items. Each section is self-contained.

---

## P2-13: Hooks Plugin Mechanism

### Problem

No way for users to run custom shell commands in response to tool execution events. Users cannot integrate external linters, formatters, notification scripts, or logging pipelines into the agent workflow without modifying Java code.

### Current State

- `AbstractLifecycle` provides 9 hook points (beforeAgentBuild, afterAgentBuild, beforeModel, afterModel, beforeAgentRun, afterAgentRun, afterAgentFailed, beforeTool, afterTool)
- `ToolExecutor` calls `beforeTool()` / `afterTool()` on all registered lifecycles
- `ServerPermissionLifecycle` dispatches `AgentEvent` to listeners — existing event infrastructure
- No user-facing hook configuration mechanism

### Design

#### 13.1 Hook configuration format

Users configure hooks in `.core-ai/hooks.json` (project-level) or `~/.core-ai/hooks.json` (user-level):

```json
{
  "hooks": {
    "beforeTool": [
      {
        "matcher": "edit_file|write_file",
        "command": "echo 'Modifying file: ${filePath}'",
        "timeout": 5
      }
    ],
    "afterTool": [
      {
        "matcher": "edit_file|write_file",
        "command": "prettier --write ${filePath}",
        "timeout": 10
      },
      {
        "matcher": "run_bash_command",
        "command": "notify-send 'Command completed'",
        "timeout": 5
      }
    ],
    "afterAgentRun": [
      {
        "command": "say 'Task completed'",
        "timeout": 5
      }
    ]
  }
}
```

#### 13.2 HookConfig model

```java
package ai.core.hook;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class HookConfig {
    public record HookEntry(String matcher, String command, int timeout) {
        public boolean matches(String toolName) {
            if (matcher == null || matcher.isBlank()) return true;
            return Pattern.matches(matcher, toolName);
        }

        public int timeoutSeconds() {
            return timeout > 0 ? timeout : 5;
        }
    }

    private Map<String, List<HookEntry>> hooks = Map.of();

    public List<HookEntry> getHooks(String event) {
        return hooks.getOrDefault(event, List.of());
    }

    public boolean isEmpty() {
        return hooks.isEmpty() || hooks.values().stream().allMatch(List::isEmpty);
    }
}
```

#### 13.3 HookExecutor

```java
package ai.core.hook;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class HookExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HookExecutor.class);

    private final Path workingDirectory;

    public HookExecutor(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
    }

    public record HookResult(int exitCode, String output, boolean timedOut) {}

    public HookResult execute(HookConfig.HookEntry hook, Map<String, String> variables) {
        String expandedCommand = expandVariables(hook.command(), variables);

        try {
            var pb = new ProcessBuilder("sh", "-c", expandedCommand)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true);

            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(hook.timeoutSeconds(), TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                LOGGER.warn("Hook timed out after {}s: {}", hook.timeoutSeconds(), expandedCommand);
                return new HookResult(-1, output, true);
            }

            return new HookResult(process.exitValue(), output.strip(), false);
        } catch (Exception e) {
            LOGGER.warn("Hook execution failed: {}", expandedCommand, e);
            return new HookResult(-1, e.getMessage(), false);
        }
    }

    private String expandVariables(String command, Map<String, String> variables) {
        String result = command;
        for (var entry : variables.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
}
```

#### 13.4 HookLifecycle

```java
package ai.core.hook;

public class HookLifecycle extends AbstractLifecycle {
    private final HookConfig config;
    private final HookExecutor executor;

    public HookLifecycle(HookConfig config, Path workingDirectory) {
        this.config = config;
        this.executor = new HookExecutor(workingDirectory);
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext context) {
        runHooks("beforeTool", functionCall.function.name, Map.of(
            "toolName", functionCall.function.name,
            "arguments", functionCall.function.arguments != null ? functionCall.function.arguments : ""
        ));
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext context, ToolCallResult result) {
        var variables = new HashMap<String, String>();
        variables.put("toolName", functionCall.function.name);
        variables.put("status", result.isCompleted() ? "success" : "failed");

        // Extract filePath from stats if available
        Object filePath = result.getStats().get("filePath");
        if (filePath != null) variables.put("filePath", filePath.toString());

        var hookResults = runHooks("afterTool", functionCall.function.name, variables);

        // Append non-empty hook output to tool result
        String hookOutput = hookResults.stream()
            .filter(r -> r.exitCode() == 0 && !r.output().isBlank())
            .map(HookExecutor.HookResult::output)
            .collect(Collectors.joining("\n"));

        if (!hookOutput.isBlank() && result.isCompleted()) {
            result.withResult(result.getResult() + "\n\n[Hook output]\n" + hookOutput);
        }
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext context) {
        runHooks("afterAgentRun", "", Map.of(
            "sessionId", context.getSessionId() != null ? context.getSessionId() : ""
        ));
    }

    private List<HookExecutor.HookResult> runHooks(String event, String toolName, Map<String, String> vars) {
        var results = new ArrayList<HookExecutor.HookResult>();
        for (var hook : config.getHooks(event)) {
            if (hook.matches(toolName)) {
                results.add(executor.execute(hook, vars));
            }
        }
        return results;
    }
}
```

#### 13.5 HookConfig loading and registration

```java
// In HookConfigLoader utility
public class HookConfigLoader {
    public static HookConfig load(Path workspace) {
        // Project-level overrides user-level
        Path projectHooks = workspace.resolve(".core-ai/hooks.json");
        Path userHooks = Path.of(System.getProperty("user.home"), ".core-ai", "hooks.json");

        HookConfig config = new HookConfig();
        if (Files.exists(userHooks)) {
            config = JsonUtil.fromJson(HookConfig.class, Files.readString(userHooks));
        }
        if (Files.exists(projectHooks)) {
            var projectConfig = JsonUtil.fromJson(HookConfig.class, Files.readString(projectHooks));
            config = merge(config, projectConfig); // project hooks append to user hooks
        }
        return config;
    }
}

// In AgentBuilder or CliAgent registration:
var hookConfig = HookConfigLoader.load(workspace);
if (!hookConfig.isEmpty()) {
    builder.addAgentLifecycle(new HookLifecycle(hookConfig, workspace));
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../hook/HookConfig.java` | **New** — hook configuration model |
| `core-ai/src/.../hook/HookExecutor.java` | **New** — shell command executor |
| `core-ai/src/.../hook/HookLifecycle.java` | **New** — lifecycle integration |
| `core-ai/src/.../hook/HookConfigLoader.java` | **New** — config loading with merge |
| `core-ai-cli/src/.../agent/CliAgent.java` | Register HookLifecycle from workspace config |

### Verification

1. Configure `afterTool` hook with `prettier --write ${filePath}` for `edit_file` → formatter runs after each edit
2. Hook with invalid command → warning logged, tool execution continues
3. Hook exceeds timeout → killed, warning logged
4. No hooks configured → no overhead, lifecycle not registered
5. Project hooks merge with user hooks → both execute

---

## P2-14: Multi-layer Config System

### Problem

Configuration is scattered: `~/.core-ai/agent.properties` for LLM keys, workspace `.core-ai/` for skills and permissions, hardcoded defaults in `AgentBootstrap`. No unified priority system. No hot reload.

### Current State

- `PropertiesFileSource.fromFile(path)` reads a single properties file
- `AgentBootstrap.initialize()` reads one PropertySource for all providers
- `CliApp` hardcodes `~/.core-ai/agent.properties` as the config path
- Workspace-specific config (skills, permissions) handled separately in `CliAgent`
- MCP servers configured via `mcp.servers.json` property in the same file

### Design

#### 14.1 Layered PropertySource

```java
package ai.core.config;

import java.util.*;

public class LayeredPropertySource implements PropertySource {
    private final List<PropertySource> layers; // ordered: highest priority first

    public LayeredPropertySource(List<PropertySource> layers) {
        this.layers = List.copyOf(layers);
    }

    @Override
    public Optional<String> property(String key) {
        for (var layer : layers) {
            var value = layer.property(key);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    public Map<String, String> allProperties() {
        var merged = new LinkedHashMap<String, String>();
        // Reverse order: lowest priority first, overwritten by higher
        for (int i = layers.size() - 1; i >= 0; i--) {
            if (layers.get(i) instanceof PropertiesFileSource pfs) {
                merged.putAll(pfs.toMap());
            }
        }
        return merged;
    }
}
```

#### 14.2 Configuration priority chain

Three levels, highest priority first:

```
1. Environment variables     (ENV_VAR format: CORE_AI_OPENROUTER_API_KEY)
2. Project-level config      (.core-ai/config.properties in workspace)
3. User-level config         (~/.core-ai/agent.properties)
4. Default values            (hardcoded in AgentBootstrap)
```

```java
package ai.core.config;

public class ConfigLoader {
    public static LayeredPropertySource load(Path workspace) {
        var layers = new ArrayList<PropertySource>();

        // Highest priority: environment variables
        layers.add(new EnvironmentPropertySource());

        // Project-level config
        Path projectConfig = workspace.resolve(".core-ai/config.properties");
        if (Files.exists(projectConfig)) {
            layers.add(PropertiesFileSource.fromFile(projectConfig));
        }

        // User-level config
        Path userConfig = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");
        if (Files.exists(userConfig)) {
            layers.add(PropertiesFileSource.fromFile(userConfig));
        }

        return new LayeredPropertySource(layers);
    }
}
```

#### 14.3 EnvironmentPropertySource

```java
package ai.core.config;

public class EnvironmentPropertySource implements PropertySource {
    // Maps env var names to property names
    // CORE_AI_OPENROUTER_API_KEY → openrouter.api.key
    @Override
    public Optional<String> property(String key) {
        String envKey = "CORE_AI_" + key.toUpperCase().replace('.', '_');
        String value = System.getenv(envKey);
        return Optional.ofNullable(value);
    }
}
```

#### 14.4 Integration in CliApp

Replace single-file loading with layered loading:

```java
// In CliApp.run(), replace:
//   var properties = PropertiesFileSource.fromFile(configPath);
// With:
var properties = ConfigLoader.load(workspace);
var bootstrap = new AgentBootstrap(properties);
bootstrap.initialize();
```

#### 14.5 Project-level config file

`.core-ai/config.properties` allows per-project overrides:

```properties
# Override model for this project
openrouter.model=anthropic/claude-sonnet-4
agent.max.turn=50

# Project-specific MCP servers
mcp.servers.json={"db": {"command": "npx", "args": ["-y", "@modelcontextprotocol/server-postgres"]}}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../config/LayeredPropertySource.java` | **New** — priority-based property lookup |
| `core-ai/src/.../config/EnvironmentPropertySource.java` | **New** — env var adapter |
| `core-ai/src/.../config/ConfigLoader.java` | **New** — assembles layered config |
| `core-ai-cli/src/.../CliApp.java` | Replace single PropertySource with `ConfigLoader.load()` |

### Verification

1. Set `CORE_AI_OPENROUTER_API_KEY` env var → overrides file config
2. Create `.core-ai/config.properties` with model override → project uses that model
3. Remove project config → falls back to user-level `~/.core-ai/agent.properties`
4. Property in both project and user config → project wins

---

## P2-15: Snapshot/Revert System

### Problem

Agent file modifications (edit_file, write_file) are irreversible. If the agent introduces a bug or corrupts a file, no built-in mechanism exists to revert to the pre-modification state. Users must rely on external git history.

### Current State

- `EditFileTool` and `WriteFileTool` write directly to disk with no pre-state capture
- `AbstractLifecycle.beforeTool()` is called before tool execution — ideal snapshot point
- `PersistenceProvider` supports key-value storage (`save(id, content)` / `load(id)`)
- `WriteTodosTool` demonstrates persistence pattern via `ExecutionContext.getPersistenceProvider()`
- No snapshot, backup, or revert mechanism exists

### Design

#### 15.1 SnapshotStore

```java
package ai.core.snapshot;

import java.nio.file.*;
import java.util.*;

public class SnapshotStore {
    private final Path snapshotDir;
    private final List<Snapshot> snapshots = new ArrayList<>();

    public record Snapshot(
        String id,
        long timestamp,
        String toolName,
        Map<String, String> filesBefore  // filePath → content before modification
    ) {}

    public SnapshotStore(Path workspace, String sessionId) {
        this.snapshotDir = workspace.resolve(".core-ai/snapshots/" + sessionId);
    }

    public Snapshot capture(String toolName, List<String> filePaths) {
        var filesBefore = new LinkedHashMap<String, String>();
        for (String path : filePaths) {
            Path file = Path.of(path);
            if (Files.exists(file) && Files.isRegularFile(file)) {
                try {
                    filesBefore.put(path, Files.readString(file));
                } catch (IOException e) {
                    LOGGER.warn("Failed to snapshot file: {}", path, e);
                }
            } else {
                filesBefore.put(path, null); // file did not exist
            }
        }

        var snapshot = new Snapshot(
            UUID.randomUUID().toString().substring(0, 8),
            System.currentTimeMillis(),
            toolName,
            filesBefore
        );
        snapshots.add(snapshot);
        persist(snapshot);
        return snapshot;
    }

    public boolean revert(String snapshotId) {
        var snapshot = snapshots.stream()
            .filter(s -> s.id().equals(snapshotId))
            .findFirst()
            .orElse(null);
        if (snapshot == null) return false;

        for (var entry : snapshot.filesBefore().entrySet()) {
            try {
                if (entry.getValue() == null) {
                    Files.deleteIfExists(Path.of(entry.getKey()));
                } else {
                    Files.writeString(Path.of(entry.getKey()), entry.getValue());
                }
            } catch (IOException e) {
                LOGGER.error("Failed to revert file: {}", entry.getKey(), e);
                return false;
            }
        }

        // Remove snapshots after the reverted one (they are now invalid)
        int idx = snapshots.indexOf(snapshot);
        if (idx >= 0) {
            snapshots.subList(idx, snapshots.size()).clear();
        }
        return true;
    }

    public List<Snapshot> list() {
        return List.copyOf(snapshots);
    }

    public Snapshot latest() {
        return snapshots.isEmpty() ? null : snapshots.getLast();
    }

    private void persist(Snapshot snapshot) {
        try {
            Files.createDirectories(snapshotDir);
            Path file = snapshotDir.resolve(snapshot.id() + ".json");
            Files.writeString(file, JsonUtil.toJson(snapshot));
        } catch (IOException e) {
            LOGGER.warn("Failed to persist snapshot: {}", snapshot.id(), e);
        }
    }

    // GC: remove snapshots older than maxAge
    public void gc(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        snapshots.removeIf(s -> s.timestamp() < cutoff);
        // Also clean files
        try (var stream = Files.list(snapshotDir)) {
            stream.forEach(f -> {
                try {
                    if (Files.getLastModifiedTime(f).toMillis() < cutoff) {
                        Files.delete(f);
                    }
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
```

#### 15.2 SnapshotLifecycle

```java
package ai.core.snapshot;

public class SnapshotLifecycle extends AbstractLifecycle {
    private static final Set<String> FILE_MODIFY_TOOLS = Set.of("edit_file", "write_file");

    private final SnapshotStore store;

    public SnapshotLifecycle(SnapshotStore store) {
        this.store = store;
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext context) {
        String toolName = functionCall.function.name;
        if (!FILE_MODIFY_TOOLS.contains(toolName)) return;

        // Extract file path from arguments
        String filePath = extractFilePath(functionCall.function.arguments);
        if (filePath != null) {
            store.capture(toolName, List.of(filePath));
        }
    }

    private String extractFilePath(String arguments) {
        if (arguments == null) return null;
        try {
            var args = JsonUtil.fromJson(Map.class, arguments);
            return (String) args.get("file_path");
        } catch (Exception e) {
            return null;
        }
    }
}
```

#### 15.3 RevertTool

```java
package ai.core.tool.tools;

public class RevertTool extends ToolCall {
    public static final String TOOL_NAME = "revert_snapshot";

    private final SnapshotStore snapshotStore;

    @CoreAiMethod(name = TOOL_NAME, description = "Revert file changes to a previous snapshot. "
        + "Use list_snapshots to see available snapshots, then revert by ID.")
    public String revert(
        @CoreAiParameter(name = "action", description = "list | revert | revert_latest") String action,
        @CoreAiParameter(name = "snapshot_id", description = "Snapshot ID to revert to", required = false) String snapshotId
    ) {
        return switch (action) {
            case "list" -> formatSnapshots(snapshotStore.list());
            case "revert_latest" -> {
                var latest = snapshotStore.latest();
                if (latest == null) yield "No snapshots available.";
                yield revertSnapshot(latest);
            }
            case "revert" -> {
                if (snapshotId == null) yield "Error: snapshot_id required for revert action.";
                var snapshot = snapshotStore.list().stream()
                    .filter(s -> s.id().equals(snapshotId)).findFirst().orElse(null);
                if (snapshot == null) yield "Error: snapshot '" + snapshotId + "' not found.";
                yield revertSnapshot(snapshot);
            }
            default -> "Error: unknown action '" + action + "'. Use: list, revert, revert_latest";
        };
    }

    private String revertSnapshot(SnapshotStore.Snapshot snapshot) {
        boolean success = snapshotStore.revert(snapshot.id());
        if (success) {
            return "Reverted to snapshot " + snapshot.id()
                + " (" + snapshot.toolName() + " at " + formatTime(snapshot.timestamp()) + ")"
                + "\nFiles restored: " + String.join(", ", snapshot.filesBefore().keySet());
        }
        return "Error: revert failed for snapshot " + snapshot.id();
    }

    private String formatSnapshots(List<SnapshotStore.Snapshot> snapshots) {
        if (snapshots.isEmpty()) return "No snapshots available.";
        var sb = new StringBuilder("Available snapshots:\n");
        for (var s : snapshots) {
            sb.append("  ").append(s.id())
              .append(" | ").append(formatTime(s.timestamp()))
              .append(" | ").append(s.toolName())
              .append(" | files: ").append(String.join(", ", s.filesBefore().keySet()))
              .append('\n');
        }
        return sb.toString();
    }
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../snapshot/SnapshotStore.java` | **New** — snapshot capture, revert, persistence, GC |
| `core-ai/src/.../snapshot/SnapshotLifecycle.java` | **New** — beforeTool hook for file tools |
| `core-ai/src/.../tool/tools/RevertTool.java` | **New** — list/revert snapshots as agent tool |
| `core-ai/src/.../agent/AgentBuilder.java` | Register SnapshotLifecycle, add RevertTool |
| `core-ai-cli/src/.../agent/CliAgent.java` | Create SnapshotStore with workspace/sessionId |

### Verification

1. Agent calls `edit_file` → snapshot captured before edit
2. Agent calls `revert_snapshot(action=list)` → shows available snapshots
3. Agent calls `revert_snapshot(action=revert_latest)` → file restored to pre-edit state
4. File that didn't exist before write_file → file deleted on revert
5. GC removes snapshots older than 24 hours

---

## P2-16: LSP Integration

### Problem

After file edits, the agent cannot detect type errors, missing imports, or syntax issues it introduced. Users must manually run compilers or linters and feed errors back to the agent.

### Current State

- `CompressionLifecycle.afterTool()` demonstrates the pattern: modify tool result after execution
- `EditFileTool` returns `filePath` in stats — available in afterTool hook
- `WriteFileTool` returns `filePath` in stats
- No LSP libraries in `build.gradle.kts`
- Test code references a planned `ai.core.lsp.LanguageServerModule` (not yet implemented)

### Design

#### 16.1 LSP client via subprocess

Rather than embedding a full LSP client library, use external language server processes and communicate via stdin/stdout JSON-RPC. Start simple: spawn a language server, send `textDocument/didOpen` + `textDocument/didChange`, receive `textDocument/publishDiagnostics`.

```java
package ai.core.lsp;

import java.nio.file.Path;
import java.util.*;

public class LanguageServerClient implements AutoCloseable {
    private Process process;
    private int requestId = 0;

    public record Diagnostic(int line, int character, String severity, String message, String source) {}

    public record ServerConfig(String language, String command, List<String> args) {}

    private static final Map<String, ServerConfig> DEFAULT_SERVERS = Map.of(
        ".ts",   new ServerConfig("typescript", "npx", List.of("typescript-language-server", "--stdio")),
        ".tsx",  new ServerConfig("typescript", "npx", List.of("typescript-language-server", "--stdio")),
        ".java", new ServerConfig("java", "jdtls", List.of()),
        ".py",   new ServerConfig("python", "pyright-langserver", List.of("--stdio"))
    );

    public static boolean isSupported(String filePath) {
        String ext = filePath.substring(filePath.lastIndexOf('.'));
        return DEFAULT_SERVERS.containsKey(ext);
    }

    public LanguageServerClient(ServerConfig config, Path workspaceRoot) {
        // Initialize LSP process and send initialize request
    }

    public List<Diagnostic> getDiagnostics(String filePath, String content) {
        // 1. Send textDocument/didOpen or textDocument/didChange
        // 2. Wait for textDocument/publishDiagnostics notification (with timeout)
        // 3. Parse and return diagnostics
        return List.of();
    }

    @Override
    public void close() {
        if (process != null && process.isAlive()) {
            // Send shutdown + exit requests
            process.destroyForcibly();
        }
    }
}
```

#### 16.2 LSPDiagnosticsLifecycle

```java
package ai.core.lsp;

public class LSPDiagnosticsLifecycle extends AbstractLifecycle {
    private static final Set<String> FILE_MODIFY_TOOLS = Set.of("edit_file", "write_file");
    private static final int MAX_DIAGNOSTICS = 10;

    private final Map<String, LanguageServerClient> clients = new HashMap<>();
    private final Path workspaceRoot;
    private final boolean enabled;

    public LSPDiagnosticsLifecycle(Path workspaceRoot, boolean enabled) {
        this.workspaceRoot = workspaceRoot;
        this.enabled = enabled;
    }

    @Override
    public void afterTool(FunctionCall functionCall, ExecutionContext context, ToolCallResult result) {
        if (!enabled || result == null || !result.isCompleted()) return;
        if (!FILE_MODIFY_TOOLS.contains(functionCall.function.name)) return;

        String filePath = (String) result.getStats().get("filePath");
        if (filePath == null || !LanguageServerClient.isSupported(filePath)) return;

        try {
            var client = getOrCreateClient(filePath);
            if (client == null) return;

            String content = Files.readString(Path.of(filePath));
            var diagnostics = client.getDiagnostics(filePath, content);

            if (!diagnostics.isEmpty()) {
                String report = formatDiagnostics(diagnostics, filePath);
                result.withResult(result.getResult() + "\n\n" + report);
                result.withStats("diagnosticCount", diagnostics.size());
            }
        } catch (Exception e) {
            LOGGER.debug("LSP diagnostics failed for {}: {}", filePath, e.getMessage());
        }
    }

    private String formatDiagnostics(List<LanguageServerClient.Diagnostic> diagnostics, String filePath) {
        var sb = new StringBuilder("<system-reminder>\n");
        sb.append("[LSP Diagnostics] ").append(diagnostics.size()).append(" issue(s) found in ").append(filePath).append(":\n");

        diagnostics.stream().limit(MAX_DIAGNOSTICS).forEach(d ->
            sb.append("  Line ").append(d.line())
              .append(":").append(d.character())
              .append(" [").append(d.severity()).append("] ")
              .append(d.message())
              .append(" (").append(d.source()).append(")\n")
        );

        if (diagnostics.size() > MAX_DIAGNOSTICS) {
            sb.append("  ... and ").append(diagnostics.size() - MAX_DIAGNOSTICS).append(" more\n");
        }
        sb.append("Fix the errors above before proceeding.\n</system-reminder>");
        return sb.toString();
    }

    private LanguageServerClient getOrCreateClient(String filePath) {
        String ext = filePath.substring(filePath.lastIndexOf('.'));
        return clients.computeIfAbsent(ext, e -> {
            try {
                var config = LanguageServerClient.DEFAULT_SERVERS.get(e);
                if (config == null) return null;
                return new LanguageServerClient(config, workspaceRoot);
            } catch (Exception ex) {
                LOGGER.warn("Failed to start LSP for {}: {}", e, ex.getMessage());
                return null;
            }
        });
    }
}
```

#### 16.3 Simpler alternative: compiler-based diagnostics

For projects where a full LSP is overkill, run compiler commands directly:

```java
package ai.core.lsp;

public class CompilerDiagnostics {
    private static final Map<String, String[]> COMPILER_COMMANDS = Map.of(
        ".ts",   new String[]{"npx", "tsc", "--noEmit", "--pretty"},
        ".java", new String[]{"javac", "-Xlint:all"},
        ".py",   new String[]{"python", "-m", "py_compile"}
    );

    public static String runDiagnostics(String filePath, Path workDir) {
        String ext = filePath.substring(filePath.lastIndexOf('.'));
        String[] cmd = COMPILER_COMMANDS.get(ext);
        if (cmd == null) return null;

        try {
            var pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);
            // Append file path as last argument
            var fullCmd = new ArrayList<>(List.of(cmd));
            fullCmd.add(filePath);
            pb.command(fullCmd);

            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return null;
            }
            return process.exitValue() != 0 ? output.strip() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../lsp/LanguageServerClient.java` | **New** — LSP subprocess client |
| `core-ai/src/.../lsp/LSPDiagnosticsLifecycle.java` | **New** — afterTool hook for diagnostics |
| `core-ai/src/.../lsp/CompilerDiagnostics.java` | **New** — simple compiler-based fallback |
| `core-ai/src/.../agent/AgentBuilder.java` | Register LSPDiagnosticsLifecycle |

### Verification

1. Edit a `.ts` file with type error → diagnostics appended to tool result
2. Edit a `.java` file with missing import → diagnostics show error line
3. LSP server not installed → fails silently, no diagnostics
4. Clean edit with no errors → no additional output
5. Large number of errors → truncated to 10

---

## P2-17: Git Deep Integration

### Problem

Git operations require going through `ShellCommandTool` with manual shell commands. No worktree support for multi-session isolation. No structured diff or commit assistance.

### Current State

- `ShellCommandTool` contains git workflow instructions in its description (lines 78-210) but no git-specific logic
- No dedicated git tools
- No worktree support in session management
- `InProcessAgentSession` creates one agent per session with shared workspace

### Design

#### 17.1 GitTool

```java
package ai.core.tool.tools;

public class GitTool extends ToolCall {
    public static final String TOOL_NAME = "git";

    private final Path workspaceRoot;

    @CoreAiMethod(name = TOOL_NAME, description = GIT_TOOL_DESC)
    public String git(
        @CoreAiParameter(name = "command", description = "Git subcommand: status, diff, log, commit, branch, stash")
            String command,
        @CoreAiParameter(name = "args", description = "Arguments for the git command", required = false)
            String args,
        @CoreAiParameter(name = "message", description = "Commit message (for commit command)", required = false)
            String message
    ) {
        return switch (command) {
            case "status" -> execGit("status", "--short");
            case "diff" -> execGitDiff(args);
            case "log" -> execGit("log", "--oneline", "-20", args != null ? args : "");
            case "commit" -> execGitCommit(message, args);
            case "branch" -> execGit("branch", args != null ? args : "");
            case "stash" -> execGit("stash", args != null ? args : "");
            default -> "Error: unknown git command '" + command
                + "'. Supported: status, diff, log, commit, branch, stash";
        };
    }

    private String execGitDiff(String args) {
        // Limit diff output to prevent context bloat
        String result = execGit("diff", "--stat", args != null ? args : "");
        if (result.lines().count() > 100) {
            return execGit("diff", "--stat", args != null ? args : "")
                + "\n\n[Diff output large. Use 'diff' with specific file path for details.]";
        }
        return execGit("diff", args != null ? args : "");
    }

    private String execGitCommit(String message, String files) {
        if (message == null || message.isBlank()) {
            return "Error: commit message is required.";
        }
        // Stage files if specified, otherwise stage all
        if (files != null && !files.isBlank()) {
            execGit("add", files);
        } else {
            execGit("add", "-A");
        }
        return execGit("commit", "-m", message);
    }

    private String execGit(String... args) {
        try {
            var cmd = new ArrayList<String>();
            cmd.add("git");
            for (String arg : args) {
                if (arg != null && !arg.isBlank()) cmd.add(arg);
            }
            var pb = new ProcessBuilder(cmd)
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            process.waitFor(30, TimeUnit.SECONDS);
            return output.strip();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static final String GIT_TOOL_DESC = """
        Execute git operations on the project repository.
        Supported commands:
        - status: Show working tree status
        - diff [path]: Show changes (default: all, or specify file path)
        - log [args]: Show recent commit history (last 20)
        - commit: Create a commit (requires message parameter, optional files to stage)
        - branch [args]: List or manage branches
        - stash [args]: Stash working changes
        """;
}
```

#### 17.2 WorktreeManager for session isolation

```java
package ai.core.git;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class WorktreeManager {
    private final Path repoRoot;
    private final Map<String, WorktreeInfo> activeWorktrees = new ConcurrentHashMap<>();

    public record WorktreeInfo(String sessionId, String branch, Path path) {}

    public WorktreeManager(Path repoRoot) {
        this.repoRoot = repoRoot;
    }

    public WorktreeInfo createForSession(String sessionId) {
        String branch = "session/" + sessionId;
        Path worktreePath = repoRoot.resolve(".core-ai/worktrees/" + sessionId);

        try {
            // Create branch from current HEAD
            execGit("branch", branch);
            // Create worktree
            execGit("worktree", "add", worktreePath.toString(), branch);

            var info = new WorktreeInfo(sessionId, branch, worktreePath);
            activeWorktrees.put(sessionId, info);
            return info;
        } catch (Exception e) {
            LOGGER.error("Failed to create worktree for session {}: {}", sessionId, e.getMessage());
            return null;
        }
    }

    public void removeForSession(String sessionId) {
        var info = activeWorktrees.remove(sessionId);
        if (info == null) return;

        try {
            execGit("worktree", "remove", info.path().toString(), "--force");
            execGit("branch", "-D", info.branch());
        } catch (Exception e) {
            LOGGER.warn("Failed to clean worktree for session {}: {}", sessionId, e.getMessage());
        }
    }

    public WorktreeInfo getWorktree(String sessionId) {
        return activeWorktrees.get(sessionId);
    }

    private String execGit(String... args) throws Exception {
        var cmd = new ArrayList<String>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        var process = new ProcessBuilder(cmd)
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes());
        int exit = process.waitFor();
        if (exit != 0) throw new RuntimeException("git " + args[0] + " failed: " + output);
        return output.strip();
    }
}
```

#### 17.3 GitStateLifecycle

Track git state and detect branch changes:

```java
package ai.core.git;

public class GitStateLifecycle extends AbstractLifecycle {
    private final Path workspaceRoot;
    private String lastKnownBranch;

    public GitStateLifecycle(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext context) {
        lastKnownBranch = getCurrentBranch();
        context.getCustomVariables().put("git.branch", lastKnownBranch);
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        String currentBranch = getCurrentBranch();
        if (lastKnownBranch != null && !lastKnownBranch.equals(currentBranch)) {
            // Inject branch change reminder
            String reminder = "\n<system-reminder>Git branch changed: "
                + lastKnownBranch + " → " + currentBranch + "</system-reminder>";
            appendToLastUserMessage(request, reminder);
            lastKnownBranch = currentBranch;
        }
    }

    private String getCurrentBranch() {
        try {
            var process = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD")
                .directory(workspaceRoot.toFile())
                .redirectErrorStream(true)
                .start();
            String output = new String(process.getInputStream().readAllBytes()).strip();
            process.waitFor(5, TimeUnit.SECONDS);
            return output;
        } catch (Exception e) {
            return null;
        }
    }

    private void appendToLastUserMessage(CompletionRequest request, String text) {
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            if (request.messages.get(i).role == RoleType.USER) {
                var msg = request.messages.get(i);
                msg.content = List.of(Content.of(msg.getTextContent() + text));
                break;
            }
        }
    }
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../tool/tools/GitTool.java` | **New** — structured git operations |
| `core-ai/src/.../git/WorktreeManager.java` | **New** — session worktree isolation |
| `core-ai/src/.../git/GitStateLifecycle.java` | **New** — branch tracking + change reminder |
| `core-ai/src/.../agent/AgentBuilder.java` | Register GitTool and GitStateLifecycle |
| `core-ai/src/.../session/InProcessAgentSession.java` | Create/cleanup worktree on session start/end |

### Verification

1. `git(command=status)` → shows working tree changes
2. `git(command=commit, message="fix bug")` → stages all and commits
3. `git(command=diff, args="src/Main.java")` → shows file diff
4. Branch changes during session → system-reminder injected
5. Multi-session with worktree → each session has isolated workspace
6. Session ends → worktree cleaned up

---

## P2-18: File Monitoring System

### Problem

If a user edits a file externally (in IDE) while the agent is working, the agent is unaware of the change. It may overwrite user changes or work with stale content.

### Current State

- No file monitoring exists in the codebase
- Java NIO `WatchService` is available in standard library
- `McpConnectionMonitor` demonstrates the async monitoring pattern: `ScheduledExecutorService` with periodic checks
- `ExecutionContext.customVariables` can store transient state per session

### Design

#### 18.1 FileWatcher

```java
package ai.core.monitor;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class FileWatcher implements AutoCloseable {
    private final Path watchRoot;
    private final WatchService watchService;
    private final Thread watchThread;
    private volatile boolean running = true;

    // Track agent-initiated modifications to distinguish from external changes
    private final Set<String> agentModifiedFiles = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> externalChanges = new ConcurrentHashMap<>();

    public FileWatcher(Path watchRoot) throws IOException {
        this.watchRoot = watchRoot;
        this.watchService = FileSystems.getDefault().newWatchService();
        registerRecursive(watchRoot);
        this.watchThread = Thread.ofVirtual().name("file-watcher").start(this::watchLoop);
    }

    public void markAgentModified(String filePath) {
        agentModifiedFiles.add(filePath);
    }

    public Map<String, Long> getAndClearExternalChanges() {
        var changes = new HashMap<>(externalChanges);
        externalChanges.clear();
        return changes;
    }

    public boolean hasExternalChanges() {
        return !externalChanges.isEmpty();
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(500, TimeUnit.MILLISECONDS);
                if (key == null) continue;

                Path dir = (Path) key.watchable();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) continue;

                    Path changed = dir.resolve((Path) event.context());
                    String absPath = changed.toAbsolutePath().normalize().toString();

                    // Skip hidden files and .core-ai directory
                    if (isIgnored(changed)) continue;

                    // Skip agent-initiated modifications
                    if (agentModifiedFiles.remove(absPath)) continue;

                    externalChanges.put(absPath, System.currentTimeMillis());
                }
                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
        }
    }

    private boolean isIgnored(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith(".") || name.equals("node_modules")
            || path.toString().contains(".core-ai/");
    }

    private void registerRecursive(Path root) throws IOException {
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), 3, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (isIgnored(dir)) return FileVisitResult.SKIP_SUBTREE;
                dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void close() {
        running = false;
        try {
            watchService.close();
            watchThread.join(2000);
        } catch (Exception ignored) {}
    }
}
```

#### 18.2 FileMonitorLifecycle

```java
package ai.core.monitor;

public class FileMonitorLifecycle extends AbstractLifecycle {
    private FileWatcher watcher;
    private final Path workspaceRoot;

    public FileMonitorLifecycle(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext context) {
        try {
            watcher = new FileWatcher(workspaceRoot);
            context.getCustomVariables().put("fileWatcher", watcher);
        } catch (IOException e) {
            LOGGER.warn("Failed to start file watcher: {}", e.getMessage());
        }
    }

    @Override
    public void beforeTool(FunctionCall functionCall, ExecutionContext context) {
        // Mark files that the agent is about to modify
        if (watcher != null && isFileModifyTool(functionCall.function.name)) {
            String filePath = extractFilePath(functionCall.function.arguments);
            if (filePath != null) {
                watcher.markAgentModified(filePath);
            }
        }
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        if (watcher == null || !watcher.hasExternalChanges()) return;

        var changes = watcher.getAndClearExternalChanges();
        if (changes.isEmpty()) return;

        // Inject reminder about external changes
        String fileList = changes.keySet().stream()
            .map(p -> "  - " + p)
            .collect(Collectors.joining("\n"));

        String reminder = "\n<system-reminder>\n"
            + "[File Monitor] External file changes detected:\n" + fileList
            + "\nThese files were modified outside the agent. "
            + "Re-read them before editing to avoid overwriting changes.\n"
            + "</system-reminder>";

        appendToLastUserMessage(request, reminder);
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext context) {
        cleanup();
    }

    @Override
    public void afterAgentFailed(String query, ExecutionContext context, Exception exception) {
        cleanup();
    }

    private void cleanup() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    private boolean isFileModifyTool(String name) {
        return "edit_file".equals(name) || "write_file".equals(name);
    }

    private String extractFilePath(String arguments) {
        if (arguments == null) return null;
        try {
            var args = JsonUtil.fromJson(Map.class, arguments);
            return (String) args.get("file_path");
        } catch (Exception e) { return null; }
    }

    private void appendToLastUserMessage(CompletionRequest request, String text) {
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            if (request.messages.get(i).role == RoleType.USER) {
                var msg = request.messages.get(i);
                msg.content = List.of(Content.of(msg.getTextContent() + text));
                break;
            }
        }
    }
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../monitor/FileWatcher.java` | **New** — NIO WatchService wrapper |
| `core-ai/src/.../monitor/FileMonitorLifecycle.java` | **New** — lifecycle hooks for monitoring |
| `core-ai/src/.../agent/AgentBuilder.java` | Register FileMonitorLifecycle |

### Verification

1. Agent running, user edits `Main.java` in IDE → system-reminder injected on next LLM turn
2. Agent edits file via edit_file → NOT reported as external change
3. User edits file in `.git/` → ignored
4. Session ends → watcher closed, no thread leak
5. WatchService init fails → warning logged, agent continues without monitoring

---

## P2-19: System Reminder Injection

### Problem

In multi-turn conversations, the LLM may lose track of important context changes: queued user messages, permission changes, environment state updates. No mechanism exists to inject contextual reminders before each LLM call.

### Current State

- `SkillLifecycle.beforeModel()` demonstrates injecting content into system messages — pattern to follow
- `Prompts.TOOL_DIRECT_RETURN_REMINDER_PROMPT` already uses `<system-reminder>` XML tags
- `ExecutionContext.customVariables` can hold transient state
- No dynamic reminder injection exists

### Design

#### 19.1 SystemReminderProvider interface

```java
package ai.core.reminder;

import java.util.List;

public interface SystemReminderProvider {
    List<String> getReminders(ExecutionContext context);
}
```

Multiple sources can provide reminders:

```java
// Examples of reminder providers:
public class QueuedMessageReminder implements SystemReminderProvider {
    public List<String> getReminders(ExecutionContext context) {
        var queued = context.getCustomVariable("queuedMessages", List.class);
        if (queued == null || queued.isEmpty()) return List.of();
        return List.of("User has queued " + queued.size() + " message(s) while you were working. "
            + "Finish your current task, then address them.");
    }
}

public class EnvironmentReminder implements SystemReminderProvider {
    public List<String> getReminders(ExecutionContext context) {
        var reminders = new ArrayList<String>();
        var branch = context.getCustomVariable("git.branch", String.class);
        if (branch != null) {
            reminders.add("Current git branch: " + branch);
        }
        return reminders;
    }
}
```

#### 19.2 SystemReminderLifecycle

```java
package ai.core.reminder;

public class SystemReminderLifecycle extends AbstractLifecycle {
    private final List<SystemReminderProvider> providers;
    private int turnCount = 0;

    public SystemReminderLifecycle(List<SystemReminderProvider> providers) {
        this.providers = providers;
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        turnCount++;
        // Only inject reminders after the first turn
        if (turnCount <= 1) return;

        var allReminders = new ArrayList<String>();
        for (var provider : providers) {
            allReminders.addAll(provider.getReminders(context));
        }

        if (allReminders.isEmpty()) return;

        String reminderBlock = "\n<system-reminder>\n"
            + String.join("\n", allReminders)
            + "\n</system-reminder>";

        // Append to the last USER message in the request
        for (int i = request.messages.size() - 1; i >= 0; i--) {
            var msg = request.messages.get(i);
            if (msg.role == RoleType.USER) {
                msg.content = List.of(Content.of(msg.getTextContent() + reminderBlock));
                break;
            }
        }
    }
}
```

#### 19.3 Registration

```java
// In AgentBuilder or CliAgent:
var reminderProviders = List.of(
    new QueuedMessageReminder(),
    new EnvironmentReminder()
);
builder.addAgentLifecycle(new SystemReminderLifecycle(reminderProviders));
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../reminder/SystemReminderProvider.java` | **New** — provider interface |
| `core-ai/src/.../reminder/SystemReminderLifecycle.java` | **New** — beforeModel injection |
| `core-ai/src/.../reminder/QueuedMessageReminder.java` | **New** — queued message detection |
| `core-ai/src/.../reminder/EnvironmentReminder.java` | **New** — environment state reminders |
| `core-ai/src/.../agent/AgentBuilder.java` | Register SystemReminderLifecycle |

### Verification

1. First turn → no reminder injected (turnCount=1)
2. Second turn with queued messages → reminder about pending messages
3. Turn with git branch info → branch name included
4. No reminders available → no `<system-reminder>` tag injected
5. Multiple providers → all reminders concatenated in single block

---

## P2-20: Structured Output Support

### Problem

When agents need to return structured data (JSON responses for API calls, classification results, extraction output), LLM may return malformed JSON. No mechanism to enforce output schema at the Agent level.

### Current State

- `ResponseFormat` class already exists with `of(Class<?>)` factory — generates JSON schema from Java class
- `CompletionRequest.responseFormat` field is wired in the factory method
- `LLMProvider.completionFormat(request, class)` already supports structured output parsing
- `Agent.handLLM()` (line 271) passes `null` for responseFormat — not exposed to agent builders

### Design

#### 20.1 Expose responseFormat in AgentBuilder

```java
// In AgentBuilder.java — add field and builder method
private ResponseFormat responseFormat;

public AgentBuilder responseFormat(ResponseFormat format) {
    this.responseFormat = format;
    return this;
}

public <T> AgentBuilder responseFormat(Class<T> clazz) {
    this.responseFormat = ResponseFormat.of(clazz);
    return this;
}
```

#### 20.2 Pass through Agent to CompletionRequest

```java
// In Agent.java — add field
ResponseFormat responseFormat;

// In Agent.handLLM() — replace null with agent's responseFormat:
var req = CompletionRequest.of(new CompletionRequest.CompletionRequestOptions(
    messages, tools, temperature, model, name,
    false,
    responseFormat,  // was: null
    reasoningEffort));
```

#### 20.3 Copy in AgentBuilder.copyValue()

```java
// In AgentBuilder.copyValue():
agent.responseFormat = this.responseFormat;
```

#### 20.4 StructuredOutputTool alternative

For cases where structured output is needed per-tool-call rather than per-agent, provide a dedicated tool:

```java
package ai.core.tool.tools;

public class StructuredOutputTool extends ToolCall {
    public static final String TOOL_NAME = "structured_output";

    @CoreAiMethod(name = TOOL_NAME, description = """
        Return structured data to the user in a specific format.
        Use this tool when the user requests data in a structured format (JSON, table, etc).
        The data field should contain valid JSON matching the requested schema.
        """)
    public String structuredOutput(
        @CoreAiParameter(name = "data", description = "Structured JSON data") String data,
        @CoreAiParameter(name = "schema_name", description = "Name/type of the data structure") String schemaName
    ) {
        // Validate JSON
        try {
            JsonUtil.fromJson(Map.class, data);
        } catch (Exception e) {
            return "Error: invalid JSON data: " + e.getMessage();
        }
        return data;
    }
}
```

Mark this tool with `directReturn=true` so the structured output is returned to the caller without further LLM processing.

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/AgentBuilder.java` | Add `responseFormat` field and builder method |
| `core-ai/src/.../agent/Agent.java` | Add `responseFormat` field, pass to CompletionRequest |
| `core-ai/src/.../tool/tools/StructuredOutputTool.java` | **New** — per-call structured output tool |

### Verification

1. `AgentBuilder.responseFormat(MyResponse.class)` → LLM always returns valid JSON matching schema
2. `LLMProvider.completionFormat()` still works (existing path unchanged)
3. `StructuredOutputTool` with valid JSON → returns data directly
4. `StructuredOutputTool` with invalid JSON → returns error message
5. Agent without responseFormat → behavior unchanged (null passed)

---

## P2-21: Session Sharing and Export

### Problem

Sessions cannot be exported for sharing, review, or archival. No way to generate a readable transcript of an agent conversation.

### Current State

- `AgentPersistence.serialization()` converts messages + status to JSON — internal format
- `Node.getMessages()` provides full message history
- `ToolCallResult` stats include metadata (filePath, duration, etc.)
- `StreamingMarkdownRenderer` in CLI renders markdown for display
- `InProcessAgentSession` has `save()` / `load()` via PersistenceProvider
- No export or share functionality

### Design

#### 21.1 SessionExporter

```java
package ai.core.session.export;

import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import java.util.*;

public class SessionExporter {

    public enum Format { MARKDOWN, JSON }

    public static String export(String sessionId, List<Message> messages, Format format) {
        return switch (format) {
            case MARKDOWN -> exportMarkdown(sessionId, messages);
            case JSON -> exportJson(sessionId, messages);
        };
    }

    private static String exportMarkdown(String sessionId, List<Message> messages) {
        var sb = new StringBuilder();
        sb.append("# Session: ").append(sessionId).append("\n\n");
        sb.append("> Exported at: ").append(java.time.LocalDateTime.now()).append("\n\n");
        sb.append("---\n\n");

        int turnNumber = 0;
        for (var msg : messages) {
            if (msg.role == RoleType.SYSTEM) continue; // skip system prompt

            if (msg.role == RoleType.USER) {
                turnNumber++;
                sb.append("## Turn ").append(turnNumber).append("\n\n");
                sb.append("### User\n\n");
                sb.append(msg.getTextContent()).append("\n\n");
            } else if (msg.role == RoleType.ASSISTANT) {
                sb.append("### Assistant\n\n");
                String content = msg.getTextContent();
                if (content != null && !content.isBlank()) {
                    sb.append(content).append("\n\n");
                }
                // Show tool calls
                if (msg.toolCalls != null && !msg.toolCalls.isEmpty()) {
                    for (var tc : msg.toolCalls) {
                        sb.append("**Tool Call**: `").append(tc.function.name).append("`\n");
                        if (tc.function.arguments != null) {
                            sb.append("```json\n").append(formatJson(tc.function.arguments)).append("\n```\n\n");
                        }
                    }
                }
            } else if (msg.role == RoleType.TOOL) {
                sb.append("**Tool Result** (`").append(msg.name != null ? msg.name : "unknown").append("`):\n");
                String result = msg.getTextContent();
                if (result != null) {
                    if (result.length() > 500) {
                        sb.append("```\n").append(result.substring(0, 500)).append("\n... [truncated]\n```\n\n");
                    } else {
                        sb.append("```\n").append(result).append("\n```\n\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    private static String exportJson(String sessionId, List<Message> messages) {
        var export = new SessionExportData(
            sessionId,
            System.currentTimeMillis(),
            messages.stream()
                .filter(m -> m.role != RoleType.SYSTEM)
                .map(SessionExporter::toExportMessage)
                .toList()
        );
        return JsonUtil.toJson(export);
    }

    public record SessionExportData(String sessionId, long exportedAt, List<ExportMessage> messages) {}

    public record ExportMessage(String role, String content, List<ToolCallInfo> toolCalls) {}

    public record ToolCallInfo(String name, String arguments) {}

    private static ExportMessage toExportMessage(Message msg) {
        List<ToolCallInfo> toolCalls = null;
        if (msg.toolCalls != null) {
            toolCalls = msg.toolCalls.stream()
                .map(tc -> new ToolCallInfo(tc.function.name, tc.function.arguments))
                .toList();
        }
        return new ExportMessage(msg.role.name(), msg.getTextContent(), toolCalls);
    }

    private static String formatJson(String json) {
        try {
            var obj = JsonUtil.fromJson(Map.class, json);
            return JsonUtil.toJson(obj); // pretty print
        } catch (Exception e) {
            return json;
        }
    }
}
```

#### 21.2 ExportSessionTool

```java
package ai.core.tool.tools;

public class ExportSessionTool extends ToolCall {
    public static final String TOOL_NAME = "export_session";

    @CoreAiMethod(name = TOOL_NAME, description = """
        Export the current session conversation to a file.
        Formats: markdown (readable), json (structured).
        Output is saved to the specified file path.
        """)
    public String exportSession(
        @CoreAiParameter(name = "format", description = "Export format: markdown | json") String format,
        @CoreAiParameter(name = "file_path", description = "Output file path") String filePath,
        ExecutionContext context
    ) {
        var fmt = "json".equalsIgnoreCase(format)
            ? SessionExporter.Format.JSON
            : SessionExporter.Format.MARKDOWN;

        // Access messages via the agent's parent node
        var messages = context.getCustomVariable("sessionMessages", List.class);
        if (messages == null || messages.isEmpty()) {
            return "Error: no session messages available for export.";
        }

        String exported = SessionExporter.export(context.getSessionId(), messages, fmt);

        try {
            Files.writeString(Path.of(filePath), exported);
            return "Session exported to " + filePath + " (" + fmt + ", " + exported.length() + " chars)";
        } catch (IOException e) {
            return "Error: failed to write export file: " + e.getMessage();
        }
    }
}
```

#### 21.3 InProcessAgentSession export API

```java
// In InProcessAgentSession — add export method:
public String exportSession(SessionExporter.Format format) {
    return SessionExporter.export(sessionId, agent.getMessages(), format);
}
```

#### 21.4 CLI `/export` command

```java
// In AgentSessionRunner.dispatchCommand():
} else if (lower.startsWith("/export")) {
    handleExport(lower);
}

private void handleExport(String cmd) {
    String format = cmd.contains("json") ? "json" : "markdown";
    String ext = format.equals("json") ? ".json" : ".md";
    String fileName = "session-" + sessionId + ext;
    Path outputPath = workspace.resolve(fileName);

    String exported = SessionExporter.export(sessionId, agent.getMessages(),
        format.equals("json") ? SessionExporter.Format.JSON : SessionExporter.Format.MARKDOWN);

    Files.writeString(outputPath, exported);
    ui.printStreamingChunk("\n  Session exported to: " + outputPath + "\n\n");
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../session/export/SessionExporter.java` | **New** — markdown/JSON export logic |
| `core-ai/src/.../tool/tools/ExportSessionTool.java` | **New** — agent tool for export |
| `core-ai/src/.../session/InProcessAgentSession.java` | Add `exportSession()` method |
| `core-ai-cli/src/.../agent/AgentSessionRunner.java` | Add `/export` command |

### Verification

1. `/export` → generates markdown file at `workspace/session-{id}.md`
2. `/export json` → generates JSON file
3. Markdown export → readable with turn numbers, tool calls, truncated results
4. JSON export → parseable, includes all non-system messages
5. Export with no messages → error message

---

## Implementation Order

```
1. P2-14  Multi-layer Config         — foundation for all other features
2. P2-19  System Reminder Injection  — small, standalone lifecycle
3. P2-13  Hooks Plugin Mechanism     — extends lifecycle system
4. P2-18  File Monitoring            — standalone lifecycle + watcher
5. P2-15  Snapshot/Revert            — lifecycle + tool + persistence
6. P2-21  Session Export             — standalone utility + CLI command
7. P2-20  Structured Output          — small Agent/Builder change
8. P2-17  Git Deep Integration       — tool + lifecycle + worktree manager
9. P2-16  LSP Integration            — most complex, external process mgmt
```
