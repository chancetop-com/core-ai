# Tutorial: Building Intelligent Agents

This tutorial will dive deep into how to build powerful intelligent agents using Core-AI.

## Table of Contents

1. [Agent Fundamentals](#agent-fundamentals)
2. [Creating Basic Agents](#creating-basic-agents)
3. [System Prompts and Templates](#system-prompts-and-templates)
4. [Memory Systems](#memory-systems)
5. [Reflection Mechanisms](#reflection-mechanisms)
6. [State Management](#state-management)
7. [Best Practices](#best-practices)

## Agent Fundamentals

### What is an Agent?

In Core-AI, an agent is an autonomous AI entity that can:
- Understand and respond to natural language input
- Call tools to complete tasks
- Maintain conversation context and memory
- Reflect on and improve its responses

### Core Components of an Agent

```java
Agent agent = Agent.builder()
    .name("agent-name")           // Agent name
    .description("agent purpose")  // Agent description
    .llmProvider(provider)         // LLM provider
    .systemPrompt(prompt)          // System prompt
    .tools(toolList)               // Available tools
    .memory(memorySystem)          // Memory system
    .enableReflection(true)        // Reflection capability
    .build();
```

## Creating Basic Agents

### 1. The Simplest Agent

```java
import ai.core.agent.Agent;
import ai.core.agent.AgentOutput;
import ai.core.llm.LLMProvider;

public class BasicAgentExample {

    public Agent createSimpleAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("simple-assistant")
            .description("A simple assistant")
            .llmProvider(llmProvider)
            .build();
    }

    public void useAgent() {
        Agent agent = createSimpleAgent(llmProvider);

        // Execute query
        AgentOutput output = agent.execute("Hello, please introduce yourself");
        System.out.println(output.getOutput());

        // Check status
        if (output.getStatus() == NodeStatus.COMPLETED) {
            System.out.println("Execution successful");
        }
    }
}
```

### 2. Configured Agent

```java
public class ConfiguredAgentExample {

    public Agent createConfiguredAgent(LLMProvider llmProvider) {
        return Agent.builder()
            .name("customer-service")
            .description("Customer service agent")
            .llmProvider(llmProvider)

            // Basic configuration
            .systemPrompt("You are a professional customer service representative...")
            .maxTokens(2000)
            .temperature(0.7)
            .topP(0.95)

            // Advanced features
            .streaming(true)           // Streaming output
            .enableReflection(true)    // Enable reflection
            .maxReflectionDepth(2)     // Reflection depth

            // Retry strategy
            .maxRetries(3)
            .retryDelay(1000)          // milliseconds

            .build();
    }
}
```

## System Prompts and Templates

### 1. Static System Prompts

```java
Agent agent = Agent.builder()
    .name("technical-writer")
    .llmProvider(llmProvider)
    .systemPrompt("""
        You are a professional technical documentation expert.

        Your responsibilities:
        1. Write clear and accurate technical documentation
        2. Explain complex concepts in simple language
        3. Provide practical code examples

        Writing style:
        - Use active voice
        - Remain objective and neutral
        - Structure content logically
        """)
    .build();
```

### 2. Dynamic Templates (Mustache)

```java
import ai.core.prompt.PromptTemplate;

public class TemplatedAgentExample {

    public Agent createTemplatedAgent(LLMProvider llmProvider) {
        // Create prompt template
        String template = """
            You are an AI assistant for {{company}}.

            Company Information:
            - Industry: {{industry}}
            - Main Products: {{products}}
            - Service Hours: {{serviceHours}}

            Current Time: {{currentTime}}
            User Location: {{userLocation}}

            Please provide personalized service based on the above information.
            """;

        // Prepare template data
        Map<String, Object> templateData = Map.of(
            "company", "Tech Innovation Inc.",
            "industry", "Artificial Intelligence",
            "products", "AI Solutions",
            "serviceHours", "9:00-18:00",
            "currentTime", LocalDateTime.now().toString(),
            "userLocation", "New York"
        );

        // Render template
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

## Memory Systems

### 1. Short-term Memory (Session Memory)

```java
import ai.core.memory.NaiveMemory;

public class MemoryAgentExample {

    public void demonstrateShortTermMemory() {
        // Agents maintain session history by default
        Agent agent = Agent.builder()
            .name("memory-agent")
            .llmProvider(llmProvider)
            .systemPrompt("You are an assistant with memory")
            .build();

        // First conversation
        AgentOutput output1 = agent.execute("My name is John");
        System.out.println(output1.getOutput());
        // Output: Hello John! Nice to meet you.

        // Second conversation (remembers the name)
        AgentOutput output2 = agent.execute("Do you remember my name?");
        System.out.println(output2.getOutput());
        // Output: Of course, your name is John.

        // Get conversation history
        List<Message> history = agent.getMessages();
        System.out.println("Conversation rounds: " + history.size());
    }
}
```

### 2. Long-term Memory

```java
import ai.core.memory.Memory;
import ai.core.memory.NaiveMemory;

public class LongTermMemoryExample {

    public Agent createAgentWithLongTermMemory() {
        // Create long-term memory system
        Memory longTermMemory = new NaiveMemory();

        // Preload memory
        longTermMemory.save("user_preferences", Map.of(
            "name", "John",
            "role", "Software Engineer",
            "project", "AI Platform",
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
                You are a project assistant. Use your memory to provide personalized help.

                When answering, please refer to:
                - User's personal information and preferences
                - Project context and constraints
                - Previous conversation history
                """)
            .build();
    }
}
```

## Reflection Mechanisms

### 1. Basic Reflection

```java
public class ReflectionExample {

    public Agent createReflectiveAgent() {
        return Agent.builder()
            .name("reflective-agent")
            .llmProvider(llmProvider)
            .enableReflection(true)
            .reflectionPrompt("""
                Please reflect on your answer:
                1. Is the answer accurate and complete?
                2. Is there any important information missing?
                3. Is the expression clear and understandable?
                4. Does it need improvement?

                If improvement is needed, please provide a better answer.
                """)
            .maxReflectionDepth(2)  // Reflect up to 2 times
            .build();
    }

    public void demonstrateReflection() {
        Agent agent = createReflectiveAgent();

        // Execute query with reflection
        AgentOutput output = agent.execute(
            "Explain what dependency injection is and give an example"
        );

        // View reflection process
        if (output.getReflections() != null) {
            for (Reflection reflection : output.getReflections()) {
                System.out.println("Reflection " + reflection.getDepth() + ":");
                System.out.println("Original: " + reflection.getOriginalOutput());
                System.out.println("Reflection: " + reflection.getReflectionContent());
                System.out.println("Improved: " + reflection.getImprovedOutput());
                System.out.println("---");
            }
        }

        // Final output (improved through reflection)
        System.out.println("Final answer: " + output.getOutput());
    }
}
```

## State Management

### 1. Agent States

```java
import ai.core.agent.NodeStatus;

public class StatusManagementExample {

    public void handleAgentStatus() {
        Agent agent = createAgent();

        // Execute and check status
        AgentOutput output = agent.execute("Execute a task");

        switch (output.getStatus()) {
            case PENDING:
                System.out.println("Task pending");
                break;

            case RUNNING:
                System.out.println("Task running");
                break;

            case COMPLETED:
                System.out.println("Task completed");
                System.out.println("Result: " + output.getOutput());
                break;

            case WAITING_FOR_USER_INPUT:
                System.out.println("Waiting for user input");
                System.out.println("Prompt: " + output.getWaitingMessage());
                // Collect user input
                String userInput = getUserInput();
                // Continue execution
                output = agent.continueWithInput(userInput);
                break;

            case FAILED:
                System.out.println("Task failed");
                System.out.println("Error: " + output.getError());
                // Optional: retry
                if (shouldRetry()) {
                    output = agent.retry();
                }
                break;
        }
    }
}
```

### 2. Persistence and Recovery

```java
import ai.core.persistence.AgentPersistence;

public class PersistenceExample {

    private final AgentPersistence persistence = new AgentPersistence();

    public void saveAgentState() {
        Agent agent = createConfiguredAgent();

        // Execute some tasks
        agent.execute("Task 1");
        agent.execute("Task 2");

        // Save state
        String agentId = agent.getId();
        persistence.saveAgent(agentId, agent);

        System.out.println("Agent state saved: " + agentId);
    }

    public void restoreAgentState(String agentId) {
        // Restore agent state
        Agent restoredAgent = persistence.loadAgent(agentId);

        if (restoredAgent != null) {
            // Continue previous conversation
            AgentOutput output = restoredAgent.execute("Continue our previous discussion");
            System.out.println(output.getOutput());

            // View restored history
            List<Message> history = restoredAgent.getMessages();
            System.out.println("Restored " + history.size() + " messages");
        }
    }
}
```

## Best Practices

### 1. Agent Design Principles

```java
public class BestPracticesExample {

    // ✅ Good practice: Single responsibility
    public Agent createSpecializedAgent() {
        return Agent.builder()
            .name("code-reviewer")
            .description("Agent specialized in code review")
            .systemPrompt("""
                You are a code review expert.
                Only responsible for:
                1. Checking code quality
                2. Finding potential issues
                3. Providing improvement suggestions
                """)
            .build();
    }

    // ❌ Bad practice: Too many responsibilities
    public Agent createOverloadedAgent() {
        return Agent.builder()
            .name("do-everything")
            .description("Agent that does everything")
            .systemPrompt("""
                You do code review, write documentation,
                manage projects, answer questions, deploy applications...
                """)
            .build();
    }
}
```

### 2. Error Handling

```java
public class ErrorHandlingExample {

    public AgentOutput executeWithErrorHandling(Agent agent, String query) {
        try {
            // Set timeout
            AgentOutput output = agent.execute(query, 30000); // 30 seconds timeout

            if (output.getStatus() == NodeStatus.FAILED) {
                // Log error
                logger.error("Agent execution failed: {}", output.getError());

                // Try fallback strategy
                return fallbackStrategy(query);
            }

            return output;

        } catch (TimeoutException e) {
            logger.error("Agent execution timeout", e);
            return AgentOutput.failed("Execution timeout, please retry");

        } catch (Exception e) {
            logger.error("Unexpected error", e);
            // Graceful degradation
            return AgentOutput.failed("System busy, please try again later");
        }
    }

    private AgentOutput fallbackStrategy(String query) {
        // Use simpler model or predefined response
        return AgentOutput.success("Sorry, I cannot process this request at the moment.");
    }
}
```

### 3. Performance Optimization

```java
public class PerformanceOptimizationExample {

    // Use object pool to manage agents
    private final ObjectPool<Agent> agentPool;

    public PerformanceOptimizationExample() {
        // Create agent pool
        this.agentPool = new GenericObjectPool<>(new AgentFactory());
        agentPool.setMaxTotal(10);
        agentPool.setMaxIdle(5);
    }

    public AgentOutput processRequest(String query) throws Exception {
        Agent agent = null;
        try {
            // Borrow agent from pool
            agent = agentPool.borrowObject();
            return agent.execute(query);
        } finally {
            if (agent != null) {
                // Return agent to pool
                agentPool.returnObject(agent);
            }
        }
    }
}
```

## Summary

Through this tutorial, you've learned:

1. ✅ How to create and configure intelligent agents
2. ✅ How to use system prompts and templates
3. ✅ How to implement memory systems
4. ✅ How to enable reflection mechanisms
5. ✅ How to manage agent states
6. ✅ Best practices and performance optimization

Next steps:
- Learn [Tool Calling](tutorial-tool-calling.md) to enhance agent capabilities
- Explore [Multi-Agent Systems](tutorial-multi-agent.md) to build complex applications
- Understand [RAG Integration](tutorial-rag.md) for knowledge enhancement