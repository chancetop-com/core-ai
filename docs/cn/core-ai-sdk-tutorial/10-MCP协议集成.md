# 10 - MCP 协议集成

> **学习目标**：深入理解 Model Context Protocol（MCP）协议，掌握 MCP 客户端（消费外部工具）和服务端（暴露内部工具）的使用方式，以及如何接入外部 MCP server。
>
> **预计时间**：1.5 天
>
> **前置要求**：完成 [05-工具系统](./05-工具系统.md)（ToolCall/ToolProvider）

---

## 📋 本章内容

- [10.1 MCP 协议简介](#101-mcp-协议简介)
- [10.2 MCP 客户端：McpClientManager](#102-mcp-客户端mcpclientmanager)
- [10.3 MCP Server 配置](#103-mcp-server-配置)
- [10.4 连接管理与状态](#104-连接管理与状态)
- [10.5 工具消费流程](#105-工具消费流程)
- [10.6 MCP 服务端：McpServerService](#106-mcp-服务端mcpserverservice)
- [10.7 实战示例](#107-实战示例)
- [10.8 验证学习成果](#108-验证学习成果)

---

## 10.1 MCP 协议简介

### 10.1.1 什么是 MCP

**Model Context Protocol（MCP）** 是一个开放协议，用于标准化 AI 模型与外部工具/数据源的交互方式。类似于 USB-C 统一了设备接口，MCP 统一了 AI agent 与工具的接口。

**核心概念**：
- **MCP Host**：AI 应用（如 core-ai）
- **MCP Client**：Host 中的客户端，连接到 MCP Server
- **MCP Server**：提供工具/资源的外部服务
- **Tools**：Server 暴露的可调用函数
- **Resources**：Server 暴露的数据源
- **Prompts**：Server 暴露的提示词模板

💡 **设计意图**：MCP 让 agent 可以无缝接入各种外部能力（如文件系统、GitHub、数据库等），而不需要为每个服务写定制代码。

### 10.1.2 MCP 在 core-ai 中的角色

```
┌─────────────────────────────────────────────────────────────┐
│                      core-ai (Host)                         │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              McpClientManager (Client)                │  │
│  │                                                      │  │
│  │  - 连接到多个 MCP Server                             │  │
│  │  - 消费 Server 暴露的 Tools                          │  │
│  │  - 管理连接状态（自动重连）                           │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  MCP Server 1   │  │  MCP Server 2   │  │  MCP Server 3   │
│  (Filesystem)   │  │  (GitHub)       │  │  (Database)     │
│                 │  │                 │  │                 │
│  Tools:         │  │  Tools:         │  │  Tools:         │
│  - read_file    │  │  - list_repos   │  │  - query        │
│  - write_file   │  │  - create_pr    │  │  - insert       │
│  - list_dir     │  │  - merge_pr     │  │  - update       │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

💡 **设计意图**：
- **统一接口**：所有外部服务通过 MCP 协议暴露，agent 无需关心底层实现
- **动态发现**：MCP Server 暴露的工具可以动态发现，不需要硬编码
- **标准化**：遵循 MCP 规范，可以接入社区已有的 MCP Server

### 10.1.3 MCP vs Skill vs Tool

| 特性 | MCP | Skill | Tool |
|---|---|---|---|
| **来源** | 外部 MCP Server | SKILL.md 文件 | Java 代码 |
| **加载方式** | 运行时连接 | 文件扫描 | 代码注册 |
| **适用场景** | 外部服务集成 | 复杂能力封装 | 简单操作 |
| **动态性** | 高（运行时发现） | 中（启动时加载） | 低（编译时确定） |

💡 **设计意图**：MCP 适合接入外部服务（如 GitHub、数据库），Skill 适合封装复杂能力（如代码审查），Tool 适合简单操作（如执行 bash）。

---

## 10.2 MCP 客户端：McpClientManager

### 10.2.1 文件位置

```
core-ai/src/main/java/ai/core/mcp/client/McpClientManager.java
```

### 10.2.2 核心职责

McpClientManager 是 MCP 客户端的核心管理类，负责：
- **连接管理**：连接到多个 MCP Server
- **状态监控**：跟踪每个 Server 的连接状态
- **自动重连**：断线后自动重连
- **工具消费**：从 Server 获取工具列表
- **优雅关闭**：应用退出时关闭所有连接

### 10.2.3 关键字段

```java
public class McpClientManager implements AutoCloseable {
    // Server 配置
    private final Map<String, McpServerConfig> configs = new ConcurrentHashMap<>();
    
    // 客户端连接（每个 Server 一个）
    private final Map<String, McpClientService> clients = new ConcurrentHashMap<>();
    
    // 连接状态
    private final Map<String, ConnectionState> states = new ConcurrentHashMap<>();
    
    // 锁（用于并发控制）
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    
    // 状态变化监听器
    private final List<ConnectionStateListener> listeners = new CopyOnWriteArrayList<>();
    
    // 连接监控器（自动重连）
    private McpConnectionMonitor connectionMonitor;
    private volatile boolean connectionMonitorInitialized = false;
    
    // Shutdown hook
    private Thread shutdownHook;
    private volatile boolean closed = false;
}
```

💡 **设计意图**：
- **ConcurrentHashMap**：支持并发访问，多个线程可以同时操作不同的 Server
- **ConnectionState**：跟踪每个 Server 的连接状态（CONNECTED/DISCONNECTED/CONNECTING）
- **connectionMonitor**：定期检查连接状态，断线后自动重连
- **shutdownHook**：JVM 退出时自动关闭连接

### 10.2.4 创建方式

**方式 1：从配置文件创建**

```java
// 从 Map 配置创建
var config = Map.of(
    "filesystem", Map.of(
        "command", "npx",
        "args", List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"),
        "env", Map.of()
    ),
    "github", Map.of(
        "command", "npx",
        "args", List.of("-y", "@modelcontextprotocol/server-github"),
        "env", Map.of("GITHUB_TOKEN", "xxx")
    )
);

var manager = McpClientManager.fromConfig(config);
```

**方式 2：编程方式创建**

```java
// 创建 Server 配置
var filesystemConfig = McpServerConfig.builder()
    .name("filesystem")
    .command("npx")
    .args(List.of("-y", "@modelcontextprotocol/server-filesystem", "/tmp"))
    .build();

var githubConfig = McpServerConfig.builder()
    .name("github")
    .command("npx")
    .args(List.of("-y", "@modelcontextprotocol/server-github"))
    .env(Map.of("GITHUB_TOKEN", "xxx"))
    .build();

// 创建 Manager
var manager = McpClientManager.of(filesystemConfig, githubConfig);
```

💡 **设计意图**：
- **配置文件方式**：适合从 `application.yml` 加载配置
- **编程方式**：适合动态创建（如测试）

### 10.2.5 核心方法

```java
public class McpClientManager {
    /**
     * 添加 Server
     */
    public void addServer(McpServerConfig config) {
        String name = config.getName();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Server config must have a name");
        }
        configs.put(name, config);
        states.put(name, ConnectionState.NOT_CONNECTED);
        locks.put(name, new Object());
        getConnectionMonitor().addServer(name);
        LOGGER.debug("Added MCP server config: {}", name);
    }
    
    /**
     * 移除 Server
     */
    public void removeServer(String serverName) {
        getConnectionMonitor().removeServer(serverName);
        closeClient(serverName);
        configs.remove(serverName);
        states.remove(serverName);
        locks.remove(serverName);
        LOGGER.debug("Removed MCP server: {}", serverName);
    }
    
    /**
     * 获取所有 Server 名称
     */
    public Set<String> getServerNames() {
        return Set.copyOf(configs.keySet());
    }
    
    /**
     * 获取连接状态
     */
    public ConnectionState getState(String serverName) {
        return states.getOrDefault(serverName, ConnectionState.NOT_CONNECTED);
    }
    
    /**
     * 获取 Server 提供的工具列表
     */
    public List<McpSchema.Tool> getTools(String serverName) {
        var client = clients.get(serverName);
        if (client == null || !isConnected(serverName)) {
            return List.of();
        }
        return client.getTools();
    }
    
    /**
     * 调用工具
     */
    public ToolCallResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        var client = clients.get(serverName);
        if (client == null || !isConnected(serverName)) {
            return ToolCallResult.error("MCP server not connected: " + serverName);
        }
        return client.callTool(toolName, arguments);
    }
    
    /**
     * 关闭所有连接
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        
        // 移除 shutdown hook
        if (shutdownHook != null) {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        }
        
        // 关闭所有客户端
        for (var serverName : clients.keySet()) {
            closeClient(serverName);
        }
        
        LOGGER.info("McpClientManager closed");
    }
    
    /**
     * 注册 shutdown hook（JVM 退出时自动关闭）
     */
    private void registerShutdownHook() {
        shutdownHook = new Thread(() -> {
            LOGGER.info("Shutdown hook triggered, closing MCP connections...");
            close();
        }, "McpClientManager-ShutdownHook");
        
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
}
```

💡 **设计意图**：
- **addServer/removeServer**：动态添加/移除 Server
- **getTools**：从 Server 获取工具列表（用于动态发现）
- **callTool**：调用 Server 暴露的工具
- **close**：优雅关闭，释放资源
- **registerShutdownHook**：JVM 退出时自动关闭，避免资源泄漏

---

## 10.3 MCP Server 配置

### 10.3.1 McpServerConfig 类定义

```java
public class McpServerConfig {
    private String name;                    // Server 名称（唯一标识）
    private String command;                 // 启动命令（如 "npx"）
    private List<String> args;              // 命令参数
    private Map<String, String> env;        // 环境变量
    private String cwd;                     // 工作目录（可选）
    private Long timeoutMs;                 // 超时时间（可选）
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static McpServerConfig fromMap(String name, Map<String, Object> map) {
        var builder = builder().name(name);
        
        if (map.containsKey("command")) {
            builder.command((String) map.get("command"));
        }
        if (map.containsKey("args")) {
            builder.args((List<String>) map.get("args"));
        }
        if (map.containsKey("env")) {
            builder.env((Map<String, String>) map.get("env"));
        }
        if (map.containsKey("cwd")) {
            builder.cwd((String) map.get("cwd"));
        }
        if (map.containsKey("timeoutMs")) {
            builder.timeoutMs(((Number) map.get("timeoutMs")).longValue());
        }
        
        return builder.build();
    }
    
    public static class Builder {
        private String name;
        private String command;
        private List<String> args = List.of();
        private Map<String, String> env = Map.of();
        private String cwd;
        private Long timeoutMs;
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder command(String command) {
            this.command = command;
            return this;
        }
        
        public Builder args(List<String> args) {
            this.args = args;
            return this;
        }
        
        public Builder env(Map<String, String> env) {
            this.env = env;
            return this;
        }
        
        public Builder cwd(String cwd) {
            this.cwd = cwd;
            return this;
        }
        
        public Builder timeoutMs(Long timeoutMs) {
            this.timeoutMs = timeoutMs;
            return this;
        }
        
        public McpServerConfig build() {
            var config = new McpServerConfig();
            config.name = name;
            config.command = command;
            config.args = args;
            config.env = env;
            config.cwd = cwd;
            config.timeoutMs = timeoutMs;
            return config;
        }
    }
}
```

💡 **设计意图**：
- **command + args**：启动 MCP Server 的命令（通常是 `npx` 或 `node`）
- **env**：传递给 Server 的环境变量（如 API key）
- **cwd**：Server 的工作目录（可选）
- **timeoutMs**：调用超时时间（可选）

### 10.3.2 配置文件格式

```yaml
# application.yml
mcp:
  servers:
    filesystem:
      command: npx
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
      env: {}
    
    github:
      command: npx
      args: ["-y", "@modelcontextprotocol/server-github"]
      env:
        GITHUB_TOKEN: ${GITHUB_TOKEN}
    
    database:
      command: node
      args: ["./mcp-servers/database-server.js"]
      env:
        DATABASE_URL: ${DATABASE_URL}
      cwd: /opt/mcp-servers
      timeoutMs: 30000
```

💡 **设计意图**：
- **YAML 格式**：易于阅读和编辑
- **环境变量替换**：`${GITHUB_TOKEN}` 会被替换为实际的环境变量值
- **灵活配置**：支持 command/args/env/cwd/timeoutMs 等参数

---

## 10.4 连接管理与状态

### 10.4.1 ConnectionState 枚举

```java
public enum ConnectionState {
    NOT_CONNECTED,      // 未连接
    CONNECTING,         // 连接中
    CONNECTED,          // 已连接
    DISCONNECTED,       // 已断开
    ERROR               // 错误
}
```

### 10.4.2 连接状态流转

```
NOT_CONNECTED
  │
  │ addServer()
  ▼
CONNECTING
  │
  ├─ 成功
  │    ▼
  │  CONNECTED ←──────┐
  │    │              │
  │    │ 断线         │ 重连成功
  │    ▼              │
  │  DISCONNECTED ────┘
  │
  └─ 失败
       ▼
     ERROR
```

💡 **设计意图**：
- **NOT_CONNECTED**：初始状态，尚未尝试连接
- **CONNECTING**：正在建立连接
- **CONNECTED**：已连接，可以调用工具
- **DISCONNECTED**：连接断开，等待重连
- **ERROR**：连接出错，需要人工干预

### 10.4.3 自动重连机制

```java
public class McpConnectionMonitor {
    private final ScheduledExecutorService scheduler = 
        Executors.newScheduledThreadPool(1);
    
    public McpConnectionMonitor(...) {
        // 每 30 秒检查一次连接状态
        scheduler.scheduleAtFixedRate(this::checkConnections, 0, 30, TimeUnit.SECONDS);
    }
    
    private void checkConnections() {
        for (var serverName : configs.keySet()) {
            var state = getState(serverName);
            
            if (state == ConnectionState.DISCONNECTED || state == ConnectionState.ERROR) {
                // 尝试重连
                reconnect(serverName);
            }
        }
    }
    
    private void reconnect(String serverName) {
        var config = configs.get(serverName);
        var lock = locks.get(serverName);
        
        synchronized (lock) {
            try {
                // 关闭旧连接
                closeClient(serverName);
                
                // 更新状态
                states.put(serverName, ConnectionState.CONNECTING);
                notifyListeners(serverName, ConnectionState.CONNECTING);
                
                // 创建新连接
                var client = new McpClientService(config);
                client.connect();
                
                // 更新状态
                clients.put(serverName, client);
                states.put(serverName, ConnectionState.CONNECTED);
                notifyListeners(serverName, ConnectionState.CONNECTED);
                
                LOGGER.info("Reconnected to MCP server: {}", serverName);
            } catch (Exception e) {
                states.put(serverName, ConnectionState.ERROR);
                notifyListeners(serverName, ConnectionState.ERROR);
                LOGGER.warn("Failed to reconnect to MCP server: {}", serverName, e);
            }
        }
    }
}
```

💡 **设计意图**：
- **定时检查**：每 30 秒检查一次连接状态
- **自动重连**：断线后自动尝试重连
- **状态通知**：连接状态变化时通知监听者（如 UI 更新）
- **并发安全**：用 synchronized 保证重连操作的原子性

---

## 10.5 工具消费流程

### 10.5.1 McpToolProvider

```java
public class McpToolProvider implements ToolProvider {
    private final McpClientManager clientManager;
    
    public McpToolProvider(McpClientManager clientManager) {
        this.clientManager = clientManager;
    }
    
    @Override
    public String id() {
        return "mcp";
    }
    
    @Override
    public Map<String, ToolCall> provide() {
        var result = new LinkedHashMap<String, ToolCall>();
        
        // 从所有连接的 Server 获取工具
        for (var serverName : clientManager.getServerNames()) {
            if (clientManager.getState(serverName) == ConnectionState.CONNECTED) {
                var tools = clientManager.getTools(serverName);
                
                for (var tool : tools) {
                    var toolCall = new McpToolCall(serverName, tool);
                    result.put(toolCall.getName(), toolCall);
                }
            }
        }
        
        return result;
    }
    
    @Override
    public int priority() {
        return 500;  // 中等优先级
    }
    
    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.EVERY_TURN;  // 每次 turn 都刷新（工具可能变化）
    }
}
```

💡 **设计意图**：
- **动态发现**：每次 `provide()` 都从 Server 获取最新工具列表
- **EVERY_TURN**：工具列表可能变化（Server 动态注册/注销工具），所以每次 turn 都刷新
- **优先级 500**：中等优先级，高于内置工具（1000），低于用户自定义（100）

### 10.5.2 McpToolCall

```java
public class McpToolCall extends ToolCall {
    private final String serverName;
    private final McpSchema.Tool mcpTool;
    
    public McpToolCall(String serverName, McpSchema.Tool mcpTool) {
        this.serverName = serverName;
        this.mcpTool = mcpTool;
        
        // 设置工具属性
        this.name = mcpTool.name;
        this.description = mcpTool.description;
        this.parameters = convertParameters(mcpTool.inputSchema);
        this.sourceType = "mcp";
    }
    
    @Override
    public ToolCallResult execute(String arguments) {
        var args = parseArguments(arguments);
        
        // 调用 MCP Server 的工具
        return clientManager.callTool(serverName, mcpTool.name, args);
    }
    
    private List<ToolCallParameter> convertParameters(McpSchema.JsonSchema schema) {
        // 把 MCP 的 JSON Schema 转换成 core-ai 的 ToolCallParameter
        // ...
    }
}
```

💡 **设计意图**：
- **McpToolCall**：把 MCP Server 暴露的工具转换成 core-ai 的 ToolCall
- **execute()**：调用 MCP Server 的工具，返回结果
- **sourceType = "mcp"**：标记工具来源，方便追踪

### 10.5.3 工具消费流程

```
Agent.turn()
  │
  ├─ 1. toolRegistry.materialize()
  │      │
  │      ├─ 调用 McpToolProvider.provide()
  │      │      │
  │      │      ├─ 遍历所有连接的 Server
  │      │      ├─ 获取每个 Server 的工具列表
  │      │      └─ 转换成 ToolCall 列表
  │      │
  │      └─ 合并所有工具（按优先级覆盖）
  │
  ├─ 2. LLM 选择工具
  │      └─ 返回 FunctionCall（工具名 + 参数）
  │
  └─ 3. ToolOrchestration.execute()
         │
         └─ McpToolCall.execute()
                │
                └─ clientManager.callTool(serverName, toolName, args)
                       │
                       ├─ 发送 JSON-RPC 请求到 MCP Server
                       ├─ 等待响应
                       └─ 返回 ToolCallResult
```

---

## 10.6 MCP 服务端：McpServerService

### 10.6.1 文件位置

```
core-ai/src/main/java/ai/core/mcp/server/McpServerService.java
```

### 10.6.2 核心职责

McpServerService 把 core-ai 内部的工具暴露为 MCP Server，供外部 agent 调用：

```
┌─────────────────────────────────────────────────────────────┐
│                  外部 Agent (MCP Client)                    │
│                                                             │
│  - Claude Code                                              │
│  - Cursor                                                   │
│  - 其他 MCP 客户端                                          │
└─────────────────────────────────────────────────────────────┘
         │
         │ MCP 协议（JSON-RPC over HTTP/SSE）
         ▼
┌─────────────────────────────────────────────────────────────┐
│              core-ai (MCP Server)                           │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              McpServerService                         │  │
│  │                                                      │  │
│  │  - 暴露内部工具为 MCP Tools                          │  │
│  │  - 处理 JSON-RPC 请求                                │  │
│  │  - 返回工具执行结果                                  │  │
│  └──────────────────────────────────────────────────────┘  │
│                                                             │
│  ┌──────────────────────────────────────────────────────┐  │
│  │              ToolRegistry                             │  │
│  │                                                      │  │
│  │  - 内部工具注册表                                     │  │
│  │  - bash, read_file, write_file, ...                  │  │
│  └──────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

💡 **设计意图**：
- **双向集成**：core-ai 既可以消费外部 MCP Server 的工具（Client），也可以暴露内部工具给外部（Server）
- **标准化**：遵循 MCP 规范，可以被任何 MCP 客户端调用
- **复用内部工具**：不需要重新实现，直接暴露 ToolRegistry 中的工具

### 10.6.3 核心方法

```java
public class McpServerService {
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    
    /**
     * 处理 tools/list 请求
     * 返回所有可用工具
     */
    public List<McpSchema.Tool> listTools() {
        return toolRegistry.getToolCalls().stream()
            .map(tool -> {
                var mcpTool = new McpSchema.Tool();
                mcpTool.name = tool.getName();
                mcpTool.description = tool.getDescription();
                mcpTool.inputSchema = convertToJsonSchema(tool.getParameters());
                return mcpTool;
            })
            .toList();
    }
    
    /**
     * 处理 tools/call 请求
     * 调用指定工具
     */
    public McpSchema.CallToolResult callTool(String toolName, Map<String, Object> arguments) {
        var tool = toolRegistry.getToolCall(toolName);
        if (tool == null) {
            return McpSchema.CallToolResult.error("Tool not found: " + toolName);
        }
        
        var result = toolExecutor.execute(tool, JsonUtil.toJson(arguments), null);
        
        return new McpSchema.CallToolResult(
            List.of(new McpSchema.TextContent(result.content)),
            result.isError
        );
    }
    
    private McpSchema.JsonSchema convertToJsonSchema(List<ToolCallParameter> parameters) {
        // 把 core-ai 的 ToolCallParameter 转换成 MCP 的 JSON Schema
        // ...
    }
}
```

💡 **设计意图**：
- **listTools()**：返回所有工具定义，供客户端发现
- **callTool()**：调用指定工具，返回执行结果
- **JSON Schema 转换**：把 core-ai 的参数定义转换成 MCP 规范的 JSON Schema

### 10.6.4 HTTP 端点

McpServerService 通过 HTTP 端点暴露 MCP 协议：

```
POST /mcp/tools/list       ← 获取工具列表
POST /mcp/tools/call       ← 调用工具
```

💡 **设计意图**：
- **HTTP 端点**：遵循 MCP 规范的 HTTP 传输层
- **JSON-RPC**：请求/响应格式遵循 JSON-RPC 2.0 规范
- **SSE**：支持 Server-Sent Events（用于流式响应）

---

## 10.7 实战示例

### 10.7.1 配置 MCP Server（application.yml）

```yaml
mcp:
  servers:
    filesystem:
      command: npx
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]
    
    github:
      command: npx
      args: ["-y", "@modelcontextprotocol/server-github"]
      env:
        GITHUB_TOKEN: ${GITHUB_TOKEN}
```

### 10.7.2 使用 MCP 工具

```java
// 1. 从配置创建 McpClientManager
var manager = McpClientManager.fromConfig(mcpConfig);

// 2. 等待连接建立
Thread.sleep(5000);  // 实际应用中应该用 listener 监听连接状态

// 3. 创建 McpToolProvider
var mcpToolProvider = new McpToolProvider(manager);

// 4. 注册到 ToolRegistry
var toolRegistry = new ToolRegistry();
toolRegistry.registerProvider(mcpToolProvider);

// 5. 创建 Agent
var agent = Agent.builder()
    .name("mcp-agent")
    .systemPrompt("你是一个助手，可以使用文件系统工具")
    .llmProvider(llmProvider)
    .toolRegistry(toolRegistry)
    .build();

// 6. 执行
var output = agent.execute("读取 /tmp/test.txt 的内容", null);
System.out.println(output);
// Agent 会调用 filesystem server 的 read_file 工具
```

### 10.7.3 监听连接状态

```java
manager.addConnectionListener((serverName, newState) -> {
    System.out.printf("Server %s state changed to %s%n", serverName, newState);
    
    if (newState == ConnectionState.CONNECTED) {
        var tools = manager.getTools(serverName);
        System.out.printf("Available tools: %s%n", 
            tools.stream().map(t -> t.name).toList());
    }
});
```

### 10.7.4 暴露工具为 MCP Server

```java
// 1. 创建内部工具
var bashTool = new BashTool();
var readFileTool = new ReadFileTool();

// 2. 注册到 ToolRegistry
var toolRegistry = new ToolRegistry();
toolRegistry.registerProvider(new ListToolProvider("builtin", List.of(bashTool, readFileTool)));

// 3. 创建 McpServerService
var mcpServerService = new McpServerService(toolRegistry, toolExecutor);

// 4. 注册 HTTP 端点（在 core-ai-server 中）
http().route("POST", "/mcp/tools/list", (req, resp) -> {
    var tools = mcpServerService.listTools();
    resp.json(tools);
});

http().route("POST", "/mcp/tools/call", (req, resp) -> {
    var request = req.json(McpCallToolRequest.class);
    var result = mcpServerService.callTool(request.toolName, request.arguments);
    resp.json(result);
});
```

💡 **设计意图**：
- **暴露内部工具**：外部 agent 可以通过 MCP 协议调用 core-ai 内部的工具
- **HTTP 端点**：遵循 MCP 规范的 HTTP 传输层
- **JSON-RPC**：请求/响应格式遵循 JSON-RPC 2.0 规范

---

## 10.8 验证学习成果

完成本章后，你应该能：

### ✅ 必须掌握

- [ ] 说出 MCP 协议的三个核心概念（Host/Client/Server）
- [ ] 说出 McpClientManager 的核心职责（连接管理/状态监控/工具消费）
- [ ] 说出 MCP Server 配置的 5 个参数（name/command/args/env/cwd）
- [ ] 说出连接状态的 5 种类型（NOT_CONNECTED/CONNECTING/CONNECTED/DISCONNECTED/ERROR）
- [ ] 说出 McpToolProvider 的优先级和刷新策略（500 / EVERY_TURN）
- [ ] 说出 McpServerService 的两个核心方法（listTools/callTool）
- [ ] 能配置并使用 MCP Server

### 🔧 动手实践

1. **配置 MCP Server**：

在 `application.yml` 中配置 filesystem 和 github 两个 MCP Server。

2. **使用 MCP 工具**：

创建 Agent，注册 McpToolProvider，调用 filesystem 工具读取文件。

3. **监听连接状态**：

添加 ConnectionListener，打印连接状态变化。

### 📝 自测题

1. MCP 协议的作用是什么？
   - A. 统一 AI 模型与外部工具的接口
   - B. 加速 LLM 推理
   - C. 加密通信
   
   **答案**：A（统一 AI 模型与外部工具的接口）

2. McpToolProvider 的刷新策略是什么？
   - A. ONCE
   - B. EVERY_TURN
   - C. MANUAL
   
   **答案**：B（EVERY_TURN，因为工具列表可能变化）

3. McpServerService 的作用是什么？
   - A. 消费外部 MCP Server 的工具
   - B. 暴露内部工具为 MCP Server
   - C. 管理连接状态
   
   **答案**：B（暴露内部工具为 MCP Server）

---

## 🎉 本章小结

本章你学会了：

- ✅ MCP 协议的核心概念（Host/Client/Server/Tools/Resources/Prompts）
- ✅ McpClientManager 的核心职责（连接管理/状态监控/自动重连/工具消费）
- ✅ MCP Server 配置（name/command/args/env/cwd/timeoutMs）
- ✅ 连接状态管理（5 种状态 + 自动重连机制）
- ✅ 工具消费流程（McpToolProvider → McpToolCall → clientManager.callTool）
- ✅ McpServerService（暴露内部工具为 MCP Server）
- ✅ 实战示例（配置/使用/监听/暴露）

---

## 🎊 本批次完成！

恭喜你完成了进阶篇的前三章（08-10）！

你已经掌握了：
- ✅ Flow 编排（DAG 工作流、节点类型、条件分支）
- ✅ Skill 系统（SKILL.md 格式、加载机制、转工具）
- ✅ MCP 协议集成（Client/Server、工具消费/暴露）

---

## 🚀 下一批次

下一批将写 **11-Session管理**、**12-遥测与可观测**、**13-持久化机制**，覆盖：

| 章节 | 主题 | 核心文件 | 学习目标 |
|---|---|---|---|
| **11** | Session 管理 | `InProcessAgentSession`、`TurnDriver`、`PermissionGate` | 会话管理、turn 调度、权限门控 |
| **12** | 遥测与可观测 | `Tracer`、`LLMTracer`、`AgentTracer` | OpenTelemetry 集成、全链路追踪 |
| **13** | 持久化机制 | `PersistenceProvider`、`Persistence` | 持久化 SPI、File/Redis/Temporary 实现 |

预计字数：约 30,000 字

---

*最后更新：2026-08-31*
