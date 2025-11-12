# 教程：构建智能代理（Agent）

本教程将深入介绍如何使用 Core-AI 构建功能强大的智能代理。

## 目录

1. [代理基础概念](#代理基础概念)
2. [创建基本代理](#创建基本代理)
3. [系统提示和模板](#系统提示和模板)
4. [记忆系统](#记忆系统)
5. [反思机制](#反思机制)
6. [状态管理](#状态管理)
7. [最佳实践](#最佳实践)

## 代理基础概念

### 什么是代理（Agent）？

在 Core-AI 中，代理是一个自主的 AI 实体，它可以：
- 理解和响应自然语言输入
- 调用工具完成任务
- 维护对话上下文和记忆
- 反思和改进自己的响应

### 代理的核心组件

```java
Agent agent = Agent.builder()
    .name("agent-name")           // 代理名称
    .description("agent purpose")  // 代理描述
    .llmProvider(provider)         // LLM 提供商
    .systemPrompt(prompt)          // 系统提示
    .tools(toolList)               // 可用工具
    .memory(memorySystem)          // 记忆系统
    .enableReflection(true)        // 反思能力
    .build();
```

## 创建基本代理

### 1. 最简单的代理

```java
import ai.core.agent.Agent;
import ai.core.agent.AgentOutput;
import ai.core.llm.LLMProvider;

public class BasicAgentExample {

    public Agent createSimpleAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("simple-assistant")
            .description("一个简单的助手")
            .llmProvider(llmProvider)
            .build();
    }

    public void useAgent() {
        Agent agent = createSimpleAgent(llmProvider);

        // 执行查询
        AgentOutput output = agent.execute("你好，请介绍一下自己");
        System.out.println(output.getOutput());

        // 检查状态
        if (output.getStatus() == NodeStatus.COMPLETED) {
            System.out.println("执行成功");
        }
    }
}
```

### 2. 配置丰富的代理

```java
public class ConfiguredAgentExample {

    public Agent createConfiguredAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("customer-service")
            .description("客户服务代理")
            .llmProvider(llmProvider)

            // 基本配置
            .systemPrompt("你是一个专业的客户服务代表...")
            .maxTokens(2000)
            .temperature(0.7)
            .topP(0.95)

            // 高级特性
            .streaming(true)           // 流式输出
            .enableReflection(true)    // 启用反思
            .maxReflectionDepth(2)     // 反思深度

            // 重试策略
            .maxRetries(3)
            .retryDelay(1000)          // 毫秒

            .build();
    }
}
```

## 系统提示和模板

### 1. 静态系统提示

```java
Agent agent = Agent.builder()
    .name("technical-writer")
    .llmProvider(llmProvider)
    .systemPrompt("""
        你是一个专业的技术文档编写专家。

        你的职责：
        1. 编写清晰、准确的技术文档
        2. 使用简洁的语言解释复杂概念
        3. 提供实用的代码示例

        写作风格：
        - 使用主动语态
        - 保持客观中立
        - 结构化组织内容

        请始终用中文回答。
        """)
    .build();
```

### 2. 动态模板（Mustache）

```java
import ai.core.prompt.PromptTemplate;

public class TemplatedAgentExample {

    public Agent createTemplatedAgent(LLMProvider llmProvider) {
        // 创建提示模板
        String template = """
            你是 {{company}} 的 AI 助手。

            公司信息：
            - 行业：{{industry}}
            - 主要产品：{{products}}
            - 服务时间：{{serviceHours}}

            当前时间：{{currentTime}}
            用户位置：{{userLocation}}

            请根据以上信息为用户提供个性化服务。
            """;

        // 准备模板数据
        Map<String, Object> templateData = Map.of(
            "company", "科技创新公司",
            "industry", "人工智能",
            "products", "AI 解决方案",
            "serviceHours", "9:00-18:00",
            "currentTime", LocalDateTime.now().toString(),
            "userLocation", "北京"
        );

        // 渲染模板
        PromptTemplate promptTemplate = new PromptTemplate(template);
        String renderedPrompt = promptTemplate.render(templateData);

        return Agent.builder()
            .name("templated-agent")
            .llmProvider(llmProvider)
            .systemPrompt(renderedPrompt)
            .build();
    }
}
```

### 3. 高级模板使用

```java
public class AdvancedTemplateExample {

    public Agent createContextAwareAgent(
            LLMProvider llmProvider,
            UserContext userContext) {

        // 复杂模板，包含条件逻辑
        String template = """
            你是一个智能助手。

            {{#isPremiumUser}}
            用户是高级会员，请提供优先服务。
            可用高级功能：
            {{#premiumFeatures}}
            - {{.}}
            {{/premiumFeatures}}
            {{/isPremiumUser}}

            {{^isPremiumUser}}
            用户是普通会员。
            {{/isPremiumUser}}

            用户偏好：
            - 语言：{{language}}
            - 专业水平：{{expertiseLevel}}

            {{#hasHistory}}
            历史互动摘要：
            {{historyS- ummary}}
            {{/hasHistory}}
            """;

        Map<String, Object> data = new HashMap<>();
        data.put("isPremiumUser", userContext.isPremium());
        data.put("premiumFeatures", List.of(
            "优先响应",
            "高级分析",
            "定制化建议"
        ));
        data.put("language", userContext.getLanguage());
        data.put("expertiseLevel", userContext.getExpertiseLevel());
        data.put("hasHistory", userContext.hasHistory());
        data.put("historySummary", userContext.getHistorySummary());

        PromptTemplate template = new PromptTemplate(template);

        return Agent.builder()
            .name("context-aware-agent")
            .llmProvider(llmProvider)
            .systemPrompt(template.render(data))
            .promptTemplate(template)  // 保存模板以供动态更新
            .promptData(data)           // 保存数据以供更新
            .build();
    }
}
```

## 记忆系统

### 1. 短期记忆（会话记忆）

```java
import ai.core.memory.NaiveMemory;

public class MemoryAgentExample {

    public void demonstrateShortTermMemory() {
        // 代理默认维护会话历史
        Agent agent = Agent.builder()
            .name("memory-agent")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个有记忆的助手")
            .build();

        // 第一次对话
        AgentOutput output1 = agent.execute("我叫张三");
        System.out.println(output1.getOutput());
        // 输出：你好张三！很高兴认识你。

        // 第二次对话（记住了名字）
        AgentOutput output2 = agent.execute("你还记得我的名字吗？");
        System.out.println(output2.getOutput());
        // 输出：当然记得，你叫张三。

        // 获取会话历史
        List<Message> history = agent.getMessages();
        System.out.println("对话轮次：" + history.size());
    }
}
```

### 2. 长期记忆

```java
import ai.core.memory.Memory;
import ai.core.memory.NaiveMemory;

public class LongTermMemoryExample {

    public Agent createAgentWithLongTermMemory() {
        // 创建长期记忆系统
        Memory longTermMemory = new NaiveMemory();

        // 预加载记忆
        longTermMemory.save("user_preferences", Map.of(
            "name", "张三",
            "role", "开发工程师",
            "project", "AI平台",
            "tech_stack", List.of("Java", "Python", "Docker")
        ));

        longTermMemory.save("project_context", Map.of(
            "deadline", "2024-06-30",
            "priority", "high",
            "team_size", 5
        ));

        return Agent.builder()
            .name("memory-enhanced-agent")
            .llmProvider(llmProvider)
            .memory(longTermMemory)
            .systemPrompt("""
                你是一个项目助手。使用你的记忆来提供个性化帮助。

                在回答时，请参考：
                - 用户的个人信息和偏好
                - 项目上下文和约束
                - 之前的对话历史
                """)
            .build();
    }

    public void useMemoryDuringConversation() {
        Agent agent = createAgentWithLongTermMemory();

        // 代理可以访问长期记忆
        AgentOutput output = agent.execute(
            "基于我的技术栈，推荐一个适合的架构方案"
        );

        // 代理会参考记忆中的 tech_stack 信息
        System.out.println(output.getOutput());

        // 更新记忆
        Memory memory = agent.getMemory();
        memory.save("architecture_decision", Map.of(
            "pattern", "microservices",
            "chosen_date", LocalDate.now().toString()
        ));
    }
}
```

### 3. 向量记忆（语义检索）

```java
import ai.core.vectorstore.VectorStore;
import ai.core.vectorstore.MilvusVectorStore;

public class VectorMemoryExample {

    public Agent createAgentWithVectorMemory() {
        // 初始化向量存储
        VectorStore vectorStore = new MilvusVectorStore(
            "localhost", 19530, "agent_memory"
        );

        // 配置 RAG
        RAGConfig ragConfig = RAGConfig.builder()
            .vectorStore(vectorStore)
            .embeddingModel("text-embedding-ada-002")
            .topK(5)
            .similarityThreshold(0.75)
            .build();

        return Agent.builder()
            .name("vector-memory-agent")
            .llmProvider(llmProvider)
            .enableRAG(true)
            .ragConfig(ragConfig)
            .systemPrompt("""
                你是一个知识助手。
                使用向量记忆检索相关信息来回答问题。
                如果检索到相关信息，请基于这些信息回答。
                如果没有相关信息，请诚实地说明。
                """)
            .build();
    }
}
```

## 反思机制

### 1. 基本反思

```java
public class ReflectionExample {

    public Agent createReflectiveAgent() {
        return Agent.builder()
            .name("reflective-agent")
            .llmProvider(llmProvider)
            .enableReflection(true)
            .reflectionPrompt("""
                请反思你的回答：
                1. 回答是否准确和完整？
                2. 是否有遗漏的重要信息？
                3. 表达是否清晰易懂？
                4. 是否需要改进？

                如果需要改进，请提供更好的回答。
                """)
            .maxReflectionDepth(2)  // 最多反思2次
            .build();
    }

    public void demonstrateReflection() {
        Agent agent = createReflectiveAgent();

        // 执行带反思的查询
        AgentOutput output = agent.execute(
            "解释什么是依赖注入，并给出例子"
        );

        // 查看反思过程
        if (output.getReflections() != null) {
            for (Reflection reflection : output.getReflections()) {
                System.out.println("反思 " + reflection.getDepth() + ":");
                System.out.println("原始输出: " + reflection.getOriginalOutput());
                System.out.println("反思内容: " + reflection.getReflectionContent());
                System.out.println("改进输出: " + reflection.getImprovedOutput());
                System.out.println("---");
            }
        }

        // 最终输出（经过反思改进）
        System.out.println("最终回答: " + output.getOutput());
    }
}
```

### 2. 条件反思

```java
public class ConditionalReflectionExample {

    public Agent createSmartReflectiveAgent() {
        return Agent.builder()
            .name("smart-reflective-agent")
            .llmProvider(llmProvider)
            .enableReflection(true)

            // 自定义反思条件
            .reflectionCondition(output -> {
                // 只有当输出包含代码或技术内容时才反思
                return output.contains("```") ||
                       output.contains("代码") ||
                       output.contains("实现") ||
                       output.length() < 100;  // 或输出太短
            })

            .reflectionPrompt("""
                评估你的技术回答：

                技术准确性：
                - 代码是否正确？
                - 概念解释是否准确？

                完整性：
                - 是否包含必要的示例？
                - 是否解释了关键概念？

                可读性：
                - 代码是否有注释？
                - 解释是否循序渐进？

                如果有不足，请改进你的回答。
                """)

            .maxReflectionDepth(3)
            .build();
    }
}
```

## 状态管理

### 1. 代理状态

```java
import ai.core.agent.NodeStatus;

public class StatusManagementExample {

    public void handleAgentStatus() {
        Agent agent = createAgent();

        // 执行并检查状态
        AgentOutput output = agent.execute("执行一个任务");

        switch (output.getStatus()) {
            case PENDING:
                System.out.println("任务待处理");
                break;

            case RUNNING:
                System.out.println("任务执行中");
                break;

            case COMPLETED:
                System.out.println("任务完成");
                System.out.println("结果: " + output.getOutput());
                break;

            case WAITING_FOR_USER_INPUT:
                System.out.println("需要用户输入");
                System.out.println("提示: " + output.getWaitingMessage());
                // 收集用户输入
                String userInput = getUserInput();
                // 继续执行
                output = agent.continueWithInput(userInput);
                break;

            case FAILED:
                System.out.println("任务失败");
                System.out.println("错误: " + output.getError());
                // 可选：重试
                if (shouldRetry()) {
                    output = agent.retry();
                }
                break;
        }
    }
}
```

### 2. 持久化和恢复

```java
import ai.core.persistence.AgentPersistence;

public class PersistenceExample {

    private final AgentPersistence persistence = new AgentPersistence();

    public void saveAgentState() {
        Agent agent = createConfiguredAgent();

        // 执行一些任务
        agent.execute("任务1");
        agent.execute("任务2");

        // 保存状态
        String agentId = agent.getId();
        persistence.saveAgent(agentId, agent);

        System.out.println("代理状态已保存: " + agentId);
    }

    public void restoreAgentState(String agentId) {
        // 恢复代理状态
        Agent restoredAgent = persistence.loadAgent(agentId);

        if (restoredAgent != null) {
            // 继续之前的对话
            AgentOutput output = restoredAgent.execute("继续我们之前的讨论");
            System.out.println(output.getOutput());

            // 查看恢复的历史
            List<Message> history = restoredAgent.getMessages();
            System.out.println("恢复了 " + history.size() + " 条消息");
        }
    }
}
```

### 3. 并发和线程安全

```java
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConcurrentAgentExample {

    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    public void handleConcurrentRequests() {
        // 每个请求创建独立的代理实例（推荐）
        List<CompletableFuture<AgentOutput>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            final int requestId = i;
            CompletableFuture<AgentOutput> future = CompletableFuture
                .supplyAsync(() -> {
                    // 为每个请求创建新代理
                    Agent agent = createAgent();
                    return agent.execute("请求 " + requestId);
                }, executor);

            futures.add(future);
        }

        // 等待所有请求完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenRun(() -> {
                futures.forEach(future -> {
                    try {
                        AgentOutput output = future.get();
                        System.out.println("输出: " + output.getOutput());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
            });
    }

    // 如果需要共享代理（注意线程安全）
    public void handleWithSharedAgent() {
        // 使用线程安全的代理包装器
        Agent sharedAgent = createThreadSafeAgent();

        List<CompletableFuture<AgentOutput>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            final String query = "查询 " + i;
            CompletableFuture<AgentOutput> future = CompletableFuture
                .supplyAsync(() -> {
                    // 同步访问共享代理
                    synchronized (sharedAgent) {
                        return sharedAgent.execute(query);
                    }
                }, executor);

            futures.add(future);
        }
    }
}
```

## 最佳实践

### 1. 代理设计原则

```java
public class BestPracticesExample {

    // ✅ 好的做法：单一职责
    public Agent createSpecializedAgent() {
        return Agent.builder()
            .name("code-reviewer")
            .description("专门进行代码审查的代理")
            .systemPrompt("""
                你是一个代码审查专家。
                只负责：
                1. 检查代码质量
                2. 发现潜在问题
                3. 提供改进建议
                """)
            .build();
    }

    // ❌ 不好的做法：职责过多
    public Agent createOverloadedAgent() {
        return Agent.builder()
            .name("do-everything")
            .description("什么都做的代理")
            .systemPrompt("""
                你要做代码审查、写文档、
                管理项目、回答问题、部署应用...
                """)
            .build();
    }
}
```

### 2. 错误处理

```java
public class ErrorHandlingExample {

    public AgentOutput executeWithErrorHandling(Agent agent, String query) {
        try {
            // 设置超时
            AgentOutput output = agent.execute(query, 30000); // 30秒超时

            if (output.getStatus() == NodeStatus.FAILED) {
                // 记录错误
                logger.error("Agent execution failed: {}", output.getError());

                // 尝试降级策略
                return fallbackStrategy(query);
            }

            return output;

        } catch (TimeoutException e) {
            logger.error("Agent execution timeout", e);
            return AgentOutput.failed("执行超时，请重试");

        } catch (Exception e) {
            logger.error("Unexpected error", e);
            // 优雅降级
            return AgentOutput.failed("系统繁忙，请稍后重试");
        }
    }

    private AgentOutput fallbackStrategy(String query) {
        // 使用更简单的模型或预定义响应
        return AgentOutput.success("抱歉，我暂时无法处理这个请求。");
    }
}
```

### 3. 性能优化

```java
public class PerformanceOptimizationExample {

    // 使用对象池管理代理
    private final ObjectPool<Agent> agentPool;

    public PerformanceOptimizationExample() {
        // 创建代理池
        this.agentPool = new GenericObjectPool<>(new AgentFactory());
        agentPool.setMaxTotal(10);
        agentPool.setMaxIdle(5);
    }

    public AgentOutput processRequest(String query) throws Exception {
        Agent agent = null;
        try {
            // 从池中借用代理
            agent = agentPool.borrowObject();
            return agent.execute(query);
        } finally {
            if (agent != null) {
                // 归还代理到池中
                agentPool.returnObject(agent);
            }
        }
    }

    // 缓存常用响应
    private final Cache<String, AgentOutput> responseCache =
        CacheBuilder.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    public AgentOutput cachedExecute(Agent agent, String query) {
        try {
            return responseCache.get(query, () -> agent.execute(query));
        } catch (ExecutionException e) {
            return AgentOutput.failed("执行失败");
        }
    }
}
```

### 4. 监控和可观测性

```java
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.TelemetryConfig;

public class ObservabilityExample {

    @Inject
    private AgentTracer tracer;

    public Agent createObservableAgent() {
        return Agent.builder()
            .name("observable-agent")
            .llmProvider(llmProvider)
            .tracer(tracer)  // 添加追踪

            // 添加监听器
            .addExecutionListener(new AgentExecutionListener() {
                @Override
                public void beforeExecute(String query) {
                    metrics.counter("agent.executions").increment();
                    logger.info("Starting execution: {}", query);
                }

                @Override
                public void afterExecute(AgentOutput output) {
                    metrics.timer("agent.execution.time")
                        .record(output.getExecutionTime());

                    if (output.getStatus() == NodeStatus.FAILED) {
                        metrics.counter("agent.failures").increment();
                    }
                }
            })
            .build();
    }
}
```

## 总结

通过本教程，您学习了：

1. ✅ 如何创建和配置智能代理
2. ✅ 如何使用系统提示和模板
3. ✅ 如何实现记忆系统
4. ✅ 如何启用反思机制
5. ✅ 如何管理代理状态
6. ✅ 最佳实践和性能优化

下一步，您可以：
- 学习[工具调用](tutorial-tool-calling.md)增强代理能力
- 探索[多代理系统](tutorial-multi-agent.md)构建复杂应用
- 了解[RAG 集成](tutorial-rag.md)实现知识增强