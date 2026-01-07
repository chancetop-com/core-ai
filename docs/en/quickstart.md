# Core-AI Quick Start

This guide will help you create your first AI agent in 10 minutes.

## Prerequisites

- Java 21 or higher
- Gradle 8.0+
- An LLM API key (Azure OpenAI or other supported provider)

## Step 1: Add Dependencies

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

## Step 2: Configure LLM Provider

Create a configuration file `conf/sys.properties`:

```properties
# Azure OpenAI Configuration
azure.openai.endpoint=https://your-resource.openai.azure.com
azure.openai.api.key=your-api-key
azure.openai.deployment.name=gpt-4

# Or use Azure AI Inference
azure.ai.inference.endpoint=https://your-model.inference.ai.azure.com
azure.ai.inference.api.key=your-api-key

# Optional: Enable tracing
trace.otlp.endpoint=https://cloud.langfuse.com
trace.service.name=my-ai-app
```

## Step 3: Create Your First Agent

```java
package com.example;

import ai.core.agent.Agent;
import ai.core.agent.AgentOutput;
import ai.core.llm.LLMProvider;
import ai.core.llm.providers.AzureOpenAILLMProvider;
import ai.core.llm.AzureOpenAIConfig;

public class QuickstartExample {
    public static void main(String[] args) {
        // 1. Initialize LLM Provider
        AzureOpenAIConfig config = AzureOpenAIConfig.builder()
            .endpoint(System.getenv("AZURE_OPENAI_ENDPOINT"))
            .apiKey(System.getenv("AZURE_OPENAI_API_KEY"))
            .deploymentName("gpt-4")
            .build();

        LLMProvider llmProvider = new AzureOpenAILLMProvider(config);

        // 2. Create a simple agent
        Agent agent = Agent.builder()
            .name("assistant")
            .description("A friendly AI assistant")
            .llmProvider(llmProvider)
            .systemPrompt("You are a friendly and professional AI assistant.")
            .build();

        // 3. Execute query
        String userQuery = "What is artificial intelligence?";
        AgentOutput output = agent.execute(userQuery);

        // 4. Get response
        System.out.println("Assistant Response:");
        System.out.println(output.getOutput());

        // Optional: View token usage
        System.out.println("Tokens used: " + output.getTotalTokens());
    }
}
```

## Step 4: Add Tool Calling

```java
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class AgentWithTools {

    // Define a tool to get current time
    public static class GetCurrentTimeTool extends ToolCall {
        @Override
        public String getName() {
            return "get_current_time";
        }

        @Override
        public String getDescription() {
            return "Get the current date and time";
        }

        @Override
        public ToolCallResult call(Map<String, Object> arguments) {
            String currentTime = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return ToolCallResult.success("Current time is: " + currentTime);
        }
    }

    public static void main(String[] args) {
        // Configure LLM (same as above)
        LLMProvider llmProvider = createLLMProvider();

        // Create agent with tools
        Agent agent = Agent.builder()
            .name("assistant-with-tools")
            .description("An assistant that can check the time")
            .llmProvider(llmProvider)
            .systemPrompt("You are an assistant. When asked about time, use the tool to get accurate time.")
            .tools(List.of(new GetCurrentTimeTool()))
            .build();

        // Execute query that needs tools
        AgentOutput output = agent.execute("What time is it?");
        System.out.println(output.getOutput());
    }
}
```

## Step 5: Use Streaming Response

```java
import ai.core.sse.ServerSentEventHandler;

public class StreamingExample {
    public static void main(String[] args) {
        LLMProvider llmProvider = createLLMProvider();

        Agent agent = Agent.builder()
            .name("streaming-assistant")
            .llmProvider(llmProvider)
            .systemPrompt("You are a friendly assistant")
            .streaming(true)  // Enable streaming response
            .build();

        // Set event handler
        ServerSentEventHandler handler = new ServerSentEventHandler() {
            @Override
            public void onMessage(String data) {
                System.out.print(data);  // Print response in real-time
            }

            @Override
            public void onComplete() {
                System.out.println("\n[Complete]");
            }
        };

        // Execute streaming query
        agent.execute("Write a poem about spring", handler);
    }
}
```

## Step 6: Use Dependency Injection (Recommended)

If you're using Spring Boot or the core-ng framework:

```java
import core.framework.inject.Inject;
import core.framework.module.Module;

public class AIModule extends Module {
    @Override
    protected void initialize() {
        // Read from configuration
        String endpoint = property("azure.openai.endpoint").orElse(null);
        String apiKey = property("azure.openai.api.key").orElse(null);
        String deployment = property("azure.openai.deployment.name").orElse("gpt-4");

        // Register LLM provider
        bind(LLMProvider.class).toInstance(
            new AzureOpenAILLMProvider(
                AzureOpenAIConfig.builder()
                    .endpoint(endpoint)
                    .apiKey(apiKey)
                    .deploymentName(deployment)
                    .build()
            )
        );

        // Optional: Enable tracing
        if (property("trace.otlp.endpoint").isPresent()) {
            load(new TelemetryModule());
        }
    }
}

// Use in service
public class ChatService {
    @Inject
    LLMProvider llmProvider;

    @Inject(optional = true)
    AgentTracer tracer;

    public String chat(String message) {
        Agent.Builder builder = Agent.builder()
            .name("chat-agent")
            .llmProvider(llmProvider)
            .systemPrompt("You are a friendly chat assistant");

        if (tracer != null) {
            builder.tracer(tracer);
        }

        Agent agent = builder.build();
        return agent.execute(message).getOutput();
    }
}
```

## Running the Application

### Using Gradle

```bash
# Build the project
./gradlew build

# Run the application
./gradlew run

# Run with environment variables
AZURE_OPENAI_ENDPOINT=https://xxx.openai.azure.com \
AZURE_OPENAI_API_KEY=your-key \
./gradlew run
```

### Using Java Directly

```bash
# Compile
javac -cp "libs/*" src/main/java/com/example/*.java

# Run
java -cp "libs/*:src/main/java" com.example.QuickstartExample
```

## Common Issues

### 1. How to switch between different LLM providers?

```java
// Azure OpenAI
LLMProvider azureProvider = new AzureOpenAILLMProvider(azureConfig);

// Azure AI Inference
LLMProvider inferenceProvider = new AzureAIInferenceLLMProvider(inferenceConfig);

// Other providers...
```

### 2. How to handle errors?

```java
try {
    AgentOutput output = agent.execute(query);
    if (output.getStatus() == NodeStatus.FAILED) {
        System.err.println("Execution failed: " + output.getError());
    }
} catch (Exception e) {
    System.err.println("Error occurred: " + e.getMessage());
}
```

### 3. How to enable debug logging?

```properties
# In sys.properties
log.level=DEBUG
log.console=true
```

### 4. How to limit token usage?

```java
Agent agent = Agent.builder()
    .name("limited-agent")
    .llmProvider(llmProvider)
    .maxTokens(1000)  // Limit max tokens
    .temperature(0.7)  // Adjust creativity
    .build();
```

## Next Steps

Congratulations! You've successfully created your first AI agent. Next, you can:

1. Read the [Detailed Tutorials](tutorial-basic-agent.md) to learn advanced features
2. Explore [Flow Orchestration](tutorial-flow.md) to build complex applications
3. Learn about [RAG Integration](tutorial-rag.md) to enhance knowledge retrieval
4. Study [Flow Orchestration](tutorial-flow.md) to build workflows

## Getting Help

- Check the [Full Documentation](https://github.com/chancetop-com/core-ai/docs)
- Submit [Issues and Feedback](https://github.com/chancetop-com/core-ai/issues)
- Reference [Example Projects](https://github.com/chancetop-com/core-ai/tree/master/example-service)