# Tutorial: Building AI Agents

This tutorial provides an in-depth guide to building intelligent agents using Core-AI.

## Table of Contents

1. [Agent Basics](#agent-basics)
2. [Creating Agents](#creating-agents)
3. [System Prompts and Templates](#system-prompts-and-templates)
4. [Reflection Mechanism](#reflection-mechanism)
5. [State Management](#state-management)
6. [Best Practices](#best-practices)

## Agent Basics

### What is an Agent?

In Core-AI, an Agent is an autonomous AI entity that can:
- Understand and respond to natural language input
- Call tools to complete tasks
- Maintain conversation context and memory
- Reflect and improve its responses

### Agent Core Components

```java
Agent agent = Agent.builder()
    .name("agent-name")           // Agent name (required)
    .description("agent purpose") // Agent description (required)
    .llmProvider(provider)        // LLM provider (required)
    .systemPrompt(prompt)         // System prompt
    .toolCalls(toolList)          // Available tools
    .build();
```

### Agent Execution Flow

```
+------------------------------------------------------------------+
|                       Agent Execution Flow                        |
+------------------------------------------------------------------+
|                                                                  |
|  1. agent.run(query, context)                                    |
|          |                                                       |
|          v                                                       |
|  2. Lifecycle: beforeAgentRun                                    |
|          |                                                       |
|          v                                                       |
|  3. Build messages (system + history + user query)               |
|          |                                                       |
|          v                                                       |
|  4. RAG retrieval (if enabled)                                   |
|          |                                                       |
|          v                                                       |
|  5. LLM completion (with tool definitions)                       |
|     |-- Lifecycle: beforeModel / afterModel                      |
|          |                                                       |
|          v                                                       |
|  6. If tool calls returned:                                      |
|     |-- Execute tools                                            |
|     |-- Add results to messages                                  |
|     |-- Loop back to step 5 (max turns limit)                    |
|          |                                                       |
|          v                                                       |
|  7. Reflection (if enabled)                                      |
|          |                                                       |
|          v                                                       |
|  8. Lifecycle: afterAgentRun                                     |
|          |                                                       |
|          v                                                       |
|  9. Return output                                                |
|                                                                  |
+------------------------------------------------------------------+
```

## Creating Agents

### 1. Simple Agent

```java
import ai.core.agent.Agent;
import ai.core.agent.NodeStatus;
import ai.core.llm.LLMProvider;

public class BasicAgentExample {

    public Agent createSimpleAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("simple-assistant")
            .description("A simple assistant")
            .llmProvider(llmProvider)
            .build();
    }

    public void useAgent(LLMProvider llmProvider) {
        Agent agent = createSimpleAgent(llmProvider);

        // Execute query
        String output = agent.run("Hello, introduce yourself");
        System.out.println(output);

        // Check status
        if (agent.getNodeStatus() == NodeStatus.COMPLETED) {
            System.out.println("Execution successful");
        }
    }
}
```

### 2. Configured Agent

```java
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;

public class ConfiguredAgentExample {

    public Agent createConfiguredAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("customer-service")
            .description("Customer service agent")
            .llmProvider(llmProvider)

            // Basic configuration
            .systemPrompt("You are a professional customer service representative...")
            .temperature(0.7)
            .model("gpt-4")

            // Tool configuration
            .toolCalls(List.of(searchTool, orderTool))

            // Turn limit
            .maxTurn(20)  // Maximum conversation turns (default: 20)

            // Reflection
            .enableReflection(true)

            .build();
    }

    public void runWithContext(Agent agent) {
        // Create execution context
        ExecutionContext context = ExecutionContext.builder()
            .userId("user-123")
            .sessionId("session-456")
            .build();

        // Run with context
        String response = agent.run("I have a question about my order", context);
        System.out.println(response);
    }
}
```

### 3. Streaming Agent

```java
import ai.core.agent.streaming.StreamingCallback;

public class StreamingAgentExample {

    public Agent createStreamingAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("streaming-agent")
            .description("Agent with streaming output")
            .llmProvider(llmProvider)
            .streaming(true)
            .streamingCallback(new StreamingCallback() {
                @Override
                public void onToken(String token) {
                    System.out.print(token);  // Print each token as received
                }

                @Override
                public void onComplete(String fullResponse) {
                    System.out.println("\n--- Complete ---");
                }

                @Override
                public void onError(Throwable error) {
                    System.err.println("Error: " + error.getMessage());
                }
            })
            .build();
    }
}
```

## System Prompts and Templates

### 1. Static System Prompt

```java
Agent agent = Agent.builder()
    .name("technical-writer")
    .description("Technical documentation expert")
    .llmProvider(llmProvider)
    .systemPrompt("""
        You are a professional technical documentation expert.

        Your responsibilities:
        1. Write clear, accurate technical documentation
        2. Use concise language to explain complex concepts
        3. Provide practical code examples

        Writing style:
        - Use active voice
        - Stay objective and neutral
        - Structure content logically

        Always respond in the user's language.
        """)
    .build();
```

### 2. Dynamic Templates (Mustache)

Core-AI uses Mustache template engine for dynamic prompts:

```java
public class TemplatedAgentExample {

    public Agent createTemplatedAgent(LLMProvider llmProvider) {
        // Template with Mustache syntax
        String systemPromptTemplate = """
            You are an AI assistant for {{company}}.

            Company information:
            - Industry: {{industry}}
            - Main products: {{products}}
            - Service hours: {{serviceHours}}

            Current time: {{currentTime}}
            User location: {{userLocation}}

            Please provide personalized service based on the above information.
            """;

        return Agent.builder()
            .name("templated-agent")
            .description("Agent with dynamic prompts")
            .llmProvider(llmProvider)
            .systemPrompt(systemPromptTemplate)
            .build();
    }

    public void runWithVariables(Agent agent) {
        // Pass variables through ExecutionContext
        Map<String, Object> variables = Map.of(
            "company", "Tech Innovation Inc",
            "industry", "Artificial Intelligence",
            "products", "AI Solutions",
            "serviceHours", "9:00-18:00",
            "currentTime", LocalDateTime.now().toString(),
            "userLocation", "Beijing"
        );

        ExecutionContext context = ExecutionContext.builder()
            .customVariables(variables)
            .build();

        agent.run("Hello, what services do you offer?", context);
    }
}
```

### 3. Prompt Template for User Query

```java
public class PromptTemplateExample {

    public Agent createAgentWithPromptTemplate(LLMProvider llmProvider) {
        return Agent.builder()
            .name("qa-agent")
            .description("Q&A agent with template")
            .llmProvider(llmProvider)
            .systemPrompt("You are a helpful Q&A assistant.")
            // promptTemplate is prepended to user query
            .promptTemplate("""
                Context: {{context}}

                Please answer the following question:
                """)
            .build();
    }

    public void runWithTemplate(Agent agent) {
        ExecutionContext context = ExecutionContext.builder()
            .customVariable("context", "This is about Java programming")
            .build();

        // Final prompt = promptTemplate + user query
        agent.run("How do I create a thread pool?", context);
    }
}
```

### 4. Langfuse Prompt Integration

```java
// Fetch prompts from Langfuse prompt management
Agent agent = Agent.builder()
    .name("langfuse-agent")
    .description("Agent using Langfuse prompts")
    .llmProvider(llmProvider)
    .langfuseSystemPrompt("customer-service-prompt")  // Prompt name in Langfuse
    .langfusePromptVersion(2)  // Optional: specific version
    // Or use label
    // .langfusePromptLabel("production")
    .build();
```

## Reflection Mechanism

Reflection allows agents to evaluate and improve their responses.

### 1. Basic Reflection

```java
import ai.core.reflection.ReflectionConfig;

public class ReflectionExample {

    public Agent createReflectiveAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("reflective-agent")
            .description("Agent with reflection capability")
            .llmProvider(llmProvider)
            .enableReflection(true)  // Use default reflection config
            .build();
    }
}
```

### 2. Custom Reflection Configuration

```java
public class CustomReflectionExample {

    public Agent createCustomReflectiveAgent(LLMProvider llmProvider) {
        // Create custom reflection config
        ReflectionConfig reflectionConfig = ReflectionConfig.builder()
            .enabled(true)
            .minRound(1)           // Minimum reflection rounds
            .maxRound(3)           // Maximum reflection rounds
            .evaluationCriteria("""
                Evaluate the response based on:
                1. Accuracy - Is the information correct?
                2. Completeness - Are all aspects covered?
                3. Clarity - Is the explanation clear?
                4. Usefulness - Does it help the user?

                Score from 1-10, where 8+ means pass.
                """)
            .build();

        return Agent.builder()
            .name("custom-reflective-agent")
            .description("Agent with custom reflection")
            .llmProvider(llmProvider)
            .reflectionConfig(reflectionConfig)
            .build();
    }
}
```

### 3. Reflection with Listener

```java
import ai.core.reflection.ReflectionListener;
import ai.core.reflection.ReflectionHistory;
import ai.core.reflection.ReflectionEvaluation;

public class ReflectionListenerExample {

    public Agent createAgentWithReflectionListener(LLMProvider llmProvider) {
        ReflectionListener listener = new ReflectionListener() {
            @Override
            public void onReflectionStart(Agent agent, String input, String criteria) {
                System.out.println("Starting reflection for: " + input);
            }

            @Override
            public void onBeforeRound(Agent agent, int round, String solution) {
                System.out.println("Round " + round + " - Evaluating solution");
            }

            @Override
            public void onAfterRound(Agent agent, int round, String improved, ReflectionEvaluation eval) {
                System.out.println("Round " + round + " - Score: " + eval.getScore());
            }

            @Override
            public void onScoreAchieved(Agent agent, int score, int round) {
                System.out.println("Target score achieved: " + score + " at round " + round);
            }

            @Override
            public void onNoImprovement(Agent agent, int score, int round) {
                System.out.println("No further improvement possible at round " + round);
            }

            @Override
            public void onMaxRoundsReached(Agent agent, int finalScore) {
                System.out.println("Max rounds reached. Final score: " + finalScore);
            }

            @Override
            public void onError(Agent agent, int round, Exception e) {
                System.err.println("Reflection error at round " + round + ": " + e.getMessage());
            }

            @Override
            public void onReflectionComplete(Agent agent, ReflectionHistory history) {
                System.out.println("Reflection complete. Total rounds: " + history.getRounds().size());
            }
        };

        return Agent.builder()
            .name("monitored-agent")
            .description("Agent with reflection monitoring")
            .llmProvider(llmProvider)
            .enableReflection(true)
            .reflectionListener(listener)
            .build();
    }
}
```

### 4. Simple Reflection with Evaluation Criteria

```java
// Shorthand for simple reflection setup
Agent agent = Agent.builder()
    .name("simple-reflective")
    .description("Simple reflective agent")
    .llmProvider(llmProvider)
    .reflectionEvaluationCriteria("""
        Check if the code is correct and well-documented.
        Score 8+ if code is functional and has comments.
        """)
    .build();
```

## State Management

### 1. Agent Status

```java
import ai.core.agent.NodeStatus;

public class StatusManagementExample {

    public void handleAgentStatus(Agent agent) {
        String response = agent.run("Execute a task");

        switch (agent.getNodeStatus()) {
            case INITED:
                System.out.println("Agent initialized");
                break;

            case RUNNING:
                System.out.println("Agent running");
                break;

            case COMPLETED:
                System.out.println("Task completed");
                System.out.println("Result: " + agent.getOutput());
                break;

            case WAITING_FOR_USER_INPUT:
                System.out.println("Waiting for user input");
                // This happens when a tool requires authentication
                // User needs to confirm with "yes"
                agent.run("yes");  // Confirm and continue
                break;

            case FAILED:
                System.out.println("Task failed");
                break;
        }
    }
}
```

### 2. Persistence and Recovery

```java
import ai.core.persistence.PersistenceProvider;

public class PersistenceExample {

    public void setupPersistence(LLMProvider llmProvider, PersistenceProvider provider) {
        Agent agent = Agent.builder()
            .name("persistent-agent")
            .description("Agent with persistence")
            .llmProvider(llmProvider)
            .persistenceProvider(provider)
            .build();

        // Execute some tasks
        agent.run("Task 1");
        agent.run("Task 2");

        // Save state
        String agentId = agent.save("my-agent-session");
        System.out.println("Agent state saved: " + agentId);
    }

    public void restoreAgent(LLMProvider llmProvider, PersistenceProvider provider) {
        Agent agent = Agent.builder()
            .name("persistent-agent")
            .description("Agent with persistence")
            .llmProvider(llmProvider)
            .persistenceProvider(provider)
            .build();

        // Load saved state
        agent.load("my-agent-session");

        // Continue conversation with restored history
        String response = agent.run("Continue our previous discussion");
        System.out.println(response);

        // Check restored message history
        List<Message> history = agent.getMessages();
        System.out.println("Restored " + history.size() + " messages");
    }
}
```

### 3. Reset Agent State

```java
public class ResetExample {

    public void resetAgentState(Agent agent) {
        // Execute some tasks
        agent.run("Question 1");
        agent.run("Question 2");

        // Reset to initial state
        agent.reset();

        // Agent now has no history
        System.out.println("Messages after reset: " + agent.getMessages().size());  // 0
        System.out.println("Status: " + agent.getNodeStatus());  // INITED
    }
}
```

### 4. Token Usage Tracking

```java
import ai.core.llm.domain.Usage;

public class TokenTrackingExample {

    public void trackTokenUsage(Agent agent) {
        agent.run("Analyze this complex problem...");

        Usage usage = agent.getCurrentTokenUsage();
        System.out.println("Prompt tokens: " + usage.getPromptTokens());
        System.out.println("Completion tokens: " + usage.getCompletionTokens());
        System.out.println("Total tokens: " + usage.getTotalTokens());
    }
}
```

## Best Practices

### 1. Single Responsibility Principle

```java
public class BestPracticesExample {

    // GOOD: Single responsibility
    public Agent createCodeReviewer(LLMProvider llmProvider) {
        return Agent.builder()
            .name("code-reviewer")
            .description("Code review specialist")
            .llmProvider(llmProvider)
            .systemPrompt("""
                You are a code review expert.
                Your only responsibilities:
                1. Check code quality
                2. Identify potential issues
                3. Provide improvement suggestions
                """)
            .build();
    }

    // BAD: Too many responsibilities
    public Agent createOverloadedAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("do-everything")
            .description("Agent that does everything")  // Too broad
            .llmProvider(llmProvider)
            .systemPrompt("""
                You do code review, write docs,
                manage projects, answer questions, deploy apps...
                """)
            .build();
    }
}
```

### 2. Error Handling

```java
public class ErrorHandlingExample {

    public String executeWithErrorHandling(Agent agent, String query) {
        try {
            String output = agent.run(query);

            if (agent.getNodeStatus() == NodeStatus.FAILED) {
                // Log error and use fallback
                logger.error("Agent execution failed");
                return fallbackResponse(query);
            }

            return output;

        } catch (Exception e) {
            logger.error("Unexpected error", e);
            // Graceful degradation
            return "System is busy, please try again later.";
        }
    }

    private String fallbackResponse(String query) {
        return "Sorry, I cannot process this request at the moment.";
    }
}
```

### 3. Proper Context Usage

```java
public class ContextExample {

    public void properContextUsage(Agent agent) {
        // Create context with all necessary info
        ExecutionContext context = ExecutionContext.builder()
            .userId("user-123")          // For memory isolation
            .sessionId("session-456")    // For session tracking
            .customVariable("locale", "en-US")
            .customVariable("timezone", "UTC")
            .build();

        // Always pass context for consistent behavior
        agent.run("What time is it?", context);
    }
}
```

### 4. Observability with Tracing

```java
import ai.core.telemetry.AgentTracer;

public class ObservabilityExample {

    public Agent createObservableAgent(LLMProvider llmProvider, AgentTracer tracer) {
        return Agent.builder()
            .name("observable-agent")
            .description("Agent with tracing")
            .llmProvider(llmProvider)
            .tracer(tracer)  // Enable distributed tracing
            .build();
    }
}
```

### 5. Memory Integration

```java
import ai.core.memory.Memory;

public class MemoryIntegrationExample {

    public Agent createPersonalizedAgent(LLMProvider llmProvider, Memory memory) {
        return Agent.builder()
            .name("personalized-agent")
            .description("Agent with long-term memory")
            .llmProvider(llmProvider)
            .enableCompression(true)      // Session context management
            .unifiedMemory(memory)        // Cross-session memory
            .systemPrompt("""
                You are a personalized assistant.
                Use the memory recall tool to remember user preferences.
                """)
            .build();
    }
}
```

## Summary

This tutorial covered:

1. **Agent Basics**: Understanding Agent structure and execution flow
2. **Creating Agents**: From simple to fully configured agents
3. **System Prompts**: Static prompts, Mustache templates, Langfuse integration
4. **Reflection**: Self-evaluation and improvement mechanism
5. **State Management**: Status handling, persistence, token tracking
6. **Best Practices**: Single responsibility, error handling, observability

Next steps:
- Learn [Tool Calling](tutorial-tool-calling.md) to extend agent capabilities
- Explore [Memory Systems](tutorial-memory.md) for persistent memory
- Learn [Compression](tutorial-compression.md) for context management
- Build [Multi-Agent Systems](tutorial-multi-agent.md) for complex applications
- Understand [Flow Orchestration](tutorial-flow.md) for workflows
