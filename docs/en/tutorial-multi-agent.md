# Tutorial: Multi-Agent Systems

> 🚧 **Note**: This English translation is in progress. Please refer to the [Chinese version](../tutorial-multi-agent.md) for the complete content.

This tutorial covers building multi-agent systems with Core-AI.

## Table of Contents

1. [Multi-Agent System Overview](#multi-agent-system-overview)
2. [Agent Groups](#agent-groups)
3. [Handoff Strategies](#handoff-strategies)
4. [Planning Strategies](#planning-strategies)
5. [Inter-Agent Communication](#inter-agent-communication)
6. [Real-World Examples](#real-world-examples)

## Multi-Agent System Overview

Multi-agent systems allow multiple specialized agents to work together on complex tasks.

### Why Use Multiple Agents?

- **Specialization**: Each agent focuses on specific expertise
- **Scalability**: Distribute work across multiple agents
- **Flexibility**: Dynamic agent composition based on needs
- **Reliability**: Redundancy and fallback options

## Quick Example

```java
// Create specialized agents
Agent researcher = Agent.builder()
    .name("researcher")
    .description("Research and gather information")
    .build();

Agent writer = Agent.builder()
    .name("writer")
    .description("Write and organize content")
    .build();

// Create agent group
AgentGroup group = AgentGroup.builder()
    .name("content-team")
    .agents(List.of(researcher, writer))
    .handoffStrategy(new AutoHandoff())
    .build();

// Execute task
AgentGroupOutput output = group.execute("Write an article about AI");
```

## Learn More

For the complete tutorial with all examples and detailed explanations, please refer to:
- [Chinese Version](../tutorial-multi-agent.md) (Full content available)
- [Tutorials Index](tutorials.md)

---

[Back to Tutorials](tutorials.md) | [Next: RAG Integration →](tutorial-rag.md)
