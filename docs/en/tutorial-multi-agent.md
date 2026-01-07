# Tutorial: Multi-Agent Systems

This tutorial covers building multi-agent systems with Core-AI where multiple specialized agents work together.

## Table of Contents

1. [Multi-Agent System Overview](#multi-agent-system-overview)
2. [Agent Groups](#agent-groups)
3. [Handoff Strategies](#handoff-strategies)
4. [Planning Strategies](#planning-strategies)
5. [Inter-Agent Communication](#inter-agent-communication)
6. [Real-World Examples](#real-world-examples)

## Multi-Agent System Overview

### Why Use Multiple Agents?

While individual agents are powerful, they have limitations when handling complex tasks:
- Limited knowledge domains
- Context window constraints
- Difficulty with multi-step complex tasks

Multi-agent systems solve these problems through specialization and collaboration.

### Core-AI Multi-Agent Architecture

```
┌─────────────────────────────────────┐
│         AgentGroup                  │
│  ┌─────────┐  ┌─────────┐          │
│  │ Agent A │  │ Agent B │          │
│  └─────────┘  └─────────┘          │
│  ┌─────────┐  ┌─────────┐          │
│  │ Agent C │  │ Agent D │          │
│  └─────────┘  └─────────┘          │
│                                     │
│  Handoff Strategy + Planning        │
└─────────────────────────────────────┘
```

## Agent Groups

### 1. Creating a Basic Agent Group

```java
import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.AgentGroupOutput;

public class BasicAgentGroupExample {

    public AgentGroup createBasicAgentGroup(LLMProvider llmProvider) {
        // Create specialized agents
        Agent researchAgent = Agent.builder()
            .name("researcher")
            .description("Responsible for research and information gathering")
            .llmProvider(llmProvider)
            .systemPrompt("You are a research expert skilled at searching and analyzing information")
            .build();

        Agent writerAgent = Agent.builder()
            .name("writer")
            .description("Responsible for writing and organizing content")
            .llmProvider(llmProvider)
            .systemPrompt("You are a professional writer skilled at organizing and expressing information")
            .build();

        Agent reviewerAgent = Agent.builder()
            .name("reviewer")
            .description("Responsible for reviewing and improving content")
            .llmProvider(llmProvider)
            .systemPrompt("You are a strict reviewer ensuring content quality")
            .build();

        // Create agent group
        return AgentGroup.builder()
            .name("content-creation-team")
            .description("Content creation team")
            .agents(List.of(researchAgent, writerAgent, reviewerAgent))
            .llmProvider(llmProvider)  // For coordination
            .build();
    }

    public void useAgentGroup() {
        AgentGroup group = createBasicAgentGroup(llmProvider);

        // Execute task
        String task = "Write an article about AI applications in healthcare";
        AgentGroupOutput output = group.execute(task);

        // View results
        System.out.println("Final output: " + output.getFinalOutput());

        // View each agent's contribution
        for (AgentOutput agentOutput : output.getAgentOutputs()) {
            System.out.println(agentOutput.getAgentName() + ": " +
                             agentOutput.getOutput());
        }
    }
}
```

### 2. Configuring Agent Group Parameters

```java
public class ConfiguredAgentGroupExample {

    public AgentGroup createConfiguredAgentGroup() {
        List<Agent> agents = createSpecializedAgents();

        return AgentGroup.builder()
            .name("advanced-team")
            .agents(agents)
            .llmProvider(llmProvider)

            // Execution configuration
            .maxRounds(5)               // Maximum collaboration rounds
            .maxAgentsPerRound(2)       // Max agents activated per round
            .parallelExecution(true)    // Parallel execution

            // Termination condition
            .terminationCondition(output -> {
                // Custom termination condition
                return output.getFinalOutput().contains("DONE") ||
                       output.getRounds() >= 3;
            })

            // Selection strategy
            .agentSelectionStrategy(new PriorityBasedSelection())

            .build();
    }
}
```

## Handoff Strategies

### Handoff Mechanism Principles

Handoff is the core mechanism for Core-AI multi-agent collaboration, responsible for deciding "which agent executes next."

```
┌─────────────────────────────────────────────────────────────────┐
│                    Handoff Mechanism Architecture                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  AgentGroup.execute()                                           │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────┐           │
│  │           Handoff Strategy Execution             │           │
│  │                                                 │           │
│  │  handoff.handoff(agentGroup, planning, vars)    │           │
│  │       │                                         │           │
│  │       ├─ DirectHandoff: Sequential next Agent   │           │
│  │       │   └─ getNextAgentNameOf(agents, current)│           │
│  │       │                                         │           │
│  │       ├─ AutoHandoff: Moderator Agent decides   │           │
│  │       │   └─ moderator.run(query) → JSON result│           │
│  │       │                                         │           │
│  │       └─ HybridHandoff: Mixed strategy          │           │
│  │           └─ Check fixed routes first, else auto│           │
│  │                                                 │           │
│  └─────────────────────────────────────────────────┘           │
│       │                                                         │
│       ▼                                                         │
│  Planning Result                                                │
│  ├─ nextAgentName: Next Agent to execute                       │
│  ├─ nextQuery: Query/context to pass                           │
│  └─ nextAction: Action (may be termination word)               │
│       │                                                         │
│       ▼                                                         │
│  currentAgent = getAgentByName(planning.nextAgentName())        │
│  output = currentAgent.run(planning.nextQuery())                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Handoff Interface Definition**:

```java
public interface Handoff {
    /**
     * Execute handoff strategy, decide next execution plan
     * @param agentGroup Current agent group
     * @param planning Planning result container
     * @param variables Execution variables
     */
    void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables);

    /**
     * Get handoff type
     */
    HandoffType getType();
}
```

**Planning Result Structure**:

```java
public class DefaultPlanningResult {
    String name;      // Next Agent name
    String query;     // Query to pass to next Agent
    String planning;  // Planning explanation
    String nextStep;  // Next action (may be termination word "DONE")
}
```

### 1. Direct Handoff

```java
import ai.core.agent.handoff.DirectHandoff;
import ai.core.agent.handoff.HandoffMessage;

public class DirectHandoffExample {

    public AgentGroup createDirectHandoffGroup() {
        // Define handoff rules
        Map<String, List<String>> handoffRules = Map.of(
            "analyzer", List.of("planner"),       // analyzer → planner
            "planner", List.of("executor"),       // planner → executor
            "executor", List.of("reporter")       // executor → reporter
        );

        DirectHandoff handoff = new DirectHandoff(handoffRules);

        // Create agents with explicit handoff targets
        Agent analyzer = Agent.builder()
            .name("analyzer")
            .description("Analyze requirements")
            .systemPrompt("""
                Analyze user requirements.
                When done, use HANDOFF: planner to switch to planning agent.
                """)
            .build();

        Agent planner = Agent.builder()
            .name("planner")
            .description("Create plans")
            .systemPrompt("""
                Create execution plan based on analysis.
                When done, use HANDOFF: executor to switch to execution agent.
                """)
            .build();

        Agent executor = Agent.builder()
            .name("executor")
            .description("Execute tasks")
            .systemPrompt("""
                Execute tasks according to plan.
                When done, use HANDOFF: reporter to switch to reporting agent.
                """)
            .build();

        Agent reporter = Agent.builder()
            .name("reporter")
            .description("Generate reports")
            .systemPrompt("Generate execution report, mark DONE when complete.")
            .build();

        return AgentGroup.builder()
            .name("workflow-team")
            .agents(List.of(analyzer, planner, executor, reporter))
            .handoffStrategy(handoff)
            .startAgent("analyzer")  // Specify starting agent
            .build();
    }
}
```

### 2. Auto Handoff

```java
import ai.core.agent.handoff.AutoHandoff;

public class AutoHandoffExample {

    public AgentGroup createAutoHandoffGroup() {
        // Auto handoff: system automatically decides next agent
        AutoHandoff handoff = new AutoHandoff();

        // Create agents with clear responsibilities
        List<Agent> agents = List.of(
            Agent.builder()
                .name("requirement-analyst")
                .description("Requirements analysis expert, skilled at understanding and refining user needs")
                .capabilities(List.of("Requirements Analysis", "Use Case Design"))
                .build(),

            Agent.builder()
                .name("architect")
                .description("System architect, designs technical solutions")
                .capabilities(List.of("Architecture Design", "Technology Selection"))
                .build(),

            Agent.builder()
                .name("developer")
                .description("Development engineer, implements specific features")
                .capabilities(List.of("Coding", "Testing"))
                .build(),

            Agent.builder()
                .name("qa-engineer")
                .description("QA engineer, ensures code quality")
                .capabilities(List.of("Testing", "Code Review"))
                .build()
        );

        return AgentGroup.builder()
            .name("auto-handoff-team")
            .agents(agents)
            .handoffStrategy(handoff)
            .llmProvider(llmProvider)  // For automatic decision making
            .selectionPrompt("""
                Based on current task status and needs, select the most appropriate agent:

                Completed: {{completed_tasks}}
                Pending: {{pending_tasks}}

                Available agents and capabilities:
                {{#agents}}
                - {{name}}: {{description}}
                  Capabilities: {{capabilities}}
                {{/agents}}

                Select the next agent and explain your reasoning.
                """)
            .build();
    }
}
```

### 3. Hybrid Handoff

```java
import ai.core.agent.handoff.HybridAutoDirectHandoff;

public class HybridHandoffExample {

    public AgentGroup createHybridHandoffGroup() {
        // Hybrid strategy: combines auto and direct handoff
        Map<String, List<String>> fixedRoutes = Map.of(
            "validator", List.of("FINISH")  // validator always ends the flow
        );

        HybridAutoDirectHandoff handoff =
            new HybridAutoDirectHandoff(fixedRoutes);

        List<Agent> agents = List.of(
            Agent.builder()
                .name("coordinator")
                .description("Coordinator, assigns tasks")
                .systemPrompt("""
                    You are the coordinator. Analyze tasks and decide:
                    - Simple tasks: handle directly
                    - Complex tasks: assign to experts
                    - Needs validation: HANDOFF: validator
                    """)
                .build(),

            Agent.builder()
                .name("expert-1")
                .description("Domain expert 1")
                .build(),

            Agent.builder()
                .name("expert-2")
                .description("Domain expert 2")
                .build(),

            Agent.builder()
                .name("validator")
                .description("Validation expert")
                .systemPrompt("Validate results, ensure quality. End flow when complete.")
                .build()
        );

        return AgentGroup.builder()
            .name("hybrid-team")
            .agents(agents)
            .handoffStrategy(handoff)
            .build();
    }
}
```

## Planning Strategies

### 1. Sequential Planning

```java
import ai.core.agent.planning.SequentialPlanning;
import ai.core.agent.planning.TaskPlan;

public class SequentialPlanningExample {

    public AgentGroup createSequentialPlanningGroup() {
        // Sequential execution plan
        SequentialPlanning planning = new SequentialPlanning();

        return AgentGroup.builder()
            .name("sequential-team")
            .agents(createAgents())
            .planningStrategy(planning)
            .planningPrompt("""
                Break down the task into sequential steps:

                Task: {{task}}

                Generate step plan, each step specifies:
                1. Step description
                2. Responsible agent
                3. Expected output

                Format:
                STEP 1: [description] - AGENT: [agent_name] - OUTPUT: [expected_result]
                """)
            .build();
    }

    public void executeWithPlan() {
        AgentGroup group = createSequentialPlanningGroup();

        // Execute task with planning
        String task = "Create a user management system";
        AgentGroupOutput output = group.executeWithPlanning(task);

        // View execution plan
        TaskPlan plan = output.getExecutionPlan();
        for (TaskStep step : plan.getSteps()) {
            System.out.println("Step " + step.getOrder() + ": " +
                             step.getDescription());
            System.out.println("  Agent: " + step.getAssignedAgent());
            System.out.println("  Status: " + step.getStatus());
        }
    }
}
```

### 2. Parallel Planning

```java
import ai.core.agent.planning.ParallelPlanning;

public class ParallelPlanningExample {

    public AgentGroup createParallelPlanningGroup() {
        ParallelPlanning planning = new ParallelPlanning();

        return AgentGroup.builder()
            .name("parallel-team")
            .agents(createSpecializedAgents())
            .planningStrategy(planning)
            .maxParallelTasks(3)  // Max 3 parallel tasks
            .planningPrompt("""
                Analyze task and identify parallelizable parts:

                Task: {{task}}

                Generate parallel execution plan:
                PARALLEL_GROUP 1:
                  - TASK: [task1] - AGENT: [agentA]
                  - TASK: [task2] - AGENT: [agentB]

                SEQUENTIAL:
                  - TASK: [task3] - AGENT: [agentC] - DEPENDS_ON: GROUP_1
                """)
            .build();
    }
}
```

### 3. Adaptive Planning

```java
import ai.core.agent.planning.AdaptivePlanning;

public class AdaptivePlanningExample {

    public AgentGroup createAdaptivePlanningGroup() {
        // Adaptive planning: dynamically adjusts based on execution
        AdaptivePlanning planning = new AdaptivePlanning();

        return AgentGroup.builder()
            .name("adaptive-team")
            .agents(createAgents())
            .planningStrategy(planning)

            // Re-planning condition
            .replanCondition(output -> {
                // Re-plan when errors occur or deviating from expectations
                return output.hasErrors() ||
                       output.getCompletionRate() < 0.5;
            })

            // Plan evaluation
            .planEvaluator(plan -> {
                // Evaluate plan quality
                double score = 0;
                score += plan.getSteps().size() <= 10 ? 1 : 0.5;
                score += plan.getParallelizationRate() * 0.5;
                return score;
            })

            .build();
    }
}
```

## Inter-Agent Communication

### 1. Message Passing

```java
import ai.core.a2a.AgentMessage;
import ai.core.a2a.MessageBus;

public class AgentCommunicationExample {

    private final MessageBus messageBus = new MessageBus();

    public AgentGroup createCommunicatingGroup() {
        // Create agents that can communicate with each other
        Agent dataCollector = Agent.builder()
            .name("data-collector")
            .systemPrompt("""
                Collect data.
                When done, send message to analyzer:
                MESSAGE_TO: analyzer
                DATA: {{collected_data}}
                """)
            .messageHandler((message) -> {
                // Handle received messages
                System.out.println("Received message: " + message);
            })
            .build();

        Agent analyzer = Agent.builder()
            .name("analyzer")
            .systemPrompt("""
                Analyze received data.
                When needing more data:
                MESSAGE_TO: data-collector
                REQUEST: {{data_type}}
                """)
            .messageHandler((message) -> {
                if (message.getType().equals("DATA")) {
                    // Process data
                    return analyzeData(message.getContent());
                }
                return null;
            })
            .build();

        // Register to message bus
        messageBus.register(dataCollector);
        messageBus.register(analyzer);

        return AgentGroup.builder()
            .name("communicating-team")
            .agents(List.of(dataCollector, analyzer))
            .messageBus(messageBus)
            .build();
    }
}
```

### 2. Shared Context

```java
import ai.core.agent.SharedContext;

public class SharedContextExample {

    public AgentGroup createContextSharingGroup() {
        // Create shared context
        SharedContext sharedContext = new SharedContext();

        Agent researcher = Agent.builder()
            .name("researcher")
            .sharedContext(sharedContext)
            .systemPrompt("""
                Research information and update shared context:
                CONTEXT_UPDATE: research_findings = {{findings}}
                """)
            .build();

        Agent writer = Agent.builder()
            .name("writer")
            .sharedContext(sharedContext)
            .systemPrompt("""
                Write using research findings from shared context:
                Available context: {{shared_context}}
                """)
            .build();

        return AgentGroup.builder()
            .name("context-sharing-team")
            .agents(List.of(researcher, writer))
            .sharedContext(sharedContext)
            .contextSyncStrategy(ContextSyncStrategy.IMMEDIATE)
            .build();
    }

    public void demonstrateContextSharing() {
        AgentGroup group = createContextSharingGroup();

        // First agent updates context
        group.execute("Research latest advances in quantum computing");

        // View shared context
        SharedContext context = group.getSharedContext();
        System.out.println("Shared research findings: " +
                         context.get("research_findings"));

        // Second agent uses context
        AgentGroupOutput output = group.execute("Write an article based on the research");
        System.out.println("Article: " + output.getFinalOutput());
    }
}
```

### 3. Collaboration Protocols

```java
public class CollaborationProtocolExample {

    public AgentGroup createProtocolBasedGroup() {
        // Define collaboration protocol
        CollaborationProtocol protocol = CollaborationProtocol.builder()
            .name("code-review-protocol")

            // Define roles
            .role("author", "Code author")
            .role("reviewer", "Code reviewer")
            .role("approver", "Approver")

            // Define flow
            .step(1, "author", "Submit code")
            .step(2, "reviewer", "Review code", parallel: true)
            .step(3, "author", "Modify code", conditional: "hasIssues")
            .step(4, "approver", "Final approval")

            // Define message format
            .messageFormat("REVIEW", Map.of(
                "code", "String",
                "comments", "List<String>",
                "approved", "Boolean"
            ))

            .build();

        // Create agents following the protocol
        List<Agent> agents = createProtocolAgents(protocol);

        return AgentGroup.builder()
            .name("protocol-team")
            .agents(agents)
            .collaborationProtocol(protocol)
            .protocolEnforcement(true)  // Enforce protocol compliance
            .build();
    }
}
```

## Real-World Examples

### Example 1: Customer Support System

```java
public class CustomerSupportSystemExample {

    public AgentGroup createCustomerSupportTeam() {
        // Receptionist agent
        Agent receptionist = Agent.builder()
            .name("receptionist")
            .description("Greet customers, understand needs")
            .systemPrompt("""
                You are a customer service receptionist.
                Responsibilities:
                1. Greet customers warmly
                2. Understand customer issues
                3. Categorize issues (technical/billing/general)
                4. Transfer to appropriate expert
                """)
            .tools(List.of(
                new CustomerInfoLookupTool(),
                new TicketCreationTool()
            ))
            .build();

        // Technical support agent
        Agent techSupport = Agent.builder()
            .name("tech-support")
            .description("Solve technical problems")
            .systemPrompt("""
                You are a technical support expert.
                Expertise:
                - Troubleshooting
                - Configuration guidance
                - Performance optimization
                Use knowledge base to solve problems.
                """)
            .enableRAG(true)
            .ragConfig(techKnowledgeBase())
            .build();

        // Billing expert
        Agent billingExpert = Agent.builder()
            .name("billing-expert")
            .description("Handle billing and payment issues")
            .systemPrompt("You are a billing expert...")
            .tools(List.of(
                new BillingSystemTool(),
                new RefundProcessingTool()
            ))
            .build();

        // Quality assurance agent
        Agent qualityAssurance = Agent.builder()
            .name("qa-agent")
            .description("Ensure service quality")
            .systemPrompt("""
                Evaluate conversation quality:
                - Is the issue resolved
                - Is the customer satisfied
                - Is follow-up needed
                """)
            .build();

        // Create support team
        return AgentGroup.builder()
            .name("customer-support-team")
            .agents(List.of(
                receptionist,
                techSupport,
                billingExpert,
                qualityAssurance
            ))

            // Use auto handoff
            .handoffStrategy(new AutoHandoff())

            // Set starting agent
            .startAgent("receptionist")

            // Termination condition
            .terminationCondition(output ->
                output.getFinalOutput().contains("RESOLVED") ||
                output.getFinalOutput().contains("ESCALATED")
            )

            .build();
    }

    public void handleCustomerQuery() {
        AgentGroup supportTeam = createCustomerSupportTeam();

        String customerQuery = "My app can't connect to the database, and I have billing issues";

        AgentGroupOutput result = supportTeam.execute(customerQuery);

        // View handling flow
        System.out.println("Handling flow:");
        for (AgentOutput step : result.getAgentOutputs()) {
            System.out.println(step.getAgentName() + ": " +
                             step.getOutput().substring(0, 100) + "...");
        }

        System.out.println("\nFinal solution: " + result.getFinalOutput());
    }
}
```

### Example 2: Research Assistant Team

```java
public class ResearchAssistantTeamExample {

    public AgentGroup createResearchTeam() {
        // Research planner
        Agent planner = Agent.builder()
            .name("research-planner")
            .description("Create research plans")
            .systemPrompt("""
                Create detailed research plans:
                1. Define research questions
                2. List information sources
                3. Assign research tasks
                4. Set timeline
                """)
            .build();

        // Literature researcher
        Agent literatureResearcher = Agent.builder()
            .name("literature-researcher")
            .description("Search and analyze academic literature")
            .tools(List.of(
                new ArxivSearchTool(),
                new PubMedSearchTool(),
                new GoogleScholarTool()
            ))
            .build();

        // Data analyst
        Agent dataAnalyst = Agent.builder()
            .name("data-analyst")
            .description("Analyze research data")
            .systemPrompt("Analyze data, identify patterns and trends")
            .tools(List.of(
                new StatisticalAnalysisTool(),
                new DataVisualizationTool()
            ))
            .build();

        // Report writer
        Agent reportWriter = Agent.builder()
            .name("report-writer")
            .description("Write research reports")
            .systemPrompt("""
                Write structured research reports:
                - Executive summary
                - Methodology
                - Findings
                - Conclusions
                - References
                """)
            .build();

        // Peer reviewer
        Agent peerReviewer = Agent.builder()
            .name("peer-reviewer")
            .description("Review research quality")
            .systemPrompt("""
                Rigorously review research quality:
                - Methodological rigor
                - Data reliability
                - Conclusion validity
                - Citation accuracy
                """)
            .build();

        // Create research team
        return AgentGroup.builder()
            .name("research-team")
            .agents(List.of(
                planner,
                literatureResearcher,
                dataAnalyst,
                reportWriter,
                peerReviewer
            ))

            // Use hybrid handoff strategy
            .handoffStrategy(new HybridAutoDirectHandoff(Map.of(
                "report-writer", List.of("peer-reviewer"),
                "peer-reviewer", List.of("report-writer", "FINISH")
            )))

            // Parallel literature and data research
            .parallelExecution(true)
            .maxParallelTasks(2)

            // Shared research context
            .sharedContext(new SharedContext())

            .build();
    }

    public void conductResearch(String topic) {
        AgentGroup researchTeam = createResearchTeam();

        // Execute research
        AgentGroupOutput result = researchTeam.executeWithPlanning(
            "Research topic: " + topic +
            "\nRequirements: Comprehensive literature review, data analysis, and detailed report"
        );

        // Save research results
        saveResearchResults(result);

        // Generate citations
        generateCitations(result);
    }
}
```

### Example 3: Software Development Team

```java
public class SoftwareDevelopmentTeamExample {

    public AgentGroup createDevTeam() {
        // Product manager
        Agent productManager = Agent.builder()
            .name("product-manager")
            .description("Define requirements and user stories")
            .systemPrompt("Convert business requirements into clear user stories and acceptance criteria")
            .build();

        // Architect
        Agent architect = Agent.builder()
            .name("architect")
            .description("Design system architecture")
            .systemPrompt("Design scalable, maintainable system architecture")
            .tools(List.of(new DiagramGenerationTool()))
            .build();

        // Frontend developer
        Agent frontendDev = Agent.builder()
            .name("frontend-developer")
            .description("Develop user interfaces")
            .systemPrompt("Develop responsive user interfaces using React")
            .tools(List.of(new CodeGenerationTool("javascript")))
            .build();

        // Backend developer
        Agent backendDev = Agent.builder()
            .name("backend-developer")
            .description("Develop APIs and business logic")
            .systemPrompt("Develop RESTful APIs using Java Spring Boot")
            .tools(List.of(new CodeGenerationTool("java")))
            .build();

        // Test engineer
        Agent tester = Agent.builder()
            .name("test-engineer")
            .description("Write and execute tests")
            .systemPrompt("Write comprehensive unit and integration tests")
            .tools(List.of(
                new TestGenerationTool(),
                new TestExecutionTool()
            ))
            .build();

        // DevOps engineer
        Agent devops = Agent.builder()
            .name("devops-engineer")
            .description("Handle deployment and infrastructure")
            .systemPrompt("Configure CI/CD and containerized deployment")
            .tools(List.of(
                new DockerTool(),
                new KubernetesTool(),
                new JenkinsTool()
            ))
            .build();

        // Create development team
        return AgentGroup.builder()
            .name("dev-team")
            .agents(List.of(
                productManager,
                architect,
                frontendDev,
                backendDev,
                tester,
                devops
            ))

            // Use capability-based auto handoff
            .handoffStrategy(new CapabilityBasedHandoff())

            // Enable parallel development
            .parallelExecution(true)
            .parallelizationStrategy(task -> {
                // Frontend and backend can develop in parallel
                if (task.contains("frontend") || task.contains("backend")) {
                    return true;
                }
                return false;
            })

            // Use Kanban to track progress
            .progressTracker(new KanbanProgressTracker())

            .build();
    }

    public void developFeature(String featureDescription) {
        AgentGroup devTeam = createDevTeam();

        // Create development plan
        DevelopmentPlan plan = createDevelopmentPlan(featureDescription);

        // Execute development
        AgentGroupOutput result = devTeam.executeWithPlan(plan);

        // Code review
        performCodeReview(result);

        // Deploy to staging
        deployToStaging(result);
    }
}
```

## Performance Optimization

### 1. Parallel Execution Optimization

```java
public class ParallelOptimizationExample {

    public AgentGroup createOptimizedParallelGroup() {
        return AgentGroup.builder()
            .name("optimized-team")
            .agents(createAgents())

            // Thread pool configuration
            .executorService(Executors.newFixedThreadPool(10))

            // Batch processing configuration
            .batchSize(5)
            .batchTimeout(Duration.ofSeconds(30))

            // Load balancing
            .loadBalancer(new RoundRobinLoadBalancer())

            // Resource limits
            .maxConcurrentAgents(5)
            .maxMemoryPerAgent(512 * 1024 * 1024) // 512MB

            .build();
    }
}
```

### 2. Caching Strategy

```java
public class CachingStrategyExample {

    public AgentGroup createCachedAgentGroup() {
        // Create cache configuration
        CacheConfig cacheConfig = CacheConfig.builder()
            .maxSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .recordStats(true)
            .build();

        return AgentGroup.builder()
            .name("cached-team")
            .agents(createAgents())

            // Enable response cache
            .enableResponseCache(true)
            .cacheConfig(cacheConfig)

            // Cache key generation strategy
            .cacheKeyGenerator((agent, input) ->
                agent.getName() + ":" + input.hashCode()
            )

            .build();
    }
}
```

## Monitoring and Debugging

### 1. Execution Tracing

```java
public class ExecutionTracingExample {

    @Inject
    private AgentTracer tracer;

    public void traceGroupExecution() {
        AgentGroup group = createTracedAgentGroup();

        // Add execution listener
        group.addExecutionListener(new GroupExecutionListener() {
            @Override
            public void onAgentStart(String agentName) {
                tracer.startSpan("agent." + agentName);
            }

            @Override
            public void onAgentComplete(String agentName, AgentOutput output) {
                tracer.addEvent("Agent completed", Map.of(
                    "agent", agentName,
                    "status", output.getStatus().toString(),
                    "tokens", output.getTotalTokens()
                ));
                tracer.endSpan();
            }

            @Override
            public void onHandoff(String from, String to) {
                tracer.addEvent("Handoff", Map.of(
                    "from", from,
                    "to", to
                ));
            }
        });

        // Execute and trace
        AgentGroupOutput output = group.execute("task");
    }
}
```

### 2. Debug Mode

```java
public class DebuggingExample {

    public AgentGroup createDebugGroup() {
        return AgentGroup.builder()
            .name("debug-team")
            .agents(createAgents())

            // Enable debug mode
            .debugMode(true)
            .debugOutput(System.out)

            // Set breakpoints
            .breakpoint(output ->
                output.getAgentName().equals("critical-agent")
            )

            // Step execution
            .stepExecution(true)

            .build();
    }
}
```

## Best Practices

1. **Specialized Roles**: Each agent should have clear responsibilities
2. **Clear Handoff Logic**: Use appropriate handoff strategies
3. **Shared Context Management**: Avoid context bloat
4. **Error Handling**: Each agent should handle errors gracefully
5. **Performance Monitoring**: Track execution time and resource usage
6. **Testing Strategy**: Test each agent individually, then test integration

## Summary

Through this tutorial, you learned:

1. How to create and configure agent groups
2. Different handoff strategies and their use cases
3. How to use planning strategies
4. Inter-agent communication mechanisms
5. Real-world application examples
6. Performance optimization and monitoring techniques

Next steps:
- Learn [RAG Integration](tutorial-rag.md) to enhance agent knowledge
- Explore [Tool Calling](tutorial-tool-calling.md) to extend agent capabilities
- Understand [Flow Orchestration](tutorial-flow.md) to build complex workflows
