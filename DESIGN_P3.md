# P3 Development Design Document

> Version: 1.0 | Branch: vidingcode | Date: 2026-03-06

This document provides implementation-level guidance for all 7 P3 items. Each section is self-contained.

---

## P3-22: IDE Integration Protocol

### Problem

No way to embed core-ai inside VS Code, Cursor, or other IDEs. The current server module has SSE streaming for server→client events but lacks bidirectional communication needed for IDE integration (e.g., cursor context, file selection, inline edits).

### Current State

- Server module has full SSE streaming: `PUT /api/sessions/events?sessionId=X`
- 9 event types: TextChunk, ReasoningChunk, ToolStart, ToolResult, ToolApprovalRequest, TurnComplete, Error, StatusChange, ReasoningComplete
- `SseEventBridge` adapts `AgentEventListener` → SSE transport
- `SessionChannelService` buffers events per session for reconnection
- HTTP REST API for session management (create, sendMessage, approve, history, status, close)
- No WebSocket support — SSE is unidirectional (server→client only)

### Design

#### 22.1 WebSocket transport for bidirectional communication

SSE works for server→client but IDE integration needs client→server streaming too (e.g., cursor position updates, selection changes, typing indicators). Add WebSocket as alternative transport.

```java
package ai.core.server.web.ws;

import java.util.concurrent.ConcurrentHashMap;

public class AgentWebSocketHandler {
    private final AgentSessionManager sessionManager;
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();

    // Client → Server message types
    public enum ClientMessageType {
        SEND_MESSAGE,       // user sends query
        APPROVE_TOOL,       // approve/deny tool call
        CANCEL,             // cancel current turn
        CURSOR_CONTEXT,     // IDE cursor position + surrounding code
        FILE_SELECTION,     // user selected file(s) in IDE
        MODE_SWITCH         // plan/build mode toggle
    }

    // Server → Client message types (reuse existing EventType + IDE extensions)
    public enum ServerMessageType {
        // Existing events
        TEXT_CHUNK, REASONING_CHUNK, TOOL_START, TOOL_RESULT,
        TOOL_APPROVAL_REQUEST, TURN_COMPLETE, ERROR, STATUS_CHANGE,
        // IDE-specific extensions
        INLINE_EDIT,        // suggest edit at specific file:line
        FILE_DECORATION,    // add gutter icons, underlines
        PROGRESS            // progress bar updates
    }

    public void onConnect(WebSocketSession session, String sessionId) {
        connections.put(sessionId, session);
        var agentSession = sessionManager.getSession(sessionId);
        agentSession.onEvent(new WebSocketEventBridge(session));
    }

    public void onMessage(WebSocketSession session, String sessionId, String message) {
        var msg = JsonUtil.fromJson(ClientMessage.class, message);
        var agentSession = sessionManager.getSession(sessionId);

        switch (msg.type()) {
            case SEND_MESSAGE -> agentSession.sendMessage(msg.content());
            case APPROVE_TOOL -> agentSession.approveToolCall(msg.callId(), msg.decision());
            case CANCEL -> agentSession.cancelTurn();
            case CURSOR_CONTEXT -> handleCursorContext(sessionId, msg);
            case FILE_SELECTION -> handleFileSelection(sessionId, msg);
            case MODE_SWITCH -> handleModeSwitch(sessionId, msg);
        }
    }

    public void onDisconnect(String sessionId) {
        connections.remove(sessionId);
    }

    public record ClientMessage(ClientMessageType type, String content,
                                String callId, String decision,
                                Map<String, Object> metadata) {}
}
```

#### 22.2 WebSocketEventBridge

```java
package ai.core.server.web.ws;

public class WebSocketEventBridge implements AgentEventListener {
    private final WebSocketSession session;

    public WebSocketEventBridge(WebSocketSession session) {
        this.session = session;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        send(ServerMessageType.TEXT_CHUNK, event);
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        send(ServerMessageType.TOOL_START, event);
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        send(ServerMessageType.TOOL_RESULT, event);
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        send(ServerMessageType.TOOL_APPROVAL_REQUEST, event);
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        send(ServerMessageType.TURN_COMPLETE, event);
    }

    // ... same for all 9 event types

    private void send(ServerMessageType type, AgentEvent event) {
        var msg = Map.of("type", type.name(), "data", event, "timestamp", System.currentTimeMillis());
        session.send(JsonUtil.toJson(msg));
    }
}
```

#### 22.3 IDE-specific events

Extend `AgentEvent` for IDE-specific communication:

```java
package ai.core.api.server.session;

// Agent suggests an inline edit to IDE
public record InlineEditEvent(
    String sessionId,
    String filePath,
    int startLine, int endLine,
    String newContent,
    String description
) implements AgentEvent {}

// Agent decorates file in IDE (error markers, highlights)
public record FileDecorationEvent(
    String sessionId,
    String filePath,
    List<Decoration> decorations
) implements AgentEvent {
    public record Decoration(int line, String type, String message) {} // type: error, warning, info
}
```

#### 22.4 IDE cursor context injection

When IDE sends cursor context, inject it into the agent's next query:

```java
private void handleCursorContext(String sessionId, ClientMessage msg) {
    var session = sessionManager.getSession(sessionId);
    var context = msg.metadata();
    // Store in ExecutionContext.customVariables for SystemReminderLifecycle (P2-19) to pick up
    session.getAgent().getExecutionContext().getCustomVariables().put("ide.cursorContext", Map.of(
        "filePath", context.get("filePath"),
        "line", context.get("line"),
        "column", context.get("column"),
        "selection", context.getOrDefault("selection", ""),
        "surroundingCode", context.getOrDefault("surroundingCode", "")
    ));
}
```

#### 22.5 Server module registration

```java
// In ServerModule.java — add WebSocket route alongside SSE
public class ServerModule extends AbstractModule {
    @Override
    protected void initialize() {
        // Existing SSE route
        sse().add("/api/sessions/events", AgentSessionChannelListener.class);

        // New WebSocket route
        ws().add("/api/sessions/ws", AgentWebSocketHandler.class);
    }
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai-server/src/.../web/ws/AgentWebSocketHandler.java` | **New** — WebSocket message handler |
| `core-ai-server/src/.../web/ws/WebSocketEventBridge.java` | **New** — AgentEvent → WebSocket adapter |
| `core-ai-api/src/.../session/InlineEditEvent.java` | **New** — IDE inline edit event |
| `core-ai-api/src/.../session/FileDecorationEvent.java` | **New** — IDE decoration event |
| `core-ai-api/src/.../session/EventType.java` | Add INLINE_EDIT, FILE_DECORATION, PROGRESS |
| `core-ai-server/src/.../ServerModule.java` | Register WebSocket route |
| `build.gradle.kts` | Add WebSocket dependency if not in core-ng |

### Verification

1. IDE connects via WebSocket → connection established, events flowing
2. IDE sends `SEND_MESSAGE` → agent processes query, streams response via WS
3. IDE sends `CURSOR_CONTEXT` → context injected into next agent turn
4. Agent edits file → `InlineEditEvent` sent to IDE for inline preview
5. SSE reconnection → buffered events replayed (existing behavior preserved)

---

## P3-23: Sandbox Isolation Execution

### Problem

`ShellCommandTool` and `PythonScriptTool` execute commands with full user privileges. No resource limits, no filesystem isolation, no environment sanitization. A malicious or careless LLM-generated command can damage the system.

### Current State

- `ShellCommandTool` uses `ProcessBuilder` with inherited environment
- `PythonScriptTool` uses `ProcessBuilder` with `PYTHONIOENCODING=utf-8`
- Only safety: timeout enforcement (30s shell, 60s python) and directory existence checks
- `SystemUtil.detectPlatform()` provides OS detection (Linux/macOS/Windows)
- No sandbox, container, namespace, seccomp, or resource limit mechanisms

### Design

#### 23.1 SandboxExecutor abstraction

```java
package ai.core.sandbox;

import java.nio.file.Path;
import java.util.*;

public interface SandboxExecutor {
    record SandboxConfig(
        Path workingDirectory,
        long timeoutSeconds,
        long maxMemoryMB,          // 0 = unlimited
        Set<String> allowedPaths,  // read/write access
        Set<String> readOnlyPaths, // read-only access
        Map<String, String> environment,
        boolean networkAccess
    ) {
        public static SandboxConfig defaultConfig(Path workDir) {
            return new SandboxConfig(workDir, 30, 512, Set.of(workDir.toString()),
                Set.of("/usr", "/bin", "/lib", "/etc"), Map.of(), true);
        }
    }

    record SandboxResult(int exitCode, String stdout, String stderr, boolean timedOut, boolean killed) {}

    SandboxResult execute(List<String> command, SandboxConfig config);

    boolean isAvailable();

    String name();
}
```

#### 23.2 macOS sandbox-exec implementation

```java
package ai.core.sandbox;

public class MacOSSandboxExecutor implements SandboxExecutor {

    @Override
    public SandboxResult execute(List<String> command, SandboxConfig config) {
        String profile = generateProfile(config);

        var fullCommand = new ArrayList<String>();
        fullCommand.add("sandbox-exec");
        fullCommand.add("-p");
        fullCommand.add(profile);
        fullCommand.addAll(command);

        return executeProcess(fullCommand, config);
    }

    private String generateProfile(SandboxConfig config) {
        var sb = new StringBuilder();
        sb.append("(version 1)\n");
        sb.append("(deny default)\n");

        // Allow basic process execution
        sb.append("(allow process-exec)\n");
        sb.append("(allow process-fork)\n");
        sb.append("(allow sysctl-read)\n");
        sb.append("(allow mach-lookup)\n");

        // Read-only paths
        for (String path : config.readOnlyPaths()) {
            sb.append("(allow file-read* (subpath \"").append(path).append("\"))\n");
        }

        // Read-write paths
        for (String path : config.allowedPaths()) {
            sb.append("(allow file-read* (subpath \"").append(path).append("\"))\n");
            sb.append("(allow file-write* (subpath \"").append(path).append("\"))\n");
        }

        // Temp directory access
        sb.append("(allow file-read* file-write* (subpath \"/tmp\"))\n");
        sb.append("(allow file-read* file-write* (subpath \"/private/tmp\"))\n");

        // Network access
        if (config.networkAccess()) {
            sb.append("(allow network*)\n");
        }

        return sb.toString();
    }

    private SandboxResult executeProcess(List<String> command, SandboxConfig config) {
        try {
            var pb = new ProcessBuilder(command)
                .directory(config.workingDirectory().toFile());

            // Sanitize environment
            var env = pb.environment();
            env.clear();
            env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
            env.put("HOME", config.workingDirectory().toString());
            env.put("LANG", "en_US.UTF-8");
            env.putAll(config.environment());

            var process = pb.start();
            String stdout = new String(process.getInputStream().readAllBytes());
            String stderr = new String(process.getErrorStream().readAllBytes());
            boolean finished = process.waitFor(config.timeoutSeconds(), java.util.concurrent.TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new SandboxResult(-1, stdout, stderr, true, true);
            }

            return new SandboxResult(process.exitValue(), stdout, stderr, false, false);
        } catch (Exception e) {
            return new SandboxResult(-1, "", e.getMessage(), false, false);
        }
    }

    @Override
    public boolean isAvailable() {
        return SystemUtil.detectPlatform().name().startsWith("MACOS")
            && ShellUtil.isCommandExists("sandbox-exec");
    }

    @Override
    public String name() { return "macos-sandbox-exec"; }
}
```

#### 23.3 Linux namespace isolation (Docker-based)

```java
package ai.core.sandbox;

public class DockerSandboxExecutor implements SandboxExecutor {

    private static final String DEFAULT_IMAGE = "ubuntu:24.04";

    @Override
    public SandboxResult execute(List<String> command, SandboxConfig config) {
        var dockerCmd = new ArrayList<String>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");
        dockerCmd.add("--network=" + (config.networkAccess() ? "bridge" : "none"));

        // Resource limits
        if (config.maxMemoryMB() > 0) {
            dockerCmd.add("--memory=" + config.maxMemoryMB() + "m");
        }
        dockerCmd.add("--cpus=1");

        // Mount working directory
        dockerCmd.add("-v");
        dockerCmd.add(config.workingDirectory() + ":/workspace");
        dockerCmd.add("-w");
        dockerCmd.add("/workspace");

        // Read-only mounts
        for (String path : config.readOnlyPaths()) {
            dockerCmd.add("-v");
            dockerCmd.add(path + ":" + path + ":ro");
        }

        // Environment
        for (var entry : config.environment().entrySet()) {
            dockerCmd.add("-e");
            dockerCmd.add(entry.getKey() + "=" + entry.getValue());
        }

        dockerCmd.add(DEFAULT_IMAGE);
        dockerCmd.addAll(command);

        return executeProcess(dockerCmd, config);
    }

    @Override
    public boolean isAvailable() {
        return ShellUtil.isCommandExists("docker");
    }

    @Override
    public String name() { return "docker"; }
}
```

#### 23.4 Fallback: no sandbox

```java
package ai.core.sandbox;

public class NoopSandboxExecutor implements SandboxExecutor {
    @Override
    public SandboxResult execute(List<String> command, SandboxConfig config) {
        // Direct ProcessBuilder execution (current behavior)
        try {
            var pb = new ProcessBuilder(command)
                .directory(config.workingDirectory().toFile())
                .redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes());
            boolean finished = process.waitFor(config.timeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new SandboxResult(-1, output, "", true, true);
            }
            return new SandboxResult(process.exitValue(), output, "", false, false);
        } catch (Exception e) {
            return new SandboxResult(-1, "", e.getMessage(), false, false);
        }
    }

    @Override
    public boolean isAvailable() { return true; }

    @Override
    public String name() { return "none"; }
}
```

#### 23.5 SandboxManager auto-detection

```java
package ai.core.sandbox;

public class SandboxManager {
    private static final List<SandboxExecutor> EXECUTORS = List.of(
        new MacOSSandboxExecutor(),
        new DockerSandboxExecutor(),
        new NoopSandboxExecutor()
    );

    public static SandboxExecutor detect() {
        for (var executor : EXECUTORS) {
            if (executor.isAvailable()) {
                LOGGER.info("Sandbox executor: {}", executor.name());
                return executor;
            }
        }
        return new NoopSandboxExecutor();
    }
}
```

#### 23.6 Integration in ShellCommandTool

```java
// In ShellCommandTool — replace direct ProcessBuilder with SandboxExecutor:
private SandboxExecutor sandbox = SandboxManager.detect();

private ToolCallResult exec(List<String> commands, String workdir, long timeout) {
    var config = new SandboxExecutor.SandboxConfig(
        Path.of(workdir), timeout, 512,
        Set.of(workdir), Set.of("/usr", "/bin", "/lib"),
        Map.of(), true
    );
    var result = sandbox.execute(commands, config);
    if (result.timedOut()) {
        return ToolCallResult.failed("Command timed out after " + timeout + "s");
    }
    return ToolCallResult.completed(result.stdout());
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../sandbox/SandboxExecutor.java` | **New** — sandbox interface |
| `core-ai/src/.../sandbox/MacOSSandboxExecutor.java` | **New** — macOS sandbox-exec |
| `core-ai/src/.../sandbox/DockerSandboxExecutor.java` | **New** — Docker container |
| `core-ai/src/.../sandbox/NoopSandboxExecutor.java` | **New** — fallback (current behavior) |
| `core-ai/src/.../sandbox/SandboxManager.java` | **New** — auto-detection |
| `core-ai/src/.../tool/tools/ShellCommandTool.java` | Use SandboxExecutor instead of raw ProcessBuilder |
| `core-ai/src/.../tool/tools/PythonScriptTool.java` | Use SandboxExecutor |

### Verification

1. macOS: `sandbox-exec` available → commands run in sandbox profile
2. Linux: Docker available → commands run in container
3. Neither available → falls back to NoopSandbox (current behavior)
4. Sandbox command tries to write outside allowed path → denied
5. Sandbox command exceeds memory limit → killed
6. Network disabled → no outbound connections

---

## P3-24: Multi Provider Unified Abstraction Enhancement

### Problem

All providers route through `LiteLLMProvider` (OpenAI-compatible API). No native support for Anthropic (cache-control, thinking tokens), Google Gemini, or AWS Bedrock with their specific features. `OpenAIProvider` exists but is not integrated into the `LLMProvider` hierarchy.

### Current State

- `LLMProvider` abstract class with template method pattern: `preprocess()` → `doCompletionStream()` → `postprocess()`
- `LiteLLMProvider extends LLMProvider` — the only production provider
- `OpenAIProvider` — standalone, NOT extending LLMProvider (uses OpenAI SDK directly)
- `LLMProviderType` enum: DEEPSEEK, OPENAI, AZURE, AZURE_INFERENCE, OPENROUTER, LITELLM
- `LLMProviders` registry: `EnumMap<LLMProviderType, LLMProvider>`
- `AgentBootstrap.configureLLMProviders()` creates all providers as `LiteLLMProvider` instances with different base URLs
- `LLMProviderConfig.requestExtraBody` allows provider-specific JSON extensions

### Design

#### 24.1 Add provider types

```java
// Extend LLMProviderType enum
public enum LLMProviderType {
    DEEPSEEK("deepseek"),
    OPENAI("openai"),
    AZURE("azure"),
    AZURE_INFERENCE("azure-inference"),
    OPENROUTER("openrouter"),
    LITELLM("litellm"),
    // New
    ANTHROPIC("anthropic"),
    GOOGLE("google"),
    BEDROCK("bedrock");
}
```

#### 24.2 AnthropicProvider

Native Anthropic API support with cache-control and extended thinking:

```java
package ai.core.llm.providers;

public class AnthropicProvider extends LLMProvider {
    private static final String API_BASE = "https://api.anthropic.com/v1";
    private final String apiKey;
    private final HTTPClient client;

    public AnthropicProvider(LLMProviderConfig config, String apiKey) {
        super(config);
        this.apiKey = apiKey;
        this.client = HTTPClient.builder()
            .timeout(config.getTimeout())
            .connectTimeout(config.getConnectTimeout())
            .build();
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        var anthropicRequest = convertToAnthropicFormat(request);
        var httpRequest = new HTTPRequest(HTTPMethod.POST, API_BASE + "/messages");
        httpRequest.header("x-api-key", apiKey);
        httpRequest.header("anthropic-version", "2023-06-01");
        httpRequest.header("content-type", "application/json");
        httpRequest.body(JsonUtil.toJson(anthropicRequest));

        // SSE streaming with Anthropic event format
        return consumeAnthropicStream(httpRequest, callback);
    }

    private Map<String, Object> convertToAnthropicFormat(CompletionRequest request) {
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model);
        body.put("max_tokens", maxTokens());
        if (request.temperature != null) body.put("temperature", request.temperature);
        body.put("stream", true);

        // Extract system message separately (Anthropic format)
        var system = new ArrayList<Map<String, Object>>();
        var messages = new ArrayList<Map<String, Object>>();
        for (var msg : request.messages) {
            if (msg.role == RoleType.SYSTEM) {
                var block = Map.of("type", "text", "text", msg.getTextContent());
                // Apply cache-control to system message if configured
                if (hasCacheControl(msg)) {
                    block = withCacheControl(block);
                }
                system.add(block);
            } else {
                messages.add(convertMessage(msg));
            }
        }
        if (!system.isEmpty()) body.put("system", system);
        body.put("messages", messages);

        // Tools in Anthropic format
        if (request.tools != null && !request.tools.isEmpty()) {
            body.put("tools", request.tools.stream()
                .map(this::convertToolToAnthropic).toList());
        }

        // Extended thinking support
        if (request.reasoningEffort != null) {
            body.put("thinking", Map.of(
                "type", "enabled",
                "budget_tokens", reasoningBudget(request.reasoningEffort)
            ));
        }

        return body;
    }

    private Map<String, Object> withCacheControl(Map<String, Object> block) {
        var result = new LinkedHashMap<>(block);
        result.put("cache_control", Map.of("type", "ephemeral"));
        return result;
    }

    private int reasoningBudget(ReasoningEffort effort) {
        return switch (effort) {
            case LOW -> 2000;
            case MEDIUM -> 8000;
            case HIGH -> 32000;
        };
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        return doCompletionStream(request, new DefaultStreamingCallback());
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        // Anthropic doesn't provide embeddings — use Voyage AI or delegate
        throw new UnsupportedOperationException("Anthropic does not provide embeddings API");
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) { return null; }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) { return null; }

    @Override
    public String name() { return "anthropic"; }

    @Override
    public int maxTokens() { return 8192; }
}
```

#### 24.3 GoogleProvider (Gemini)

```java
package ai.core.llm.providers;

public class GoogleProvider extends LLMProvider {
    private static final String API_BASE = "https://generativelanguage.googleapis.com/v1beta";
    private final String apiKey;
    private final HTTPClient client;

    public GoogleProvider(LLMProviderConfig config, String apiKey) {
        super(config);
        this.apiKey = apiKey;
        this.client = HTTPClient.builder()
            .timeout(config.getTimeout())
            .connectTimeout(config.getConnectTimeout())
            .build();
    }

    @Override
    protected CompletionResponse doCompletionStream(CompletionRequest request, StreamingCallback callback) {
        String url = API_BASE + "/models/" + request.model + ":streamGenerateContent?key=" + apiKey;
        var geminiRequest = convertToGeminiFormat(request);
        var httpRequest = new HTTPRequest(HTTPMethod.POST, url);
        httpRequest.header("content-type", "application/json");
        httpRequest.body(JsonUtil.toJson(geminiRequest));

        return consumeGeminiStream(httpRequest, callback);
    }

    private Map<String, Object> convertToGeminiFormat(CompletionRequest request) {
        var body = new LinkedHashMap<String, Object>();

        // System instruction (separate in Gemini)
        for (var msg : request.messages) {
            if (msg.role == RoleType.SYSTEM) {
                body.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", msg.getTextContent()))
                ));
                break;
            }
        }

        // Convert messages to Gemini contents format
        var contents = new ArrayList<Map<String, Object>>();
        for (var msg : request.messages) {
            if (msg.role == RoleType.SYSTEM) continue;
            String role = msg.role == RoleType.ASSISTANT ? "model" : "user";
            contents.add(Map.of(
                "role", role,
                "parts", List.of(Map.of("text", msg.getTextContent()))
            ));
        }
        body.put("contents", contents);

        // Generation config
        var genConfig = new LinkedHashMap<String, Object>();
        if (request.temperature != null) genConfig.put("temperature", request.temperature);
        genConfig.put("maxOutputTokens", maxTokens());
        body.put("generationConfig", genConfig);

        // Tools in Gemini format
        if (request.tools != null && !request.tools.isEmpty()) {
            body.put("tools", List.of(Map.of(
                "functionDeclarations", request.tools.stream()
                    .map(this::convertToolToGemini).toList()
            )));
        }

        return body;
    }

    @Override
    protected CompletionResponse doCompletion(CompletionRequest request) {
        return doCompletionStream(request, new DefaultStreamingCallback());
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest request) {
        // Gemini has text-embedding-004 model
        String url = API_BASE + "/models/" + config.getEmbeddingModel() + ":embedContent?key=" + apiKey;
        // ... implement embedding call
        return null;
    }

    @Override
    public RerankingResponse rerankings(RerankingRequest request) { return null; }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) { return null; }

    @Override
    public String name() { return "google"; }

    @Override
    public int maxTokens() { return 8192; }
}
```

#### 24.4 Bootstrap registration

```java
// In AgentBootstrap.configureLLMProviders() — add new providers:

// Anthropic
props.property("anthropic.api.key").ifPresent(key -> {
    var providerConfig = createProviderConfig("anthropic");
    var provider = new AnthropicProvider(providerConfig, key);
    injectTracerIfAvailable(provider);
    llmProviders.addProvider(LLMProviderType.ANTHROPIC, provider);
});

// Google Gemini
props.property("google.api.key").ifPresent(key -> {
    var providerConfig = createProviderConfig("google");
    var provider = new GoogleProvider(providerConfig, key);
    injectTracerIfAvailable(provider);
    llmProviders.addProvider(LLMProviderType.GOOGLE, provider);
});
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../llm/providers/AnthropicProvider.java` | **New** — native Anthropic API |
| `core-ai/src/.../llm/providers/GoogleProvider.java` | **New** — native Gemini API |
| `core-ai/src/.../llm/LLMProviderType.java` | Add ANTHROPIC, GOOGLE, BEDROCK |
| `core-ai/src/.../bootstrap/AgentBootstrap.java` | Register new providers |

### Verification

1. `anthropic.api.key` configured → AnthropicProvider registered
2. System prompt gets `cache_control: ephemeral` → cache hit on subsequent calls
3. Extended thinking enabled → `thinking` block in Anthropic request
4. Google provider with Gemini model → proper format conversion
5. Fallback: LiteLLMProvider still works for all providers via proxy

---

## P3-25: Token Precise Billing

### Problem

Token tracking only captures `promptTokens`, `completionTokens`, `totalTokens`. No distinction for cache_read, cache_write, reasoning tokens. No cost calculation by model pricing.

### Current State

- `Usage` class: `promptTokens`, `completionTokens`, `totalTokens`, `CompletionTokensDetails.reasoningTokens`
- `Node.addTokenCost(Usage)` accumulates tokens and propagates to parent
- `AgentRunner` extracts input/output for `TokenUsage` (server domain)
- `model_prices_and_context_window.json` (27k lines) contains pricing data but only `maxInputTokens` / `maxOutputTokens` are loaded
- `LLMModelContextRegistry` does not load pricing fields

### Design

#### 25.1 Extend Usage class

```java
// In Usage.java — add cache-aware fields
@Property(name = "prompt_tokens")
public int promptTokens;

@Property(name = "completion_tokens")
public int completionTokens;

@Property(name = "total_tokens")
public int totalTokens;

// New fields
@Property(name = "cache_read_input_tokens")
public int cacheReadInputTokens;

@Property(name = "cache_creation_input_tokens")
public int cacheCreationInputTokens;

@Property(name = "completion_tokens_details")
public CompletionTokensDetails completionTokensDetails;

public static class CompletionTokensDetails {
    @Property(name = "reasoning_tokens")
    public int reasoningTokens;
}

// Addition method update
public void add(Usage other) {
    if (other == null) return;
    this.promptTokens += other.promptTokens;
    this.completionTokens += other.completionTokens;
    this.totalTokens += other.totalTokens;
    this.cacheReadInputTokens += other.cacheReadInputTokens;
    this.cacheCreationInputTokens += other.cacheCreationInputTokens;
    if (other.completionTokensDetails != null) {
        if (this.completionTokensDetails == null) {
            this.completionTokensDetails = new CompletionTokensDetails();
        }
        this.completionTokensDetails.reasoningTokens += other.completionTokensDetails.reasoningTokens;
    }
}
```

#### 25.2 Model pricing in LLMModelContextRegistry

```java
// Extend ModelInfo record
public record ModelInfo(
    int maxInputTokens,
    int maxOutputTokens,
    String provider,
    String mode,
    // New pricing fields
    double inputCostPerToken,
    double outputCostPerToken,
    double cacheReadCostPerToken,
    double cacheCreationCostPerToken,
    double reasoningCostPerToken
) {}

// In loadModelInfo() — parse pricing from JSON
private ModelInfo loadModelInfo(Map<String, Object> data) {
    return new ModelInfo(
        getInt(data, "max_input_tokens", 0),
        getInt(data, "max_output_tokens", 0),
        getString(data, "litellm_provider", ""),
        getString(data, "mode", ""),
        getDouble(data, "input_cost_per_token", 0),
        getDouble(data, "output_cost_per_token", 0),
        getDouble(data, "cache_read_input_token_cost", 0),
        getDouble(data, "cache_creation_input_token_cost", 0),
        getDouble(data, "output_cost_per_reasoning_token", 0)
    );
}
```

#### 25.3 CostCalculator

```java
package ai.core.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class CostCalculator {

    public record CostBreakdown(
        BigDecimal inputCost,
        BigDecimal outputCost,
        BigDecimal cacheReadCost,
        BigDecimal cacheCreationCost,
        BigDecimal reasoningCost,
        BigDecimal totalCost
    ) {
        public String formatted() {
            return "$" + totalCost.setScale(6, RoundingMode.HALF_UP).toPlainString();
        }
    }

    public static CostBreakdown calculate(Usage usage, String model) {
        var info = LLMModelContextRegistry.getModelInfo(model);
        if (info == null) {
            return new CostBreakdown(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        var input = BigDecimal.valueOf(usage.promptTokens)
            .multiply(BigDecimal.valueOf(info.inputCostPerToken()));
        var output = BigDecimal.valueOf(usage.completionTokens)
            .multiply(BigDecimal.valueOf(info.outputCostPerToken()));
        var cacheRead = BigDecimal.valueOf(usage.cacheReadInputTokens)
            .multiply(BigDecimal.valueOf(info.cacheReadCostPerToken()));
        var cacheCreate = BigDecimal.valueOf(usage.cacheCreationInputTokens)
            .multiply(BigDecimal.valueOf(info.cacheCreationCostPerToken()));

        int reasoningTokens = usage.completionTokensDetails != null
            ? usage.completionTokensDetails.reasoningTokens : 0;
        var reasoning = BigDecimal.valueOf(reasoningTokens)
            .multiply(BigDecimal.valueOf(info.reasoningCostPerToken()));

        var total = input.add(output).add(cacheRead).add(cacheCreate).add(reasoning);

        return new CostBreakdown(input, output, cacheRead, cacheCreate, reasoning, total);
    }
}
```

#### 25.4 CLI display integration

```java
// In CliEventListener — update token display after each turn:
@Override
public void onTurnComplete(TurnCompleteEvent event) {
    var usage = agent.getCurrentTokenUsage();
    String model = agent.getModel();
    var cost = CostCalculator.calculate(usage, model);

    ui.printStatusLine(String.format(
        "Tokens: %d in / %d out / %d cache_read | Cost: %s",
        usage.promptTokens, usage.completionTokens, usage.cacheReadInputTokens,
        cost.formatted()
    ));
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../llm/domain/Usage.java` | Add cache/reasoning token fields + `add()` method |
| `core-ai/src/.../llm/LLMModelContextRegistry.java` | Load pricing fields from JSON |
| `core-ai/src/.../llm/CostCalculator.java` | **New** — per-model cost calculation |
| `core-ai-cli/src/.../listener/CliEventListener.java` | Display cost breakdown |
| `core-ai-server/src/.../domain/TokenUsage.java` | Add cache/reasoning fields |

### Verification

1. Anthropic response with `cache_read_input_tokens` → tracked in Usage
2. `CostCalculator.calculate()` for `claude-sonnet-4` → correct per-token pricing
3. Multi-turn conversation → costs accumulated across turns
4. CLI displays cost after each turn
5. Unknown model → zero cost (graceful fallback)

---

## P3-26: Custom Agent Configuration

### Problem

Creating agents requires Java code. Non-developers cannot define agents. No way to define an agent via configuration file and use it without recompilation.

### Current State

- `AgentBuilder` has 40+ builder methods for full agent configuration
- `BuiltinTools` defines 6 tool groups: ALL, FILE_OPERATIONS, FILE_READ_ONLY, MULTIMODAL, WEB, CODE_EXECUTION
- Default agents (DefaultExploreAgent, DefaultShellCommandAgent, etc.) are Java factory classes
- SnakeYAML 2.2 already in dependencies, used by `SkillLoader` for YAML parsing
- Server module has `CreateAgentRequest` with basic agent definition fields
- `Termination` interface with implementations: MaxRound, StopMessage, MaxToken, UserCancelled

### Design

#### 26.1 Agent YAML format

`.core-ai/agents/my-agent.yaml`:

```yaml
name: code-reviewer
description: Reviews code changes and suggests improvements

system_prompt: |
  You are an expert code reviewer. Analyze code changes for:
  - Bugs and potential issues
  - Performance problems
  - Code style and best practices
  Always provide specific, actionable suggestions.

prompt_template: |
  Review the following code changes:
  {{{NODE_CURRENT_INPUT}}}

model: anthropic/claude-sonnet-4
temperature: 0.3
max_turns: 10

# Tool groups or individual tool names
tools:
  - group: file_read_only     # BuiltinTools group
  - name: run_bash_command     # individual tool

# MCP servers
mcp_servers:
  - github

# Skills
skills:
  - .core-ai/skills

# Optional sub-agents
sub_agents:
  - name: explorer
    description: Search the codebase
    system_prompt: Find relevant code files
    tools:
      - group: file_read_only
    max_turns: 5
```

#### 26.2 AgentConfigLoader

```java
package ai.core.agent.config;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.util.*;

public class AgentConfigLoader {

    public record AgentConfig(
        String name,
        String description,
        String systemPrompt,
        String promptTemplate,
        String model,
        Double temperature,
        Integer maxTurns,
        List<ToolRef> tools,
        List<String> mcpServers,
        List<String> skills,
        List<AgentConfig> subAgents
    ) {}

    public record ToolRef(String group, String name) {
        public boolean isGroup() { return group != null; }
    }

    public static AgentConfig load(Path yamlFile) {
        try {
            var options = new org.yaml.snakeyaml.LoaderOptions();
            options.setCodePointLimit(1024 * 1024); // 1MB max
            var yaml = new Yaml(new SafeConstructor(options));
            Map<String, Object> data = yaml.load(Files.readString(yamlFile));
            return parse(data);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load agent config: " + yamlFile, e);
        }
    }

    public static List<AgentConfig> loadAll(Path directory) {
        if (!Files.isDirectory(directory)) return List.of();
        try (var stream = Files.list(directory)) {
            return stream
                .filter(f -> f.toString().endsWith(".yaml") || f.toString().endsWith(".yml"))
                .map(AgentConfigLoader::load)
                .toList();
        } catch (Exception e) {
            LOGGER.warn("Failed to load agent configs from {}: {}", directory, e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static AgentConfig parse(Map<String, Object> data) {
        List<ToolRef> tools = new ArrayList<>();
        var toolsList = (List<Map<String, String>>) data.getOrDefault("tools", List.of());
        for (var t : toolsList) {
            tools.add(new ToolRef(t.get("group"), t.get("name")));
        }

        List<AgentConfig> subAgents = new ArrayList<>();
        var subList = (List<Map<String, Object>>) data.getOrDefault("sub_agents", List.of());
        for (var sub : subList) {
            subAgents.add(parse(sub));
        }

        return new AgentConfig(
            (String) data.get("name"),
            (String) data.get("description"),
            (String) data.get("system_prompt"),
            (String) data.get("prompt_template"),
            (String) data.get("model"),
            data.containsKey("temperature") ? ((Number) data.get("temperature")).doubleValue() : null,
            data.containsKey("max_turns") ? ((Number) data.get("max_turns")).intValue() : null,
            tools,
            (List<String>) data.getOrDefault("mcp_servers", List.of()),
            (List<String>) data.getOrDefault("skills", List.of()),
            subAgents
        );
    }
}
```

#### 26.3 AgentConfigBuilder — config to Agent

```java
package ai.core.agent.config;

public class AgentConfigBuilder {
    private static final Map<String, java.util.function.Supplier<List<ToolCall>>> TOOL_GROUPS = Map.of(
        "all", BuiltinTools::ALL,
        "file_operations", BuiltinTools::FILE_OPERATIONS,
        "file_read_only", BuiltinTools::FILE_READ_ONLY,
        "multimodal", BuiltinTools::MULTIMODAL,
        "web", BuiltinTools::WEB,
        "code_execution", BuiltinTools::CODE_EXECUTION
    );

    private static final Map<String, java.util.function.Supplier<ToolCall>> TOOL_REGISTRY = Map.ofEntries(
        Map.entry("read_file", ReadFileTool::new),
        Map.entry("write_file", WriteFileTool::new),
        Map.entry("edit_file", EditFileTool::new),
        Map.entry("glob_file", GlobFileTool::new),
        Map.entry("grep_file", GrepFileTool::new),
        Map.entry("run_bash_command", ShellCommandTool::new),
        Map.entry("run_python_script", PythonScriptTool::new),
        Map.entry("web_fetch", WebFetchTool::new),
        Map.entry("web_search", WebSearchTool::new),
        Map.entry("write_todos", WriteTodosTool::new)
    );

    public static Agent build(AgentConfig config, LLMProviders providers) {
        var builder = Agent.builder()
            .name(config.name())
            .description(config.description() != null ? config.description() : config.name());

        // System prompt and template
        if (config.systemPrompt() != null) builder.systemPrompt(config.systemPrompt());
        if (config.promptTemplate() != null) builder.promptTemplate(config.promptTemplate());

        // Model and provider
        LLMProvider provider;
        if (config.model() != null) {
            builder.model(config.model());
            provider = resolveProvider(config.model(), providers);
        } else {
            provider = providers.getProvider();
        }
        builder.llmProvider(provider);

        // Temperature and turns
        if (config.temperature() != null) builder.temperature(config.temperature());
        if (config.maxTurns() != null) builder.maxTurn(config.maxTurns());

        // Tools
        var tools = resolveTools(config.tools());
        builder.toolCalls(tools);

        // MCP servers
        if (config.mcpServers() != null && !config.mcpServers().isEmpty()) {
            builder.mcpServers(config.mcpServers());
        }

        // Skills
        if (config.skills() != null && !config.skills().isEmpty()) {
            builder.skills(config.skills().toArray(new String[0]));
        }

        // Sub-agents
        if (config.subAgents() != null && !config.subAgents().isEmpty()) {
            var subAgentTools = config.subAgents().stream()
                .map(sub -> SubAgentToolCall.of(build(sub, providers)))
                .toList();
            builder.subAgents(subAgentTools);
        }

        return builder.build();
    }

    private static List<ToolCall> resolveTools(List<AgentConfigLoader.ToolRef> refs) {
        if (refs == null || refs.isEmpty()) return BuiltinTools.ALL();
        var tools = new ArrayList<ToolCall>();
        for (var ref : refs) {
            if (ref.isGroup()) {
                var groupFactory = TOOL_GROUPS.get(ref.group().toLowerCase());
                if (groupFactory != null) tools.addAll(groupFactory.get());
            } else if (ref.name() != null) {
                var toolFactory = TOOL_REGISTRY.get(ref.name());
                if (toolFactory != null) tools.add(toolFactory.get());
            }
        }
        return tools;
    }

    private static LLMProvider resolveProvider(String model, LLMProviders providers) {
        // Match model prefix to provider
        if (model.startsWith("anthropic/") || model.startsWith("claude")) {
            var p = providers.getProvider(LLMProviderType.ANTHROPIC);
            if (p != null) return p;
        }
        if (model.startsWith("gemini") || model.startsWith("google/")) {
            var p = providers.getProvider(LLMProviderType.GOOGLE);
            if (p != null) return p;
        }
        // Fallback to default or OpenRouter
        return providers.getProvider();
    }
}
```

#### 26.4 CLI integration

```java
// In CliAgent.of() — load custom agents from workspace
Path agentsDir = workspace.resolve(".core-ai/agents");
var customAgents = AgentConfigLoader.loadAll(agentsDir);

// Register as sub-agents or make selectable
if (!customAgents.isEmpty()) {
    var subAgentTools = customAgents.stream()
        .map(cfg -> SubAgentToolCall.of(AgentConfigBuilder.build(cfg, providers)))
        .toList();
    builder.subAgents(subAgentTools);
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/config/AgentConfigLoader.java` | **New** — YAML parser for agent definitions |
| `core-ai/src/.../agent/config/AgentConfigBuilder.java` | **New** — config-to-Agent builder |
| `core-ai-cli/src/.../agent/CliAgent.java` | Load custom agents from `.core-ai/agents/` |

### Verification

1. Create `.core-ai/agents/reviewer.yaml` → agent available as sub-agent
2. YAML with `tools: [{group: file_read_only}]` → only read tools available
3. YAML with sub_agents → recursive agent construction
4. YAML with `model: anthropic/claude-sonnet-4` → correct provider resolved
5. Invalid YAML → warning logged, agent skipped

---

## P3-27: Remote Service and Mobile Access

### Problem

Server module is designed for co-located deployment. No service discovery for LAN access, no optimized protocol for mobile clients, no remote TUI attachment.

### Current State

- Full HTTP REST API with SSE streaming (9 event types)
- Bearer token authentication (`cai_` prefix)
- `AgentSessionManager` with in-memory ConcurrentMap
- MongoDB persistence for agent definitions and runs
- No mDNS, no WebSocket, no session persistence across restarts

### Design

#### 27.1 mDNS zero-config LAN discovery

```java
package ai.core.server.discovery;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.net.InetAddress;

public class ServiceDiscovery implements AutoCloseable {
    private JmDNS jmdns;
    private static final String SERVICE_TYPE = "_core-ai._tcp.local.";

    public void register(String instanceName, int port) {
        try {
            jmdns = JmDNS.create(InetAddress.getLocalHost());
            var serviceInfo = ServiceInfo.create(
                SERVICE_TYPE, instanceName, port,
                "Core AI Agent Service"
            );
            jmdns.registerService(serviceInfo);
            LOGGER.info("mDNS service registered: {} on port {}", instanceName, port);
        } catch (Exception e) {
            LOGGER.warn("mDNS registration failed: {}", e.getMessage());
        }
    }

    public List<ServiceInfo> discover() {
        try {
            if (jmdns == null) jmdns = JmDNS.create(InetAddress.getLocalHost());
            return List.of(jmdns.list(SERVICE_TYPE, 3000));
        } catch (Exception e) {
            return List.of();
        }
    }

    @Override
    public void close() {
        if (jmdns != null) {
            jmdns.unregisterAllServices();
            try { jmdns.close(); } catch (Exception ignored) {}
        }
    }
}
```

#### 27.2 Session persistence for server restart

```java
package ai.core.server.session;

public class PersistentSessionManager extends AgentSessionManager {
    private final PersistenceProvider persistence;

    public PersistentSessionManager(PersistenceProvider persistence) {
        this.persistence = persistence;
    }

    @Override
    public InProcessAgentSession createSession(SessionConfig config) {
        var session = super.createSession(config);
        // Persist session config for recovery
        persistence.save("session:" + session.getSessionId(),
            JsonUtil.toJson(config));
        return session;
    }

    public void recoverSessions() {
        // On startup, load persisted session configs
        // Recreate sessions (messages restored from agent persistence)
        persistence.loadByPrefix("session:").forEach((key, configJson) -> {
            try {
                var config = JsonUtil.fromJson(SessionConfig.class, configJson);
                var session = super.createSession(config);
                LOGGER.info("Recovered session: {}", session.getSessionId());
            } catch (Exception e) {
                LOGGER.warn("Failed to recover session {}: {}", key, e.getMessage());
            }
        });
    }
}
```

#### 27.3 Remote CLI attachment

```java
package ai.core.cli.remote;

public class RemoteAttach {
    private final String serverUrl;
    private final String apiKey;

    public RemoteAttach(String serverUrl, String apiKey) {
        this.serverUrl = serverUrl;
        this.apiKey = apiKey;
    }

    public void attach(String sessionId) {
        // Connect to SSE endpoint for events
        var sseUrl = serverUrl + "/api/sessions/events?sessionId=" + sessionId;
        var sseClient = new SSEClient(sseUrl, apiKey);
        sseClient.onEvent(event -> renderEvent(event));

        // REPL for input
        var scanner = new Scanner(System.in);
        while (scanner.hasNextLine()) {
            String input = scanner.nextLine();
            if ("/quit".equals(input)) break;

            // Send message via HTTP
            sendMessage(sessionId, input);
        }
        sseClient.close();
    }

    private void sendMessage(String sessionId, String message) {
        var url = serverUrl + "/api/sessions/" + sessionId + "/messages";
        var client = HTTPClient.builder().build();
        var request = new HTTPRequest(HTTPMethod.POST, url);
        request.header("Authorization", "Bearer " + apiKey);
        request.body(JsonUtil.toJson(Map.of("message", message)));
        client.execute(request);
    }
}
```

#### 27.4 CLI `--serve` and `--attach` commands

```java
// In CliApp — add serve and attach modes:
if (args.contains("--serve")) {
    int port = parsePort(args, 8080);
    startServer(port, workspace);
} else if (args.contains("--attach")) {
    String url = parseArg(args, "--attach");
    String sessionId = parseArg(args, "--session");
    new RemoteAttach(url, apiKey).attach(sessionId);
} else {
    // Normal interactive mode
    startInteractive(workspace, config);
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai-server/src/.../discovery/ServiceDiscovery.java` | **New** — mDNS registration/discovery |
| `core-ai-server/src/.../session/PersistentSessionManager.java` | **New** — session persistence |
| `core-ai-cli/src/.../remote/RemoteAttach.java` | **New** — remote CLI attachment |
| `core-ai-cli/src/.../CliApp.java` | Add `--serve` and `--attach` modes |
| `build.gradle.kts` | Add JmDNS dependency |

### Verification

1. Start server with `--serve` → mDNS service registered on LAN
2. Remote CLI `--attach http://server:8080 --session abc` → connects and streams events
3. Server restart → sessions recovered from persistence
4. LAN discovery → finds running server instances
5. Bearer token authentication → unauthorized access rejected

---

## P3-28: Prompt Caching Support

### Problem

System prompts and tool definitions are sent with every LLM call, consuming tokens repeatedly. Anthropic and other providers support prompt caching (`cache_control: ephemeral`) to reduce costs, but core-ai doesn't leverage this.

### Current State

- `Message` class has no `cache_control` field
- `Content` class has no cache directives
- `Usage` class lacks `cache_read_input_tokens` and `cache_creation_input_tokens` (addressed in P3-25)
- `LLMProvider.preprocess()` is the injection point before sending requests
- `model_prices_and_context_window.json` has `supports_prompt_caching` flags per model
- `LLMProviderConfig.requestExtraBody` could carry cache hints but is not structured

### Design

#### 28.1 CacheControl model

```java
package ai.core.llm.domain;

public class CacheControl {
    @Property(name = "type")
    public String type; // "ephemeral"

    public static CacheControl ephemeral() {
        var cc = new CacheControl();
        cc.type = "ephemeral";
        return cc;
    }
}
```

#### 28.2 Extend Content class

```java
// In Content.java — add cache_control field
@Property(name = "cache_control")
public CacheControl cacheControl;

public static Content ofCached(String text) {
    var content = of(text);
    content.cacheControl = CacheControl.ephemeral();
    return content;
}
```

#### 28.3 CacheOptimizationLifecycle

A lifecycle that marks cacheable content before LLM calls:

```java
package ai.core.context;

public class CacheOptimizationLifecycle extends AbstractLifecycle {

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext context) {
        // Check if current model supports caching
        String model = request.model;
        if (!supportsCaching(model)) return;

        // Mark system message content as cacheable
        for (var msg : request.messages) {
            if (msg.role == RoleType.SYSTEM && msg.content != null) {
                var lastContent = msg.content.getLast();
                if (lastContent.cacheControl == null) {
                    lastContent.cacheControl = CacheControl.ephemeral();
                }
            }
        }

        // Mark tool definitions as cacheable (via extra body)
        if (request.tools != null && !request.tools.isEmpty()) {
            // For Anthropic: tools array can have cache_control on last tool
            markLastToolCacheable(request);
        }
    }

    private boolean supportsCaching(String model) {
        if (model == null) return false;
        var info = LLMModelContextRegistry.getModelInfo(model);
        if (info != null) return info.supportsCaching();
        // Heuristic: Claude models support caching
        return model.contains("claude") || model.contains("anthropic");
    }

    private void markLastToolCacheable(CompletionRequest request) {
        // Provider-specific: Anthropic allows cache_control on the last tool definition
        // This is handled by AnthropicProvider in request serialization
        if (request.extraBody == null) request.extraBody = new LinkedHashMap<>();
        request.extraBody.put("_cache_tools", true);
    }
}
```

#### 28.4 AnthropicProvider cache serialization

```java
// In AnthropicProvider.convertToAnthropicFormat() — respect cache_control:

// System messages with cache_control
for (var msg : request.messages) {
    if (msg.role == RoleType.SYSTEM) {
        for (var content : msg.content) {
            var block = new LinkedHashMap<String, Object>();
            block.put("type", "text");
            block.put("text", content.text);
            if (content.cacheControl != null) {
                block.put("cache_control", Map.of("type", content.cacheControl.type));
            }
            system.add(block);
        }
    }
}

// Tool definitions with cache_control on last item
if (Boolean.TRUE.equals(request.extraBody.get("_cache_tools"))) {
    var toolsList = /* converted tools */;
    if (!toolsList.isEmpty()) {
        var lastTool = toolsList.getLast();
        lastTool.put("cache_control", Map.of("type", "ephemeral"));
    }
}
```

#### 28.5 LiteLLMProvider cache support

LiteLLM proxy already supports cache_control passthrough. Ensure Content serialization includes the field:

```java
// In LiteLLMProvider — no special handling needed if Content.cacheControl
// is serialized by Jackson. Just ensure the field is included in JSON output.
// Jackson will serialize @Property(name = "cache_control") automatically.
```

#### 28.6 Registration

```java
// In AgentBuilder.copyValue() — add CacheOptimizationLifecycle before CompressionLifecycle
if (this.cacheOptimizationEnabled) {
    agent.agentLifecycles.add(new CacheOptimizationLifecycle());
}

// In AgentBuilder — builder method
private boolean cacheOptimizationEnabled = true; // enabled by default

public AgentBuilder cacheOptimization(boolean enabled) {
    this.cacheOptimizationEnabled = enabled;
    return this;
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../llm/domain/CacheControl.java` | **New** — cache control model |
| `core-ai/src/.../llm/domain/Content.java` | Add `cacheControl` field |
| `core-ai/src/.../context/CacheOptimizationLifecycle.java` | **New** — auto-mark cacheable content |
| `core-ai/src/.../llm/LLMModelContextRegistry.java` | Load `supports_prompt_caching` flag |
| `core-ai/src/.../llm/providers/AnthropicProvider.java` | Serialize cache_control in Anthropic format |
| `core-ai/src/.../agent/AgentBuilder.java` | Register CacheOptimizationLifecycle |

### Verification

1. Claude model with system prompt → `cache_control: {type: "ephemeral"}` on system content
2. Second turn → `cache_read_input_tokens` > 0 in usage (cache hit)
3. Non-Claude model → no cache_control injected
4. `cacheOptimization(false)` → lifecycle not registered
5. Tool definitions marked cacheable → reduced token cost on repeated calls

---

## Implementation Order

```
1. P3-25  Token Precise Billing      — foundation: extend Usage, load pricing
2. P3-28  Prompt Caching Support     — depends on Usage extensions from P3-25
3. P3-24  Multi Provider Abstraction — new providers, uses cache and billing
4. P3-26  Custom Agent Configuration — standalone, YAML parsing
5. P3-23  Sandbox Isolation          — standalone, process execution
6. P3-27  Remote Service             — extends server, mDNS + persistence
7. P3-22  IDE Integration Protocol   — most complex, WebSocket + IDE events
```
