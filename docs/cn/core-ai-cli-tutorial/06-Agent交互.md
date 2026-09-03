# 06 - Agent 交互

> 🎯 **学习目标**：深入理解 Agent 会话管理的实现，包括 CliAgent 构建、AgentSessionRunner 运行循环、事件监听
> 
> ⏱️ **预计时间**：1.5 天

---

## 📚 本章内容

- [6.1 Agent 交互概述](#61-agent-交互概述)
- [6.2 CliAgent 构建](#62-cliagent-构建)
- [6.3 AgentSessionRunner](#63-agentsessionrunner)
- [6.4 事件监听系统](#64-事件监听系统)
- [6.5 会话命令处理](#65-会话命令处理)
- [6.6 Agent 配置管理](#66-agent-配置管理)
- [6.7 Prompt 注入系统](#67-prompt-注入系统)
- [6.8 验证学习成果](#68-验证学习成果)

---

## 6.1 Agent 交互概述

### 6.1.1 Agent 交互的角色

Agent 交互层是 CLI 的核心，负责：

- 构建和配置 Agent
- 管理会话生命周期
- 处理用户消息和 Agent 响应
- 监听和分发事件
- 管理工具权限
- 处理会话压缩

💡 **设计意图**：将 Agent 的生命周期管理与 UI 层分离，提供灵活的会话控制。

### 6.1.2 核心组件

| 组件 | 位置 | 职责 |
|------|------|------|
| `CliAgent` | `agent/` | Agent 工厂（静态构建） |
| `AgentSessionRunner` | `agent/` | 会话运行器（REPL 循环） |
| `BaseEventListener` | `listener/` | 事件监听基类 |
| `CliEventListener` | `listener/` | CLI 事件监听器 |
| `CommandDispatcher` | `agent/` | 命令分发器 |
| `ModelPicker` | `agent/` | 模型选择器 |

### 6.1.3 数据流

```
用户输入 → AgentSessionRunner.readInputLoop
         → CommandDispatcher.dispatch
         → AgentSession.sendMessage
         → Agent 处理
         → BaseEventListener.on*
         → OutputPanel 渲染
         → 终端显示
```

---

## 6.2 CliAgent 构建

### 6.2.1 类定义

**位置**: `agent/CliAgent.java`

```java
public class CliAgent {
    
    // 静态工厂方法
    public static Agent of(Config config) {
        // 1. 初始化插件管理器
        PluginManager pluginManager = PluginManager.getInstance(
            Path.of(System.getProperty("user.home"), ".core-ai")
        );
        
        // 2. 构建工具注册表
        ToolRegistry toolRegistry = buildToolRegistry(config);
        
        // 3. 加载钩子配置
        HookConfig hookConfig = HookConfig.load(config.workspace());
        ScriptHookLifecycle hookLifecycle = new ScriptHookLifecycle(hookConfig);
        
        // 4. 运行会话开始钩子
        String hookOutput = hookLifecycle.runSessionStartHooks();
        
        // 5. 构建 Prompt 部分
        List<PromptInject> promptSections = constructPromptSection(config, hookOutput);
        
        // 6. 构建 Agent
        Agent.Builder builder = Agent.builder()
            .llmProvider(config.llmProviders().getDefault())
            .maxTurnNumber(config.maxTurn())
            .toolRegistry(toolRegistry)
            .temperature(0.8)
            .persistence(config.persistence());
        
        // 7. 设置模型覆盖
        if (config.model() != null) {
            builder.modelOverride(config.model());
        }
        
        // 8. 设置多模态模型
        if (config.multimodalModel() != null) {
            builder.multimodalModel(config.multimodalModel());
        }
        
        // 9. 构建 Agent
        Agent agent = builder.build();
        
        // 10. 附加追踪收集器（如果已登录）
        if (AuthManager.isLoggedIn()) {
            agent.addLifecycle(new TraceCollectorLifecycle(agent));
        }
        
        // 11. 构建执行上下文
        ExecutionContext context = buildExecutionContext(config);
        agent.setExecutionContext(context);
        
        return agent;
    }
}
```

💡 **说明**：
- 静态工厂方法，接收 `Config` record
- 按顺序初始化各个组件
- 支持钩子、追踪、Prompt 注入

### 6.2.2 Config Record

```java
public record Config(
    LLMProviders llmProviders,           // LLM 提供商
    String model,                         // 模型名称
    Integer maxTurn,                      // 最大轮次
    SessionPersistence persistence,       // 会话持久化
    Path workspace,                       // 工作空间
    AskUserHandler askUserHandler,        // 用户询问处理器
    boolean memoryEnabled,                // 记忆功能
    boolean dailyLogsEnabled,             // 日志功能
    List<RemoteAgent> remoteAgents,       // 远程 Agent
    List<RemoteServer> remoteServers,     // 远程服务器
    List<SubagentConfig> subagentConfigs, // 子 Agent 配置
    List<MediaProvider> mediaProviders,   // 媒体提供商
    ScheduledTaskStore scheduledTaskStore // 定时任务存储
) {}
```

💡 **说明**：
- 包含所有 Agent 配置
- 使用 record 保证不可变性
- 支持远程 Agent、子 Agent、媒体等

### 6.2.3 工具注册表构建

```java
private static ToolRegistry buildToolRegistry(Config config) {
    // 1. 创建基础工具工厂
    ToolRegistryFactory factory = ToolRegistryFactory.create(
        buildFactoryContext(config)
    );
    
    // 2. 注册 MCP 工具
    factory.register(new McpToolProvider());
    
    // 3. 注册技能工具
    SkillConfig skillConfig = buildSkillConfig(config);
    factory.register(new SkillToolProvider(skillConfig));
    
    // 4. 注册 CLI 用户工具
    List<ToolCall> cliTools = cliUserTools(config);
    factory.register(ListToolProvider.of(cliTools));
    
    // 5. 注册媒体工具
    for (MediaProvider provider : config.mediaProviders()) {
        factory.register(provider.getToolProvider());
    }
    
    // 6. 注册远程 Agent 工具
    factory.register(RemoteAgentToolProvider.discover(
        config.remoteAgents(),
        config.remoteServers()
    ));
    
    return factory.build();
}

private static List<ToolCall> cliUserTools(Config config) {
    return List.of(
        new AskUserTool(config.askUserHandler()),
        new AddMcpServerTool(),
        new ScheduledTaskTool(config.scheduledTaskStore())
    );
}
```

💡 **说明**：
- 使用工厂模式构建工具注册表
- 支持多种工具提供器（MCP、技能、远程 Agent）
- CLI 特定工具：`AskUserTool`、`AddMcpServerTool`、`ScheduledTaskTool`

### 6.2.4 执行上下文构建

```java
private static ExecutionContext buildExecutionContext(Config config) {
    return ExecutionContext.builder()
        .workspace(config.workspace())
        .sessionId(generateSessionId())
        .userId(getUserId())
        .customVariables(loadCustomVariables())
        .subagentOutputSinkFactory(
            new FileSubagentOutputSinkFactory(
                Path.of(".core-ai/tasks")
            )
        )
        .todoStoreFactory(
            new FileTodoStoreFactory(
                Path.of(".core-ai/todos")
            )
        )
        .promptSections(constructPromptSection(config, ""))
        .agentProfileRegistry(buildAgentProfileRegistry(config))
        .build();
}
```

💡 **说明**：
- 设置工作空间、会话 ID、用户 ID
- 配置子 Agent 输出和 TODO 存储
- 注入 Prompt 部分和 Agent 配置

### 6.2.5 Agent 配置注册

```java
private static AgentProfileRegistry buildAgentProfileRegistry(Config config) {
    AgentProfileRegistry registry = new AgentProfileRegistry();
    
    // 1. 内置配置提供器
    registry.register(new BuiltinAgentProfileProvider());
    
    // 2. 工作空间配置（优先级 100）
    Path workspaceAgentDir = config.workspace().resolve(".core-ai/agents");
    if (Files.isDirectory(workspaceAgentDir)) {
        registry.register(
            new FilesystemAgentProfileProvider(workspaceAgentDir, 100)
        );
    }
    
    // 3. 用户配置（优先级 50）
    Path userAgentDir = Path.of(System.getProperty("user.home"))
        .resolve(".core-ai/agents");
    if (Files.isDirectory(userAgentDir)) {
        registry.register(
            new FilesystemAgentProfileProvider(userAgentDir, 50)
        );
    }
    
    return registry;
}
```

💡 **说明**：
- 工作空间配置优先级高于用户配置
- 支持内置配置和文件系统配置
- 配置文件为 `*.md` 格式（YAML frontmatter + Markdown）

---

## 6.3 AgentSessionRunner

### 6.3.1 类定义

**位置**: `agent/AgentSessionRunner.java`

```java
public class AgentSessionRunner {
    private final Config config;
    private final TerminalUI ui;
    private final Agent agent;
    
    public AgentSessionRunner(Config config, TerminalUI ui, Agent agent) {
        this.config = config;
        this.ui = ui;
        this.agent = agent;
    }
    
    // 主运行循环
    public String run() {
        // 1. 加载持久化会话
        Session session = loadPersistedSession();
        
        // 2. 创建进程内会话
        InProcessAgentSession agentSession = new InProcessAgentSession(agent, session);
        
        // 3. 创建事件监听器
        CliEventListener listener = new CliEventListener(ui, agentSession);
        agent.addEventListener(listener);
        
        // 4. 设置压缩监听器
        setupCompressionListener(agent, listener);
        
        // 5. 打印 Banner
        BannerPrinter.print(ui);
        
        // 6. 检查升级
        SessionUpgradeHandler.checkInBackground(ui);
        
        // 7. 打印历史
        printSessionHistory(session);
        
        // 8. 启动定时任务计时器
        startScheduleTicker();
        
        // 9. 启动发送线程
        BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        startSenderThread(queue, agentSession, listener);
        
        // 10. 运行输入循环
        readInputLoop(queue);
        
        // 11. 停止计时器
        stopScheduleTicker();
        
        // 12. 保存会话
        saveSession(session);
        
        // 13. 运行会话关闭提取器
        SessionCloseExtractor.onSessionClose(agent, session);
        
        // 14. 关闭会话
        agentSession.close();
        
        // 15. 运行会话停止钩子
        runSessionStopHooks();
        
        // 返回下一个会话 ID（用于 /resume、/clear）
        return getNextSessionId();
    }
}
```

💡 **说明**：
- 15 个步骤的完整生命周期
- 返回下一个会话 ID（支持会话切换）
- 集成钩子、压缩、定时任务

### 6.3.2 输入循环

```java
private void readInputLoop(BlockingQueue<String> queue) {
    // 创建命令处理器
    AgentSessionRunnerCommandHandler sessionHandler = 
        new AgentSessionRunnerCommandHandler(ui, agent, session);
    ReplCommandHandler replHandler = new ReplCommandHandler(ui);
    MemoryCommandHandler memoryHandler = new MemoryCommandHandler(...);
    
    HandlerContext context = new HandlerContext(
        replHandler, memoryHandler, config.memoryEnabled()
    );
    
    // 创建命令分发器
    CommandDispatcher dispatcher = new CommandDispatcher(context, queue);
    
    // 获取输入信号量
    Semaphore readyForInput = new Semaphore(1);
    
    while (true) {
        // 等待可以输入
        readyForInput.acquire();
        
        // 读取输入
        String input = ui.readInput();
        
        if (input == null) {
            // Ctrl+D → 退出
            break;
        }
        
        if (input.isEmpty()) {
            // 空输入 → 继续
            readyForInput.release();
            continue;
        }
        
        if ("/exit".equals(input)) {
            // /exit → 退出
            break;
        }
        
        if ("/upgrade".equals(input)) {
            // /upgrade → 检查升级
            SessionUpgradeHandler.handleUpgrade(ui);
            readyForInput.release();
            continue;
        }
        
        if (input.startsWith("/")) {
            // 斜杠命令 → 分发
            boolean handled = dispatcher.dispatch(input);
            if (!handled) {
                // 未知命令
                readyForInput.release();
            }
            
            // /clear 发送毒丸
            if ("/clear".equals(input)) {
                queue.put(CommandDispatcher.POISON_PILL);
                break;
            }
            
            continue;
        }
        
        // 普通消息 → 展开粘贴和文件引用
        String expanded = PasteBuffer.expand(input);
        expanded = FileReferenceExpander.expand(expanded, config.workspace());
        
        // 发送到队列
        queue.put(expanded);
    }
}
```

💡 **说明**：
- 使用信号量控制输入节奏
- 支持斜杠命令分发
- 展开粘贴缓冲和文件引用
- 毒丸模式退出会话

### 6.3.3 发送线程

```java
private void startSenderThread(
    BlockingQueue<String> queue,
    InProcessAgentSession session,
    CliEventListener listener
) {
    Thread senderThread = new Thread(() -> {
        while (true) {
            try {
                // 从队列获取消息
                String message = queue.take();
                
                // 毒丸 → 退出
                if (CommandDispatcher.POISON_PILL.equals(message)) {
                    break;
                }
                
                // 准备 Turn
                listener.prepareTurn();
                
                // 发送消息
                session.sendMessage(message);
                
                // 等待 Turn 完成
                listener.waitForTurn();
                
                // 释放输入信号量
                readyForInput.release();
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }, "sender-thread");
    
    senderThread.setDaemon(true);
    senderThread.start();
}
```

💡 **说明**：
- 独立的守护线程
- 从队列获取消息并发送
- 使用监听器的 `prepareTurn/waitForTurn` 同步
- 支持毒丸退出

### 6.3.4 定时任务计时器

```java
private void startScheduleTicker() {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    
    scheduleTicker = scheduler.scheduleAtFixedRate(() -> {
        try {
            // 查找到期任务
            List<ScheduledTask> dueTasks = config.scheduledTaskStore()
                .findDueTasks();
            
            for (ScheduledTask task : dueTasks) {
                // 声明任务
                if (config.scheduledTaskStore().claim(task.id())) {
                    // 构建触发消息
                    String trigger = buildTriggerMessage(task);
                    
                    // 发送到队列
                    queue.put(trigger);
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
    }, 30, 30, TimeUnit.SECONDS);  // 每 30 秒检查一次
}

private String buildTriggerMessage(ScheduledTask task) {
    return String.format(
        "[Scheduled Task] %s\n\n%s",
        task.name(),
        task.message()
    );
}
```

💡 **说明**：
- 每 30 秒检查一次到期任务
- 声明任务避免重复执行
- 构建触发消息发送到队列

### 6.3.5 单次运行模式

```java
// 单次运行（非交互式）
public void runPrompt(String prompt, Integer timeLimitSeconds) {
    Session session = loadPersistedSession();
    InProcessAgentSession agentSession = new InProcessAgentSession(agent, session);
    CliEventListener listener = new CliEventListener(ui, agentSession);
    agent.addEventListener(listener);
    
    if (timeLimitSeconds != null) {
        // 带超时的执行
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> future = executor.submit(() -> {
            listener.prepareTurn();
            agentSession.sendMessage(prompt);
            listener.waitForTurn();
        });
        
        try {
            future.get(timeLimitSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // 超时 → 取消 Turn
            agentSession.cancelTurn();
            ui.showError("Time limit exceeded");
        } finally {
            executor.shutdown();
        }
    } else {
        // 无超时
        listener.prepareTurn();
        agentSession.sendMessage(prompt);
        listener.waitForTurn();
    }
    
    saveSession(session);
    agentSession.close();
}
```

💡 **说明**：
- 支持带超时的单次运行
- 超时自动取消 Turn
- 用于脚本化和自动化场景

---

## 6.4 事件监听系统

### 6.4.1 BaseEventListener

**位置**: `listener/BaseEventListener.java`

```java
public class BaseEventListener implements AgentEventListener {
    protected final OutputPanel panel;
    protected CompletableFuture<Void> turnFuture;
    protected final Map<String, RuntimeTask> runtimeTasks;
    
    public BaseEventListener(TerminalUI ui) {
        this.panel = new OutputPanel(ui.getWriter(), ...);
        this.runtimeTasks = new ConcurrentHashMap<>();
    }
    
    // Turn 生命周期
    public void prepareTurn() {
        turnFuture = new CompletableFuture<>();
        panel.beginTurn();
    }
    
    public void waitForTurn() {
        turnFuture.join();
    }
    
    // 文本事件
    @Override
    public void onTextChunk(AgentEvent.TextChunk event) {
        panel.streamText(event.chunk());
    }
    
    @Override
    public void onReasoningChunk(AgentEvent.ReasoningChunk event) {
        panel.streamReasoning(event.chunk());
    }
    
    // 工具事件
    @Override
    public void onBatchToolStart(AgentEvent.BatchToolStart event) {
        // 收集批量调用 ID
        // 显示紧凑摘要：Bash×2, Read×1
        panel.batchStart(formatBatchSummary(event.toolCalls()));
    }
    
    @Override
    public void onToolStart(AgentEvent.ToolStart event) {
        String toolName = event.toolCall().name();
        Map<String, Object> args = event.toolCall().arguments();
        
        // 区分前台/后台任务
        if (event.toolCall() instanceof TaskTool) {
            // 后台任务
            RuntimeTask task = new RuntimeTask(
                event.toolCallId(),
                System.currentTimeMillis(),
                true,  // runInBackground
                0
            );
            runtimeTasks.put(event.toolCallId(), task);
            panel.asyncTaskLaunched(toolName);
        } else {
            // 前台任务
            panel.toolStart(toolName, args);
        }
    }
    
    @Override
    public void onToolResult(AgentEvent.ToolResult event) {
        String toolCallId = event.toolCallId();
        String result = event.result();
        
        RuntimeTask task = runtimeTasks.get(toolCallId);
        if (task != null) {
            // 更新任务计数
            runtimeTasks.put(toolCallId, task.withToolCallCount(task.toolCallCount() + 1));
        }
        
        panel.toolResult(event.toolName(), result);
    }
    
    @Override
    public void onEnvironmentOutput(AgentEvent.EnvironmentOutput event) {
        panel.toolOutputChunk(event.output());
    }
    
    // 权限请求
    @Override
    public void onToolApprovalRequest(AgentEvent.ToolApprovalRequest event) {
        ApprovalDecision decision = ui.askPermission(
            event.toolName(),
            event.arguments(),
            event.suggestedPattern()
        );
        
        event.session().approveToolCall(
            event.toolCallId(),
            decision
        );
    }
    
    // Turn 完成
    @Override
    public void onTurnComplete(AgentEvent.TurnComplete event) {
        panel.endTurn();
        
        // 显示 Turn 摘要
        panel.turnSummary(
            event.elapsedMs(),
            event.inputTokens(),
            event.outputTokens(),
            event.cachedTokens(),
            event.costUsd()
        );
        
        // 如果有后台任务，保持 spinner
        if (!runtimeTasks.isEmpty()) {
            panel.spinnerActive.set(true);
        }
        
        // 完成 Future
        turnFuture.complete(null);
    }
    
    // 任务状态
    @Override
    public void onTaskStatus(AgentEvent.TaskStatus event) {
        panel.taskStatus(event.taskId(), event.status());
    }
    
    // 错误
    @Override
    public void onError(AgentEvent.Error event) {
        panel.error(event.message());
        turnFuture.complete(null);
    }
    
    // 状态变化
    @Override
    public void onStatusChange(AgentEvent.StatusChange event) {
        // 忽略
    }
    
    // 计划更新
    @Override
    public void onPlanUpdate(AgentEvent.PlanUpdate event) {
        panel.planUpdate(event.items());
    }
}

record RuntimeTask(
    String taskId,
    long startTime,
    boolean runInBackground,
    int toolCallCount
) {}
```

💡 **说明**：
- 实现 `AgentEventListener` 接口
- 管理 Turn 生命周期（`prepareTurn/waitForTurn`）
- 区分前台/后台任务
- 使用 `CompletableFuture` 同步
- 集成 `OutputPanel` 渲染

### 6.4.2 CliEventListener

**位置**: `listener/CliEventListener.java`

```java
public class CliEventListener extends BaseEventListener {
    private final TerminalUI ui;
    private final InProcessAgentSession session;
    private Thread escReaderThread;
    private final AtomicLong tokenUsage;
    
    public CliEventListener(TerminalUI ui, InProcessAgentSession session) {
        super(ui);
        this.ui = ui;
        this.session = session;
        this.tokenUsage = new AtomicLong(0);
    }
    
    @Override
    public void prepareTurn() {
        super.prepareTurn();
        
        // 进入 raw 模式（用于 ESC/Ctrl+C 检测）
        ui.enterRawMode();
        
        // 启动 ESC 读取线程
        startEscReader();
        
        // 设置统计信息供应商
        panel.spinner.setStatsSupplier(() -> {
            long tokens = tokenUsage.get();
            return String.format("(%d tokens)", tokens);
        });
    }
    
    @Override
    public void onTurnComplete(AgentEvent.TurnComplete event) {
        // 更新 token 统计
        tokenUsage.addAndGet(event.outputTokens());
        
        // 停止 ESC 读取线程
        stopEscReader();
        
        // 退出 raw 模式
        ui.exitRawMode();
        
        super.onTurnComplete(event);
        
        // 打印详细摘要
        printTurnSummary(event);
    }
    
    private void startEscReader() {
        escReaderThread = new Thread(() -> {
            try {
                // 从 /dev/tty 读取
                InputStream tty = new FileInputStream("/dev/tty");
                
                while (!Thread.interrupted()) {
                    int c = tty.read();
                    
                    if (c == 27) {  // ESC
                        // ESC → 取消 Turn
                        session.cancelTurn();
                        break;
                    } else if (c == 3) {  // Ctrl+C
                        // Ctrl+C → 取消 Turn
                        session.cancelTurn();
                        break;
                    }
                }
            } catch (IOException e) {
                // 忽略
            }
        }, "esc-reader");
        
        escReaderThread.setDaemon(true);
        escReaderThread.start();
    }
    
    private void stopEscReader() {
        if (escReaderThread != null) {
            escReaderThread.interrupt();
            try {
                escReaderThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    private void printTurnSummary(AgentEvent.TurnComplete event) {
        ui.println();
        ui.println(String.format(
            "Time: %ds | Tokens: %d in / %d out (%d cached) | Cost: $%.4f",
            event.elapsedMs() / 1000,
            event.inputTokens(),
            event.outputTokens(),
            event.cachedTokens(),
            event.costUsd()
        ));
    }
}
```

💡 **说明**：
- 继承 `BaseEventListener`
- 添加 raw 模式（ESC/Ctrl+C 检测）
- 独立的 ESC 读取线程
- Token 统计和成本显示

### 6.4.3 RemoteEventListener

**位置**: `listener/RemoteEventListener.java`

```java
public class RemoteEventListener extends BaseEventListener {
    // 用于远程会话，无额外行为
    public RemoteEventListener(TerminalUI ui) {
        super(ui);
    }
}
```

💡 **说明**：
- 用于远程会话
- 继承基类，无额外行为

---

## 6.5 会话命令处理

### 6.5.1 AgentSessionRunnerCommandHandler

**位置**: `agent/AgentSessionRunnerCommandHandler.java`

```java
public class AgentSessionRunnerCommandHandler {
    private final TerminalUI ui;
    private final Agent agent;
    private final Session session;
    
    public AgentSessionRunnerCommandHandler(
        TerminalUI ui, Agent agent, Session session
    ) {
        this.ui = ui;
        this.agent = agent;
        this.session = session;
    }
    
    // /stats - 显示统计信息
    public void handleStats() {
        ui.println();
        ui.println("Session Statistics:");
        ui.println();
        ui.printf("  Messages: %d%n", session.getMessageCount());
        ui.printf("  Tools: %d%n", session.getToolCallCount());
        ui.printf("  Tokens: %d in / %d out%n",
            session.getInputTokens(),
            session.getOutputTokens()
        );
        ui.printf("  Cost: $%.4f%n", session.getCost());
        ui.printf("  Duration: %s%n", formatDuration(session.getDuration()));
        ui.println();
    }
    
    // /tools - 列出工具
    public void handleTools() {
        ui.println();
        ui.println("Available Tools:");
        ui.println();
        
        for (ToolCall tool : agent.getToolRegistry().getAllTools()) {
            ui.printf("  %-20s - %s%n", 
                tool.name(), 
                tool.description()
            );
        }
        
        ui.println();
    }
    
    // /thinking - 设置推理力度
    public void handleThinking(String trimmed) {
        String[] parts = trimmed.split("\\s+");
        
        if (parts.length == 1) {
            // /thinking - 显示交互式选择器
            showThinkingPicker();
            return;
        }
        
        String level = parts[1];
        
        // 解析级别
        ReasoningEffort effort = ReasoningEffort.fromString(level);
        if (effort == null) {
            ui.showError("Invalid level: " + level);
            ui.println("Valid levels: low, none, high, off");
            return;
        }
        
        // 持久化设置
        AgentSessionRunnerHelper.saveReasoningEffort(effort);
        
        // 更新 Agent
        agent.setReasoningEffort(effort);
        
        ui.println("Reasoning effort: " + level);
    }
    
    private void showThinkingPicker() {
        List<String> levels = List.of("low", "none", "high", "off");
        int selected = ui.pickIndex(levels);
        
        if (selected >= 0) {
            handleThinking("/thinking " + levels.get(selected));
        }
    }
    
    // /export - 导出会话
    public void handleExport() {
        String filename = "session-" + LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            + ".md";
        
        Path exportFile = Path.of(filename);
        
        try {
            // 转换为 Markdown
            StringBuilder md = new StringBuilder();
            md.append("# Session Export\n\n");
            md.append("Exported: ").append(LocalDateTime.now()).append("\n\n");
            md.append("---\n\n");
            
            for (Message msg : session.getMessages()) {
                md.append("## ").append(msg.role()).append("\n\n");
                md.append(msg.content()).append("\n\n");
            }
            
            Files.writeString(exportFile, md.toString());
            
            ui.println("Exported to: " + exportFile);
        } catch (IOException e) {
            ui.showError("Failed to export: " + e.getMessage());
        }
    }
    
    // /copy - 复制到剪贴板
    public void handleCopy() {
        // 获取最后一条助手消息
        String lastMessage = session.getLastAssistantMessage();
        if (lastMessage == null) {
            ui.showError("No assistant message to copy");
            return;
        }
        
        // 检测操作系统
        String os = System.getProperty("os.name").toLowerCase();
        
        try {
            ProcessBuilder pb;
            if (os.contains("mac")) {
                pb = new ProcessBuilder("pbcopy");
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("clip");
            } else {
                // Linux: 尝试 wl-copy 或 xclip
                pb = new ProcessBuilder("wl-copy");
                try {
                    pb.start();
                } catch (IOException e) {
                    pb = new ProcessBuilder("xclip", "-selection", "clipboard");
                }
            }
            
            Process process = pb.start();
            process.getOutputStream().write(lastMessage.getBytes());
            process.getOutputStream().close();
            process.waitFor();
            
            ui.println("Copied to clipboard");
        } catch (Exception e) {
            ui.showError("Failed to copy: " + e.getMessage());
        }
    }
    
    // /compact - 压缩对话历史
    public void handleCompact() {
        ui.println("Compressing conversation...");
        
        // 强制压缩
        agent.getCompression().forceCompress();
        
        // 重新保存会话
        session.save();
        
        // 重新加载记忆部分
        MemorySectionManager.reloadAgentMemorySection(agent, memoryProvider);
        
        ui.println("Conversation compressed");
    }
    
    // /undo - 撤销最后消息
    public void handleUndo() {
        // 截断历史到最后一条用户消息
        List<Message> messages = session.getMessages();
        int lastUserIndex = -1;
        
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role().equals("user")) {
                lastUserIndex = i;
                break;
            }
        }
        
        if (lastUserIndex >= 0) {
            // 删除最后一条用户消息及之后的所有消息
            List<Message> truncated = new ArrayList<>(
                messages.subList(0, lastUserIndex)
            );
            session.setMessages(truncated);
            
            ui.println("Undone last message");
        } else {
            ui.showError("No message to undo");
        }
    }
    
    // /resume - 恢复会话
    public void handleResume() {
        showSessionPicker();
    }
    
    private void showSessionPicker() {
        List<Session> sessions = SessionManager.listRecentSessions();
        
        if (sessions.isEmpty()) {
            ui.showError("No sessions to resume");
            return;
        }
        
        List<String> labels = new ArrayList<>();
        for (Session s : sessions) {
            labels.add(String.format(
                "%s - %s",
                s.createdAt(),
                s.getFirstMessage()
            ));
        }
        
        // 分页显示（每页 8 个）
        int selected = ui.pickIndex(labels, 8);
        
        if (selected >= 0) {
            Session selectedSession = sessions.get(selected);
            // 切换到选中的会话
            switchSession(selectedSession.id());
        }
    }
}
```

💡 **说明**：
- 实现会话级命令：`/stats`、`/tools`、`/thinking`、`/export`、`/copy`、`/compact`、`/undo`、`/resume`
- `/copy` 跨平台支持（macOS: pbcopy, Windows: clip, Linux: wl-copy/xclip）
- `/compact` 强制压缩并重新加载记忆
- `/undo` 截断历史
- `/resume` 分页选择器

---

## 6.6 Agent 配置管理

### 6.6.1 ModelPicker

**位置**: `agent/ModelPicker.java`

```java
public class ModelPicker {
    private final TerminalUI ui;
    private final ModelRegistry modelRegistry;
    
    public ModelPicker(TerminalUI ui, ModelRegistry modelRegistry) {
        this.ui = ui;
        this.modelRegistry = modelRegistry;
    }
    
    // 显示模型选择器
    public void showModelPicker() {
        ui.println();
        ui.println("Available Models:");
        ui.println();
        
        List<ModelInfo> models = modelRegistry.getAllModels();
        
        for (int i = 0; i < models.size(); i++) {
            ModelInfo model = models.get(i);
            ui.printf("  %d. [%s] %s%n",
                i + 1,
                model.provider(),
                model.name()
            );
        }
        
        ui.println();
        ui.println("  a) Add model");
        ui.println("  b) New provider");
        ui.println("  c) Remove model");
        ui.println();
        
        String choice = ui.readLine("Select model (1-%d, a/b/c): "
            .formatted(models.size()));
        
        switch (choice.toLowerCase()) {
            case "a" -> handleAddModel();
            case "b" -> handleNewProvider();
            case "c" -> handleRemoveModel();
            default -> {
                try {
                    int index = Integer.parseInt(choice) - 1;
                    if (index >= 0 && index < models.size()) {
                        switchModel(models.get(index));
                    } else {
                        ui.showError("Invalid selection");
                    }
                } catch (NumberFormatException e) {
                    // 尝试按名称匹配
                    ModelInfo model = modelRegistry.findByName(choice);
                    if (model != null) {
                        switchModel(model);
                    } else {
                        ui.showError("Model not found: " + choice);
                    }
                }
            }
        }
    }
    
    private void switchModel(ModelInfo model) {
        // 更新 Agent
        agent.setLlmProvider(model.provider());
        agent.setModel(model.name());
        
        // 持久化
        ProviderConfigurator.saveActiveModel(model.provider(), model.name());
        
        ui.println("Switched to: " + model.name());
    }
    
    private void handleAddModel() {
        String name = ui.readLine("Model name: ");
        String provider = ui.readLine("Provider: ");
        
        modelRegistry.addModel(new ModelInfo(name, provider));
        ProviderConfigurator.saveModel(name, provider);
        
        ui.println("Model added: " + name);
    }
    
    private void handleNewProvider() {
        String providerName = ui.readLine("Provider name: ");
        String baseUrl = ui.readLine("Base URL: ");
        String apiKey = ui.readLine("API Key: ");
        
        ProviderConfigurator.addProvider(providerName, baseUrl, apiKey);
        
        ui.println("Provider added: " + providerName);
    }
    
    private void handleRemoveModel() {
        String name = ui.readLine("Model name to remove: ");
        
        modelRegistry.removeModel(name);
        ProviderConfigurator.removeModel(name);
        
        ui.println("Model removed: " + name);
    }
}
```

💡 **说明**：
- 交互式模型选择器
- 支持添加模型、添加提供商、删除模型
- 持久化到配置文件

### 6.6.2 CreateAgentCommandHandler

**位置**: `agent/CreateAgentCommandHandler.java`

```java
public class CreateAgentCommandHandler {
    private final TerminalUI ui;
    private final Agent agent;
    
    public CreateAgentCommandHandler(TerminalUI ui, Agent agent) {
        this.ui = ui;
        this.agent = agent;
    }
    
    public void handle() {
        ui.println();
        ui.println("Create a new agent profile:");
        ui.println();
        
        // 1. 获取描述
        String description = ui.readLine("Describe your agent: ");
        
        // 2. 使用 LLM 生成配置
        GeneratedConfig config = generateConfig(description);
        
        // 3. 预览配置
        ui.println();
        ui.println("Generated configuration:");
        ui.println();
        ui.printf("  Name: %s%n", config.name());
        ui.printf("  Description: %s%n", config.description());
        ui.println();
        ui.println("System Prompt:");
        ui.println(config.systemPrompt());
        ui.println();
        
        // 4. 确认或编辑
        String choice = ui.readLine("Accept? (y/e/n): ");
        
        switch (choice.toLowerCase()) {
            case "y" -> saveAgent(config);
            case "e" -> {
                // 打开编辑器
                String edited = editConfig(config);
                if (edited != null) {
                    saveAgent(GeneratedConfig.parse(edited));
                }
            }
            default -> ui.println("Cancelled");
        }
    }
    
    private GeneratedConfig generateConfig(String description) {
        // 使用 LLM 生成配置
        String prompt = String.format("""
            Generate an agent configuration based on this description:
            %s
            
            Respond in JSON format:
            {
              "name": "agent-name",
              "description": "short description",
              "systemPrompt": "detailed system prompt"
            }
            """, description);
        
        String response = agent.getLlmProvider().completionFormat(
            prompt,
            GeneratedConfig.class,
            ResponseFormat.jsonObject(),
            null
        );
        
        return GeneratedConfig.parse(response);
    }
    
    private void saveAgent(GeneratedConfig config) {
        // 生成文件名
        String filename = config.name().toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            + ".md";
        
        Path agentFile = Path.of(".core-ai/agents/" + filename);
        
        try {
            Files.createDirectories(agentFile.getParent());
            
            // 构建 Markdown 文件（YAML frontmatter + 系统提示）
            StringBuilder md = new StringBuilder();
            md.append("---\n");
            md.append("name: ").append(config.name()).append("\n");
            md.append("description: ").append(config.description()).append("\n");
            md.append("---\n\n");
            md.append(config.systemPrompt());
            
            Files.writeString(agentFile, md.toString());
            
            // 刷新 AgentProfileRegistry
            AgentProfileRegistry.refresh();
            
            // 更新 UI
            ui.setAgentProfiles(AgentProfileRegistry.getAllProfiles());
            
            ui.println("Agent created: " + config.name());
        } catch (IOException e) {
            ui.showError("Failed to save agent: " + e.getMessage());
        }
    }
    
    private String editConfig(GeneratedConfig config) {
        // 创建临时文件
        try {
            Path tempFile = Files.createTempFile("agent-", ".md");
            Files.writeString(tempFile, config.toYaml());
            
            // 打开编辑器
            String editor = System.getenv("EDITOR");
            if (editor == null) editor = "vim";
            
            new ProcessBuilder(editor, tempFile.toString())
                .inheritIO()
                .start()
                .waitFor();
            
            // 读取编辑后的内容
            return Files.readString(tempFile);
        } catch (Exception e) {
            ui.showError("Failed to edit: " + e.getMessage());
            return null;
        }
    }
}

record GeneratedConfig(
    String name,
    String description,
    String systemPrompt
) {
    public static GeneratedConfig parse(String json) {
        // 解析 JSON
    }
    
    public String toYaml() {
        return String.format("""
            ---
            name: %s
            description: %s
            ---
            
            %s
            """, name, description, systemPrompt);
    }
}
```

💡 **说明**：
- 使用 LLM 生成 Agent 配置
- 支持预览和编辑
- 保存为 Markdown 文件（YAML frontmatter + 系统提示）
- 自动刷新 `AgentProfileRegistry`

---

## 6.7 Prompt 注入系统

### 6.7.1 PromptInject 接口

```java
public interface PromptInject {
    SectionType getSectionType();
    String getContent();
}

public enum SectionType {
    IDENTITY,      // 身份定义
    ENVIRONMENT,   // 环境信息
    INSTRUCTIONS,  // 指令
    MEMORY,        // 记忆
    HOOK           // 钩子输出
}
```

💡 **说明**：
- 定义 Prompt 部分接口
- 5 种部分类型：身份、环境、指令、记忆、钩子

### 6.7.2 CliAgentBasePrompt

```java
public record CliAgentBasePrompt() implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.IDENTITY;
    }
    
    @Override
    public String getContent() {
        return """
            You are a helpful AI coding assistant.
            
            You help users with:
            - Writing and reviewing code
            - Debugging and troubleshooting
            - Answering technical questions
            - Providing explanations and documentation
            
            Be concise, accurate, and helpful.
            """;
    }
}
```

💡 **说明**：
- 基础身份定义
- 简洁明了

### 6.7.3 CliAgentCodeBasePrompt

```java
public record CliAgentCodeBasePrompt() implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.IDENTITY;
    }
    
    @Override
    public String getContent() {
        return """
            You are an expert AI coding assistant.
            
            ## Tone and Style
            - Be concise and direct
            - Use technical language appropriately
            - Provide code examples when helpful
            
            ## Task Management
            - Use write_todos for complex tasks
            - Break down large tasks into steps
            - Track progress explicitly
            
            ## Tool Usage
            - Use tools when appropriate
            - Explain tool usage when needed
            - Handle tool errors gracefully
            
            ## Code References
            - Reference code as file_path:line
            - Provide context for code changes
            - Suggest tests when applicable
            """;
    }
}
```

💡 **说明**：
- 代码 Agent 的详细身份定义
- 包含语气、任务管理、工具使用、代码引用

### 6.7.4 CliAgentEnvironmentPrompt

```java
public record CliAgentEnvironmentPrompt(
    Path workingDir,
    Path workspace,
    boolean isGitRepo,
    String platform,
    LocalDate currentDate
) implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.ENVIRONMENT;
    }
    
    @Override
    public String getContent() {
        return String.format("""
            ## Environment
            
            - Working directory: %s
            - Workspace: %s
            - Git repository: %s
            - Platform: %s
            - Current date: %s
            """,
            workingDir,
            workspace,
            isGitRepo ? "yes" : "no",
            platform,
            currentDate
        );
    }
}
```

💡 **说明**：
- 环境信息：工作目录、工作空间、Git、平台、日期

### 6.7.5 CliAgentGitStatusPrompt

```java
public record CliAgentGitStatusPrompt(
    String branch,
    String status,
    List<String> recentCommits
) implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.ENVIRONMENT;
    }
    
    @Override
    public String getContent() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Git Status\n\n");
        sb.append("- Branch: ").append(branch).append("\n");
        sb.append("- Status:\n```\n").append(status).append("\n```\n");
        sb.append("- Recent commits:\n");
        
        for (String commit : recentCommits) {
            sb.append("  - ").append(commit).append("\n");
        }
        
        return sb.toString();
    }
}
```

💡 **说明**：
- Git 状态：分支、状态、最近提交
- 使用 `git status --short` 和 `git log -5`

### 6.7.6 CliAgentInstructionsPrompt

```java
public record CliAgentInstructionsPrompt(Path workspace) implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.INSTRUCTIONS;
    }
    
    @Override
    public String getContent() {
        // 按优先级加载指令文件
        List<Path> instructionFiles = List.of(
            workspace.resolve(".core-ai/instructions.md"),
            workspace.resolve("AGENTS.md"),
            workspace.resolve("CLAUDE.md")
        );
        
        StringBuilder sb = new StringBuilder();
        sb.append("## Instructions\n\n");
        
        for (Path file : instructionFiles) {
            if (Files.exists(file)) {
                try {
                    String content = Files.readString(file);
                    sb.append(content).append("\n\n");
                } catch (IOException e) {
                    // 忽略
                }
            }
        }
        
        return sb.toString();
    }
}
```

💡 **说明**：
- 加载自定义指令文件
- 优先级：`.core-ai/instructions.md` > `AGENTS.md` > `CLAUDE.md`

### 6.7.7 MemorySystemPrompt

```java
public record MemorySystemPrompt(String memories) implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.MEMORY;
    }
    
    @Override
    public String getContent() {
        return String.format("""
            <memories>
            %s
            </memories>
            
            When extracting memories:
            1. Call get_memory_extraction_spec to get the spec
            2. Follow the spec to extract relevant information
            3. Store memories using the appropriate tools
            """, memories);
    }
}
```

💡 **说明**：
- 注入 `<memories>` 块
- 提供记忆提取指导

### 6.7.8 CliAgentHookPrompt

```java
public record CliAgentHookPrompt(String hookOutput) implements PromptInject {
    
    @Override
    public SectionType getSectionType() {
        return SectionType.HOOK;
    }
    
    @Override
    public String getContent() {
        if (hookOutput == null || hookOutput.isEmpty()) {
            return "";
        }
        
        return String.format("""
            ## Session Start Hook Output
            
            %s
            """, hookOutput);
    }
}
```

💡 **说明**：
- 注入会话开始钩子的输出

---

## 6.8 验证学习成果

### 6.8.1 自测题

1. **CliAgent 使用什么模式构建？**
   - A. 静态工厂方法
   - B. Builder 模式
   - C. 单例模式
   
   **答案**: A

2. **AgentSessionRunner 的主循环使用什么同步机制？**
   - A. synchronized
   - B. BlockingQueue + Semaphore
   - C. wait/notify
   
   **答案**: B

3. **事件监听器如何区分前台/后台任务？**
   - A. 通过工具名称
   - B. 通过 TaskTool 类型检查
   - C. 通过参数
   
   **答案**: B

### 6.8.2 动手实践

1. **查看会话统计**
   ```bash
   > /stats
   ```

2. **列出可用工具**
   ```bash
   > /tools
   ```

3. **设置推理力度**
   ```bash
   > /thinking
   > /thinking high
   ```

4. **导出会话**
   ```bash
   > /export
   ```

5. **复制最后回复**
   ```bash
   > /copy
   ```

6. **压缩对话历史**
   ```bash
   > /compact
   ```

7. **撤销最后消息**
   ```bash
   > /undo
   ```

### 6.8.3 思考题

1. **如何实现多 Agent 协作？**
   
   **提示**：
   - 使用子 Agent（TaskTool）
   - 共享执行上下文
   - 独立的输出空间

2. **如何实现会话的分布式处理？**
   
   **提示**：
   - 使用远程 Agent（A2A 协议）
   - SSE 事件流
   - 分布式会话状态

3. **如何实现更智能的会话压缩？**
   
   **提示**：
   - 使用 LLM 生成摘要
   - 保留关键信息
   - 删除冗余内容

4. **如何实现工具调用的权限管理？**
   
   **提示**：
   - 使用 `ToolPermissionStore`
   - 支持白名单/黑名单
   - 基于模式的匹配

---

## 🎉 本章小结

本章你学会了:

✅ CliAgent 的构建流程  
✅ AgentSessionRunner 的运行循环  
✅ 事件监听系统（BaseEventListener、CliEventListener）  
✅ 会话命令处理（/stats、/tools、/thinking 等）  
✅ Agent 配置管理（ModelPicker、CreateAgentCommandHandler）  
✅ Prompt 注入系统（5 种部分类型）  

---

## 🚀 下一步

准备好进入 [07-认证系统](./07-认证系统.md)，学习认证系统的实现！

---

## 📚 参考资料

- **源码**: `core-ai-cli/src/main/java/ai/core/cli/agent/`
- **源码**: `core-ai-cli/src/main/java/ai/core/cli/listener/`
- **Agent 框架**: `core-ai/src/main/java/ai/core/agent/`

---

*最后更新: 2026-08-31*
