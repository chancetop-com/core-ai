# Core-AI 快速开始

本指南将帮助您在 10 分钟内创建您的第一个 AI 代理。

## 前置要求

- Java 21 或更高版本
- Gradle 8.0+
- 一个 LLM API 密钥（Azure OpenAI 或其他支持的提供商）

## 第 1 步：添加依赖

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven { url = uri("https://neowu.github.io/maven-repo/") }
    maven { url = uri("https://chancetop-com.github.io/maven-repo/") }
}

dependencies {
    implementation("com.chancetop:core-ai:1.1.84")
    implementation("com.chancetop:core-ai-api:1.1.14")
}
```

### Gradle (Groovy)

```groovy
repositories {
    mavenCentral()
    maven { url = "https://neowu.github.io/maven-repo/" }
    maven { url = "https://chancetop-com.github.io/maven-repo/" }
}

dependencies {
    implementation 'com.chancetop:core-ai:1.1.84'
    implementation 'com.chancetop:core-ai-api:1.1.14'
}
```

## 第 2 步：配置 LLM 提供商

创建配置文件 `conf/sys.properties`：

```properties
# Azure OpenAI 配置
azure.openai.endpoint=https://your-resource.openai.azure.com
azure.openai.api.key=your-api-key
azure.openai.deployment.name=gpt-4

# 或者使用 Azure AI Inference
azure.ai.inference.endpoint=https://your-model.inference.ai.azure.com
azure.ai.inference.api.key=your-api-key

# 可选：启用追踪
trace.otlp.endpoint=https://cloud.langfuse.com
trace.service.name=my-ai-app
```

## 第 3 步：创建您的第一个代理

```java
package com.example;

import ai.core.agent.Agent;
import ai.core.agent.AgentOutput;
import ai.core.llm.LLMProvider;
import ai.core.llm.providers.AzureOpenAILLMProvider;
import ai.core.llm.AzureOpenAIConfig;

public class QuickstartExample {
    public static void main(String[] args) {
        // 1. 初始化 LLM 提供商
        AzureOpenAIConfig config = AzureOpenAIConfig.builder()
            .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))
            .apiKey(System.getenv("AZURE_OPENAI_API_KEY"))
            .deploymentName("gpt-4")
            .build();

        LLMProvider llmProvider = new AzureOpenAILLMProvider(config);

        // 2. 创建一个简单的代理
        Agent agent = Agent.builder()
            .name("assistant")
            .description("一个友好的 AI 助手")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个友好、专业的 AI 助手。请用中文回答用户的问题。")
            .build();

        // 3. 执行查询
        String userQuery = "请介绍一下什么是人工智能？";
        AgentOutput output = agent.execute(userQuery);

        // 4. 获取响应
        System.out.println("助手回复：");
        System.out.println(output.getOutput());

        // 可选：查看使用的 token 数量
        System.out.println("使用的 tokens: " + output.getTotalTokens());
    }
}
```

## 第 4 步：添加工具调用能力

```java
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AgentWithTools {

    // 定义一个获取当前时间的工具
    public static class GetCurrentTimeTool extends ToolCall {
        @Override
        public String getName() {
            return "get_current_time";
        }

        @Override
        public String getDescription() {
            return "获取当前的日期和时间";
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ToolCallResult.success("当前时间是：" + currentTime);
        }
    }

    public static void main(String[] args) {
        // 配置 LLM（同上）
        LLMProvider llmProvider = createLLMProvider();

        // 创建带工具的代理
        Agent agent = Agent.builder()
            .name("assistant-with-tools")
            .description("能够查询时间的助手")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个助手。当用户询问时间时，使用工具获取准确的时间。")
            .tools(List.of(new GetCurrentTimeTool()))
            .build();

        // 执行需要工具的查询
        AgentOutput output = agent.execute("现在几点了？");
        System.out.println(output.getOutput());
    }
}
```

## 第 5 步：使用流式响应

```java
import ai.core.sse.ServerSentEventHandler;

public class StreamingExample {
    public static void main(String[] args) {
        LLMProvider llmProvider = createLLMProvider();

        Agent agent = Agent.builder()
            .name("streaming-assistant")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个友好的助手")
            .streaming(true)  // 启用流式响应
            .build();

        // 设置事件处理器
        ServerSentEventHandler handler = new ServerSentEventHandler() {
            @Override
            public void onMessage(String data) {
                System.out.print(data);  // 实时打印响应
            }

            @Override
            public void onComplete() {
                System.out.println("\n[完成]");
            }
        };

        // 执行流式查询
        agent.execute("写一首关于春天的诗", handler);
    }
}
```

## 第 6 步：使用依赖注入（推荐）

如果您使用 Spring Boot 或 core-ng 框架：

```java
import core.framework.inject.Inject;
import core.framework.module.Module;

public class AIModule extends Module {
    @Override
    protected void initialize() {
        // 从配置文件读取
        String endpoint = property("azure.openai.endpoint").orElse(null);
        String apiKey = property("azure.openai.api.key").orElse(null);
        String deployment = property("azure.openai.deployment.name").orElse("gpt-4");

        // 注册 LLM 提供商
        bind(LLMProvider.class).toInstance(
            new AzureOpenAILLMProvider(
                AzureOpenAIConfig.builder()
                    .endpoint(endpoint)
                    .apiKey(apiKey)
                    .deploymentName(deployment)
                    .build()
            )
        );

        // 可选：启用追踪
        if (property("trace.otlp.endpoint").isPresent()) {
            load(new TelemetryModule());
        }
    }
}

// 在服务中使用
public class ChatService {
    @Inject
    LLMProvider llmProvider;

    @Inject(optional = true)
    AgentTracer tracer;

    public String chat(String message) {
        Agent.Builder builder = Agent.builder()
            .name("chat-agent")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个友好的聊天助手");

        if (tracer != null) {
            builder.tracer(tracer);
        }

        Agent agent = builder.build();
        return agent.execute(message).getOutput();
    }
}
```

## 运行应用

### 使用 Gradle

```bash
# 构建项目
./gradlew build

# 运行应用
./gradlew run

# 带环境变量运行
AZURE_OPENAI_ENDPOINT=https://xxx.openai.azure.com \
AZURE_OPENAI_API_KEY=your-key \
./gradlew run
```

### 使用 Java 直接运行

```bash
# 编译
javac -cp "libs/*" src/main/java/com/example/*.java

# 运行
java -cp "libs/*:src/main/java" com.example.QuickstartExample
```

## 常见问题

### 1. 如何切换不同的 LLM 提供商？

```java
// Azure OpenAI
LLMProvider azureProvider = new AzureOpenAILLMProvider(azureConfig);

// Azure AI Inference
LLMProvider inferenceProvider = new AzureAIInferenceLLMProvider(inferenceConfig);

// 其他提供商...
```

### 2. 如何处理错误？

```java
try {
    AgentOutput output = agent.execute(query);
    if (output.getStatus() == NodeStatus.FAILED) {
        System.err.println("执行失败：" + output.getError());
    }
} catch (Exception e) {
    System.err.println("发生错误：" + e.getMessage());
}
```

### 3. 如何启用调试日志？

```properties
# 在 sys.properties 中
log.level=DEBUG
log.console=true
```

### 4. 如何限制 token 使用？

```java
Agent agent = Agent.builder()
    .name("limited-agent")
    .llmProvider(llmProvider)
    .maxTokens(1000)  // 限制最大 token
    .temperature(0.7)  // 调整创造性
    .build();
```

## 下一步

恭喜！您已经成功创建了第一个 AI 代理。接下来您可以：

1. 阅读[详细教程](tutorial.md)学习高级功能
2. 探索[多代理系统](tutorial-multi-agent.md)构建复杂应用
3. 了解[RAG 集成](tutorial-rag.md)增强知识检索
4. 学习[流程编排](tutorial-flow.md)构建工作流

## 获取帮助

- 查看[完整文档](https://github.com/chancetop-com/core-ai/docs)
- 提交[问题和反馈](https://github.com/chancetop-com/core-ai/issues)
- 参考[示例项目](https://github.com/chancetop-com/core-ai/tree/master/example-service)