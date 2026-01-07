# 教程：构建智能代理（Agent）

本教程将深入介绍如何使用 Core-AI 构建功能强大的智能代理。

## 目录

1. [代理基础概念](#代理基础概念)
2. [创建基本代理](#创建基本代理)
3. [系统提示和模板](#系统提示和模板)
4. [反思机制](#反思机制)
5. [状态管理](#状态管理)
6. [最佳实践](#最佳实践)

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
    .name("agent-name")           // 代理名称（可选，默认："assistant"）
    .description("agent purpose") // 代理描述（可选，默认："assistant agent that help with user"）
    .llmProvider(provider)        // LLM 提供商（必需）
    .systemPrompt(prompt)         // 系统提示
    .toolCalls(toolList)          // 可用工具
    .build();
```

### 核心架构设计

Agent 的执行分为**外层包装**和**核心执行**两个层次：

```
┌─────────────────────────────────────────────────────────────────┐
│                         Node 层                                  │
│   aroundExecute() - 生命周期钩子包装                             │
│   ├─ beforeAgentRun() - 可修改输入查询                          │
│   ├─ execute()        - 核心执行                                │
│   └─ afterAgentRun()  - 可修改输出结果                          │
├─────────────────────────────────────────────────────────────────┤
│                        Agent 层                                  │
│   execute() → doExecute() → commandOrLoops()                    │
│   ├─ SlashCommand → chatCommand() - 斜杠命令快捷处理            │
│   └─ 普通查询 → chatLoops() → chatTurns() - 多轮对话循环        │
├─────────────────────────────────────────────────────────────────┤
│                        对话轮次层                                │
│   chatTurns() - 核心对话循环                                    │
│   while (lastIsToolMsg && turn < maxTurn):                      │
│       ├─ beforeModel()   - LLM 调用前钩子                       │
│       ├─ LLM 推理        - 调用大模型                           │
│       ├─ afterModel()    - LLM 调用后钩子                       │
│       ├─ 工具执行        - 并行执行工具调用                      │
│       └─ 累积输出        - 收集 ASSISTANT 消息                  │
└─────────────────────────────────────────────────────────────────┘
```

### 代理执行流程

```
+------------------------------------------------------------------+
|                        代理执行流程                                |
+------------------------------------------------------------------+
|                                                                  |
|  1. agent.run(query, context)                                    |
|          |                                                       |
|          v                                                       |
|  2. 生命周期: beforeAgentRun                                      |
|          |                                                       |
|          v                                                       |
|  3. 构建消息 (系统提示 + 历史 + 用户查询)                          |
|          |                                                       |
|          v                                                       |
|  4. RAG 检索 (如果启用)                                           |
|          |                                                       |
|          v                                                       |
|  5. LLM 推理 (带工具定义)                                         |
|     |-- 生命周期: beforeModel / afterModel                       |
|          |                                                       |
|          v                                                       |
|  6. 如果有工具调用:                                               |
|     |-- 执行工具                                                  |
|     |-- 添加结果到消息                                            |
|     |-- 循环回到步骤 5 (受最大轮次限制)                           |
|          |                                                       |
|          v                                                       |
|  7. 反思 (如果启用)                                               |
|          |                                                       |
|          v                                                       |
|  8. 生命周期: afterAgentRun                                       |
|          |                                                       |
|          v                                                       |
|  9. 返回输出                                                      |
|                                                                  |
+------------------------------------------------------------------+
```

## 创建基本代理

### 1. 最简单的代理

```java
import ai.core.agent.Agent;
import ai.core.agent.NodeStatus;
import ai.core.llm.LLMProvider;

public class BasicAgentExample {

    public Agent createSimpleAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("simple-assistant")
            .description("一个简单的助手")
            .llmProvider(llmProvider)
            .build();
    }

    public void useAgent(LLMProvider llmProvider) {
        Agent agent = createSimpleAgent(llmProvider);

        // 执行查询
        String output = agent.run("你好，请介绍一下自己");
        System.out.println(output);

        // 检查状态
        if (agent.getNodeStatus() == NodeStatus.COMPLETED) {
            System.out.println("执行成功");
        }
    }
}
```

### 2. 配置丰富的代理

```java
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;

public class ConfiguredAgentExample {

    public Agent createConfiguredAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("customer-service")
            .description("客户服务代理")
            .llmProvider(llmProvider)

            // 基本配置
            .systemPrompt("你是一个专业的客户服务代表...")
            .temperature(0.7)
            .model("gpt-4")

            // 工具配置
            .toolCalls(List.of(searchTool, orderTool))

            // 轮次限制
            .maxTurn(20)  // 最大对话轮次（默认：20）

            // 反思
            .enableReflection(true)

            .build();
    }

    public void runWithContext(Agent agent) {
        // 创建执行上下文
        ExecutionContext context = ExecutionContext.builder()
            .userId("user-123")
            .sessionId("session-456")
            .build();

        // 带上下文执行
        String response = agent.run("我有个订单问题", context);
        System.out.println(response);
    }
}
```

### 3. 流式输出代理

```java
import ai.core.agent.streaming.StreamingCallback;

public class StreamingAgentExample {

    public Agent createStreamingAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("streaming-agent")
            .description("支持流式输出的代理")
            .llmProvider(llmProvider)
            .streaming(true)
            .streamingCallback(new StreamingCallback() {
                @Override
                public void onToken(String token) {
                    System.out.print(token);  // 逐字打印
                }

                @Override
                public void onComplete(String fullResponse) {
                    System.out.println("\n--- 完成 ---");
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("错误: " + error.getMessage());
                }
            })
            .build();
    }
}
```

## 系统提示和模板

### 1. 静态系统提示

```java
Agent agent = Agent.builder()
    .name("technical-writer")
    .description("技术文档专家")
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

        请始终用用户使用的语言回答。
        """)
    .build();
```

### 2. 动态模板（Mustache）

Core-AI 使用 Mustache 模板引擎实现动态提示：

```java
public class TemplatedAgentExample {

    public Agent createTemplatedAgent(LLMProvider llmProvider) {
        // 使用 Mustache 语法的模板
        String systemPromptTemplate = """
            你是 {{company}} 的 AI 助手。

            公司信息：
            - 行业：{{industry}}
            - 主要产品：{{products}}
            - 服务时间：{{serviceHours}}

            当前时间：{{currentTime}}
            用户位置：{{userLocation}}

            请根据以上信息为用户提供个性化服务。
            """;

        return Agent.builder()
            .name("templated-agent")
            .description("支持动态提示的代理")
            .llmProvider(llmProvider)
            .systemPrompt(systemPromptTemplate)
            .build();
    }

    public void runWithVariables(Agent agent) {
        // 通过 ExecutionContext 传递变量
        Map<String, Object> variables = Map.of(
            "company", "科技创新公司",
            "industry", "人工智能",
            "products", "AI 解决方案",
            "serviceHours", "9:00-18:00",
            "currentTime", LocalDateTime.now().toString(),
            "userLocation", "北京"
        );

        ExecutionContext context = ExecutionContext.builder()
            .customVariables(variables)
            .build();

        agent.run("你好，你们提供什么服务？", context);
    }
}
```

### 3. 用户查询的提示模板

```java
public class PromptTemplateExample {

    public Agent createAgentWithPromptTemplate(LLMProvider llmProvider) {
        return Agent.builder()
            .name("qa-agent")
            .description("带模板的问答代理")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个有帮助的问答助手。")
            // promptTemplate 会添加到用户查询前面
            .promptTemplate("""
                上下文: {{context}}

                请回答以下问题:
                """)
            .build();
    }

    public void runWithTemplate(Agent agent) {
        ExecutionContext context = ExecutionContext.builder()
            .customVariable("context", "这是关于 Java 编程的问题")
            .build();

        // 最终提示 = promptTemplate + 用户查询
        agent.run("如何创建线程池？", context);
    }
}
```

### 4. Langfuse 提示集成

```java
// 从 Langfuse 提示管理获取提示
Agent agent = Agent.builder()
    .name("langfuse-agent")
    .description("使用 Langfuse 提示的代理")
    .llmProvider(llmProvider)
    .langfuseSystemPrompt("customer-service-prompt")  // Langfuse 中的提示名称
    .langfusePromptVersion(2)  // 可选：指定版本
    // 或使用标签
    // .langfusePromptLabel("production")
    .build();
```

## 反思机制

反思允许代理评估和改进其响应。

### 1. 基本反思

```java
import ai.core.reflection.ReflectionConfig;

public class ReflectionExample {

    public Agent createReflectiveAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("reflective-agent")
            .description("具有反思能力的代理")
            .llmProvider(llmProvider)
            .enableReflection(true)  // 使用默认反思配置
            .build();
    }
}
```

### 2. 自定义反思配置

```java
public class CustomReflectionExample {

    public Agent createCustomReflectiveAgent(LLMProvider llmProvider) {
        // 创建自定义反思配置
        ReflectionConfig reflectionConfig = ReflectionConfig.builder()
            .enabled(true)
            .minRound(1)           // 最小反思轮数
            .maxRound(3)           // 最大反思轮数
            .evaluationCriteria("""
                根据以下标准评估回答：
                1. 准确性 - 信息是否正确？
                2. 完整性 - 是否涵盖所有方面？
                3. 清晰度 - 解释是否清楚？
                4. 实用性 - 是否对用户有帮助？

                评分 1-10，8 分以上为通过。
                """)
            .build();

        return Agent.builder()
            .name("custom-reflective-agent")
            .description("自定义反思的代理")
            .llmProvider(llmProvider)
            .reflectionConfig(reflectionConfig)
            .build();
    }
}
```

### 3. 带监听器的反思

```java
import ai.core.reflection.ReflectionListener;
import ai.core.reflection.ReflectionHistory;
import ai.core.reflection.ReflectionEvaluation;

public class ReflectionListenerExample {

    public Agent createAgentWithReflectionListener(LLMProvider llmProvider) {
        ReflectionListener listener = new ReflectionListener() {
            @Override
            public void onReflectionStart(Agent agent, String input, String criteria) {
                System.out.println("开始反思: " + input);
            }

            @Override
            public void onBeforeRound(Agent agent, int round, String solution) {
                System.out.println("第 " + round + " 轮 - 评估解决方案");
            }

            @Override
            public void onAfterRound(Agent agent, int round, String improved, ReflectionEvaluation eval) {
                System.out.println("第 " + round + " 轮 - 分数: " + eval.getScore());
            }

            @Override
            public void onScoreAchieved(Agent agent, int score, int round) {
                System.out.println("目标分数达成: " + score + " 在第 " + round + " 轮");
            }

            @Override
            public void onNoImprovement(Agent agent, int score, int round) {
                System.out.println("无法进一步改进，在第 " + round + " 轮");
            }

            @Override
            public void onMaxRoundsReached(Agent agent, int finalScore) {
                System.out.println("达到最大轮数。最终分数: " + finalScore);
            }

            @Override
            public void onError(Agent agent, int round, Exception e) {
                System.err.println("第 " + round + " 轮反思错误: " + e.getMessage());
            }

            @Override
            public void onReflectionComplete(Agent agent, ReflectionHistory history) {
                System.out.println("反思完成。总轮数: " + history.getRounds().size());
            }
        };

        return Agent.builder()
            .name("monitored-agent")
            .description("带反思监控的代理")
            .llmProvider(llmProvider)
            .enableReflection(true)
            .reflectionListener(listener)
            .build();
    }
}
```

### 4. 简化的反思配置

```java
// 简化的反思设置
Agent agent = Agent.builder()
    .name("simple-reflective")
    .description("简单反思代理")
    .llmProvider(llmProvider)
    .reflectionEvaluationCriteria("""
        检查代码是否正确且有良好的文档。
        代码可运行且有注释则评 8 分以上。
        """)
    .build();
```

## 状态管理

### 状态转换机制原理

Agent 使用有限状态机（FSM）管理执行状态。理解状态转换对于正确处理代理执行非常重要：

```
                    ┌─────────┐
                    │  INITED │ (初始状态 - Agent 创建后)
                    └────┬────┘
                         │ run() 被调用
                         ▼
                    ┌─────────┐
            ┌───────│ RUNNING │───────┐
            │       └────┬────┘       │
            │            │            │
     工具需认证      正常完成     发生异常
            │            │            │
            ▼            ▼            ▼
┌───────────────────┐ ┌─────────┐ ┌────────┐
│WAITING_FOR_USER   │ │COMPLETED│ │ FAILED │
│     _INPUT        │ └─────────┘ └────────┘
└─────────┬─────────┘
          │ 用户确认 "yes"
          │
          ▼
     ┌─────────┐
     │ RUNNING │ (继续执行)
     └─────────┘
```

**状态说明**：

| 状态 | 描述 | 触发条件 |
|------|------|---------|
| `INITED` | 初始状态 | Agent 创建后 |
| `RUNNING` | 执行中 | `run()` 被调用 |
| `COMPLETED` | 成功完成 | 正常执行结束 |
| `WAITING_FOR_USER_INPUT` | 等待用户输入 | 工具设置了 `needAuth=true` |
| `FAILED` | 执行失败 | 发生未处理异常 |

### 1. 代理状态

```java
import ai.core.agent.NodeStatus;

public class StatusManagementExample {

    public void handleAgentStatus(Agent agent) {
        String response = agent.run("执行一个任务");

        switch (agent.getNodeStatus()) {
            case INITED:
                System.out.println("代理已初始化");
                break;

            case RUNNING:
                System.out.println("代理运行中");
                break;

            case COMPLETED:
                System.out.println("任务完成");
                System.out.println("结果: " + agent.getOutput());
                break;

            case WAITING_FOR_USER_INPUT:
                System.out.println("等待用户输入");
                // 当工具需要认证时会进入此状态
                // 用户需要用 "yes" 确认
                agent.run("yes");  // 确认并继续
                break;

            case FAILED:
                System.out.println("任务失败");
                break;
        }
    }
}
```

### 2. 持久化和恢复

```java
import ai.core.persistence.PersistenceProvider;

public class PersistenceExample {

    public void setupPersistence(LLMProvider llmProvider, PersistenceProvider provider) {
        Agent agent = Agent.builder()
            .name("persistent-agent")
            .description("支持持久化的代理")
            .llmProvider(llmProvider)
            .persistenceProvider(provider)
            .build();

        // 执行一些任务
        agent.run("任务 1");
        agent.run("任务 2");

        // 保存状态
        String agentId = agent.save("my-agent-session");
        System.out.println("代理状态已保存: " + agentId);
    }

    public void restoreAgent(LLMProvider llmProvider, PersistenceProvider provider) {
        Agent agent = Agent.builder()
            .name("persistent-agent")
            .description("支持持久化的代理")
            .llmProvider(llmProvider)
            .persistenceProvider(provider)
            .build();

        // 加载保存的状态
        agent.load("my-agent-session");

        // 继续对话，带有恢复的历史
        String response = agent.run("继续我们之前的讨论");
        System.out.println(response);

        // 检查恢复的消息历史
        List<Message> history = agent.getMessages();
        System.out.println("恢复了 " + history.size() + " 条消息");
    }
}
```

### 3. 重置代理状态

```java
public class ResetExample {

    public void resetAgentState(Agent agent) {
        // 执行一些任务
        agent.run("问题 1");
        agent.run("问题 2");

        // 重置到初始状态
        agent.reset();

        // 代理现在没有历史了
        System.out.println("重置后的消息数: " + agent.getMessages().size());  // 0
        System.out.println("状态: " + agent.getNodeStatus());  // INITED
    }
}
```

### 4. Token 使用跟踪

```java
import ai.core.llm.domain.Usage;

public class TokenTrackingExample {

    public void trackTokenUsage(Agent agent) {
        agent.run("分析这个复杂问题...");

        Usage usage = agent.getCurrentTokenUsage();
        System.out.println("Prompt tokens: " + usage.getPromptTokens());
        System.out.println("Completion tokens: " + usage.getCompletionTokens());
        System.out.println("Total tokens: " + usage.getTotalTokens());
    }
}
```

## 最佳实践

### 1. 单一职责原则

```java
public class BestPracticesExample {

    // 好的做法：单一职责
    public Agent createCodeReviewer(LLMProvider llmProvider) {
        return Agent.builder()
            .name("code-reviewer")
            .description("代码审查专家")
            .llmProvider(llmProvider)
            .systemPrompt("""
                你是一个代码审查专家。
                你的职责仅包括：
                1. 检查代码质量
                2. 发现潜在问题
                3. 提供改进建议
                """)
            .build();
    }

    // 不好的做法：职责过多
    public Agent createOverloadedAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("do-everything")
            .description("什么都做的代理")  // 太宽泛
            .llmProvider(llmProvider)
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

    public String executeWithErrorHandling(Agent agent, String query) {
        try {
            String output = agent.run(query);

            if (agent.getNodeStatus() == NodeStatus.FAILED) {
                // 记录错误并使用备用方案
                logger.error("代理执行失败");
                return fallbackResponse(query);
            }

            return output;

        } catch (Exception e) {
            logger.error("意外错误", e);
            // 优雅降级
            return "系统繁忙，请稍后重试。";
        }
    }

    private String fallbackResponse(String query) {
        return "抱歉，我暂时无法处理这个请求。";
    }
}
```

### 3. 正确使用上下文

```java
public class ContextExample {

    public void properContextUsage(Agent agent) {
        // 创建包含所有必要信息的上下文
        ExecutionContext context = ExecutionContext.builder()
            .userId("user-123")          // 用于记忆隔离
            .sessionId("session-456")    // 用于会话跟踪
            .customVariable("locale", "zh-CN")
            .customVariable("timezone", "Asia/Shanghai")
            .build();

        // 始终传递上下文以确保一致行为
        agent.run("现在几点？", context);
    }
}
```

### 4. 可观测性与追踪

```java
import ai.core.telemetry.AgentTracer;

public class ObservabilityExample {

    public Agent createObservableAgent(LLMProvider llmProvider, AgentTracer tracer) {
        return Agent.builder()
            .name("observable-agent")
            .description("支持追踪的代理")
            .llmProvider(llmProvider)
            .tracer(tracer)  // 启用分布式追踪
            .build();
    }
}
```

### 5. 记忆集成

```java
import ai.core.memory.Memory;

public class MemoryIntegrationExample {

    public Agent createPersonalizedAgent(LLMProvider llmProvider, Memory memory) {
        return Agent.builder()
            .name("personalized-agent")
            .description("带长期记忆的代理")
            .llmProvider(llmProvider)
            .enableCompression(true)      // 会话内上下文管理
            .unifiedMemory(memory)        // 跨会话记忆
            .systemPrompt("""
                你是一个个性化助手。
                使用记忆召回工具来记住用户偏好。
                """)
            .build();
    }
}
```

## 总结

本教程涵盖了：

1. **代理基础**：理解代理结构和执行流程
2. **创建代理**：从简单到完整配置的代理
3. **系统提示**：静态提示、Mustache 模板、Langfuse 集成
4. **反思机制**：自我评估和改进机制
5. **状态管理**：状态处理、持久化、Token 跟踪
6. **最佳实践**：单一职责、错误处理、可观测性

下一步：
- 学习[工具调用](tutorial-tool-calling.md)扩展代理能力
- 探索[记忆系统](tutorial-memory.md)实现持久化记忆
- 学习[压缩机制](tutorial-compression.md)管理上下文
- 探索 [RAG 集成](tutorial-rag.md)实现知识增强
- 了解[流程编排](tutorial-flow.md)实现工作流
