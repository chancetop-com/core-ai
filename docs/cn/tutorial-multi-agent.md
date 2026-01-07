# 教程：多代理系统

本教程将介绍如何使用 Core-AI 构建多个代理协同工作的系统。

## 目录

1. [多代理系统概述](#多代理系统概述)
2. [代理组（AgentGroup）](#代理组agentgroup)
3. [切换策略（Handoff）](#切换策略handoff)
4. [规划策略（Planning）](#规划策略planning)
5. [代理间通信](#代理间通信)
6. [实战案例](#实战案例)

## 多代理系统概述

### 为什么需要多代理？

单个代理虽然强大，但在处理复杂任务时有局限性：
- 知识领域有限
- 上下文窗口限制
- 难以处理多步骤复杂任务

多代理系统通过专业分工和协作解决这些问题。

### Core-AI 的多代理架构

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

## 代理组（AgentGroup）

### 1. 创建基本代理组

```java
import ai.core.agent.Agent;
import ai.core.agent.AgentGroup;
import ai.core.agent.AgentGroupOutput;

public class BasicAgentGroupExample {

    public AgentGroup createBasicAgentGroup(LLMProvider llmProvider) {
        // 创建专门的代理
        Agent researchAgent = Agent.builder()
            .name("researcher")
            .description("负责研究和收集信息")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个研究专家，擅长搜索和分析信息")
            .build();

        Agent writerAgent = Agent.builder()
            .name("writer")
            .description("负责编写和整理内容")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个专业写作者，擅长组织和表达信息")
            .build();

        Agent reviewerAgent = Agent.builder()
            .name("reviewer")
            .description("负责审核和改进内容")
            .llmProvider(llmProvider)
            .systemPrompt("你是一个严格的审核者，确保内容质量")
            .build();

        // 创建代理组
        return AgentGroup.builder()
            .name("content-creation-team")
            .description("内容创作团队")
            .agents(List.of(researchAgent, writerAgent, reviewerAgent))
            .llmProvider(llmProvider)  // 用于协调
            .build();
    }

    public void useAgentGroup() {
        AgentGroup group = createBasicAgentGroup(llmProvider);

        // 执行任务
        String task = "写一篇关于人工智能在医疗领域应用的文章";
        AgentGroupOutput output = group.execute(task);

        // 查看结果
        System.out.println("最终输出: " + output.getFinalOutput());

        // 查看各代理的贡献
        for (AgentOutput agentOutput : output.getAgentOutputs()) {
            System.out.println(agentOutput.getAgentName() + ": " +
                             agentOutput.getOutput());
        }
    }
}
```

### 2. 配置代理组参数

```java
public class ConfiguredAgentGroupExample {

    public AgentGroup createConfiguredAgentGroup() {
        List<Agent> agents = createSpecializedAgents();

        return AgentGroup.builder()
            .name("advanced-team")
            .agents(agents)
            .llmProvider(llmProvider)

            // 执行配置
            .maxRounds(5)               // 最大协作轮数
            .maxAgentsPerRound(2)       // 每轮最多激活代理数
            .parallelExecution(true)    // 并行执行

            // 终止条件
            .terminationCondition(output -> {
                // 自定义终止条件
                return output.getFinalOutput().contains("DONE") ||
                       output.getRounds() >= 3;
            })

            // 选择策略
            .agentSelectionStrategy(new PriorityBasedSelection())

            .build();
    }
}
```

## 切换策略（Handoff）

### Handoff 机制原理

Handoff 是 Core-AI 多代理协作的核心机制，负责决定"下一步由哪个代理执行"。

```
┌─────────────────────────────────────────────────────────────────┐
│                    Handoff 机制架构                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  AgentGroup.execute()                                           │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────────────────────────────────────────┐           │
│  │           Handoff 策略执行                        │           │
│  │                                                 │           │
│  │  handoff.handoff(agentGroup, planning, vars)    │           │
│  │       │                                         │           │
│  │       ├─ DirectHandoff: 顺序选择下一个 Agent     │           │
│  │       │   └─ getNextAgentNameOf(agents, current)│           │
│  │       │                                         │           │
│  │       ├─ AutoHandoff: Moderator Agent 决策      │           │
│  │       │   └─ moderator.run(query) → JSON result│           │
│  │       │                                         │           │
│  │       └─ HybridHandoff: 混合策略                 │           │
│  │           └─ 先检查固定路由，否则自动决策         │           │
│  │                                                 │           │
│  └─────────────────────────────────────────────────┘           │
│       │                                                         │
│       ▼                                                         │
│  Planning 结果                                                  │
│  ├─ nextAgentName: 下一个执行的 Agent                           │
│  ├─ nextQuery: 传递的查询/上下文                                │
│  └─ nextAction: 动作（可能是终止词）                            │
│       │                                                         │
│       ▼                                                         │
│  currentAgent = getAgentByName(planning.nextAgentName())        │
│  output = currentAgent.run(planning.nextQuery())                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Handoff 接口定义**：

```java
public interface Handoff {
    /**
     * 执行切换策略，决定下一步的执行计划
     * @param agentGroup 当前代理组
     * @param planning 规划结果容器
     * @param variables 执行变量
     */
    void handoff(AgentGroup agentGroup, Planning planning, Map<String, Object> variables);

    /**
     * 获取切换类型
     */
    HandoffType getType();
}
```

**Planning 结果结构**：

```java
public class DefaultPlanningResult {
    String name;      // 下一个 Agent 名称
    String query;     // 传递给下一个 Agent 的查询
    String planning;  // 规划说明
    String nextStep;  // 下一步动作（可能是终止词 "DONE"）
}
```

### 1. 直接切换（DirectHandoff）

```java
import ai.core.agent.handoff.DirectHandoff;
import ai.core.agent.handoff.HandoffMessage;

public class DirectHandoffExample {

    public AgentGroup createDirectHandoffGroup() {
        // 定义切换规则
        Map<String, List<String>> handoffRules = Map.of(
            "analyzer", List.of("planner"),       // analyzer → planner
            "planner", List.of("executor"),       // planner → executor
            "executor", List.of("reporter")       // executor → reporter
        );

        DirectHandoff handoff = new DirectHandoff(handoffRules);

        // 创建代理，明确指定切换目标
        Agent analyzer = Agent.builder()
            .name("analyzer")
            .description("分析需求")
            .systemPrompt("""
                分析用户需求。
                完成后，使用 HANDOFF: planner 切换到规划代理。
                """)
            .build();

        Agent planner = Agent.builder()
            .name("planner")
            .description("制定计划")
            .systemPrompt("""
                基于分析结果制定执行计划。
                完成后，使用 HANDOFF: executor 切换到执行代理。
                """)
            .build();

        Agent executor = Agent.builder()
            .name("executor")
            .description("执行任务")
            .systemPrompt("""
                按计划执行任务。
                完成后，使用 HANDOFF: reporter 切换到报告代理。
                """)
            .build();

        Agent reporter = Agent.builder()
            .name("reporter")
            .description("生成报告")
            .systemPrompt("生成执行报告，标记 DONE 表示完成。")
            .build();

        return AgentGroup.builder()
            .name("workflow-team")
            .agents(List.of(analyzer, planner, executor, reporter))
            .handoffStrategy(handoff)
            .startAgent("analyzer")  // 指定起始代理
            .build();
    }
}
```

### 2. 自动切换（AutoHandoff）

```java
import ai.core.agent.handoff.AutoHandoff;

public class AutoHandoffExample {

    public AgentGroup createAutoHandoffGroup() {
        // 自动切换：系统自动决定下一个代理
        AutoHandoff handoff = new AutoHandoff();

        // 创建具有明确职责的代理
        List<Agent> agents = List.of(
            Agent.builder()
                .name("requirement-analyst")
                .description("需求分析专家，擅长理解和细化用户需求")
                .capabilities(List.of("需求分析", "用例设计"))
                .build(),

            Agent.builder()
                .name("architect")
                .description("系统架构师，设计技术方案")
                .capabilities(List.of("架构设计", "技术选型"))
                .build(),

            Agent.builder()
                .name("developer")
                .description("开发工程师，实现具体功能")
                .capabilities(List.of("编码", "测试"))
                .build(),

            Agent.builder()
                .name("qa-engineer")
                .description("质量保证工程师，确保代码质量")
                .capabilities(List.of("测试", "代码审查"))
                .build()
        );

        return AgentGroup.builder()
            .name("auto-handoff-team")
            .agents(agents)
            .handoffStrategy(handoff)
            .llmProvider(llmProvider)  // 用于自动决策
            .selectionPrompt("""
                基于当前任务状态和需求，选择最合适的代理：

                当前已完成：{{completed_tasks}}
                待处理：{{pending_tasks}}

                可用代理及其能力：
                {{#agents}}
                - {{name}}: {{description}}
                  能力: {{capabilities}}
                {{/agents}}

                选择下一个代理并说明理由。
                """)
            .build();
    }
}
```

### 3. 混合切换（HybridAutoDirectHandoff）

```java
import ai.core.agent.handoff.HybridAutoDirectHandoff;

public class HybridHandoffExample {

    public AgentGroup createHybridHandoffGroup() {
        // 混合策略：结合自动和直接切换
        Map<String, List<String>> fixedRoutes = Map.of(
            "validator", List.of("FINISH")  // validator 总是结束流程
        );

        HybridAutoDirectHandoff handoff =
            new HybridAutoDirectHandoff(fixedRoutes);

        List<Agent> agents = List.of(
            Agent.builder()
                .name("coordinator")
                .description("协调员，分配任务")
                .systemPrompt("""
                    你是协调员。分析任务并决定：
                    - 简单任务：直接处理
                    - 复杂任务：分配给专家
                    - 需要验证：HANDOFF: validator
                    """)
                .build(),

            Agent.builder()
                .name("expert-1")
                .description("领域专家1")
                .build(),

            Agent.builder()
                .name("expert-2")
                .description("领域专家2")
                .build(),

            Agent.builder()
                .name("validator")
                .description("验证专家")
                .systemPrompt("验证结果，确保质量。完成后结束流程。")
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

## 规划策略（Planning）

### 1. 顺序规划

```java
import ai.core.agent.planning.SequentialPlanning;
import ai.core.agent.planning.TaskPlan;

public class SequentialPlanningExample {

    public AgentGroup createSequentialPlanningGroup() {
        // 顺序执行计划
        SequentialPlanning planning = new SequentialPlanning();

        return AgentGroup.builder()
            .name("sequential-team")
            .agents(createAgents())
            .planningStrategy(planning)
            .planningPrompt("""
                将任务分解为顺序步骤：

                任务：{{task}}

                生成步骤计划，每步指定：
                1. 步骤描述
                2. 负责的代理
                3. 预期输出

                格式：
                STEP 1: [描述] - AGENT: [代理名] - OUTPUT: [预期结果]
                """)
            .build();
    }

    public void executeWithPlan() {
        AgentGroup group = createSequentialPlanningGroup();

        // 执行带规划的任务
        String task = "创建一个用户管理系统";
        AgentGroupOutput output = group.executeWithPlanning(task);

        // 查看执行计划
        TaskPlan plan = output.getExecutionPlan();
        for (TaskStep step : plan.getSteps()) {
            System.out.println("步骤 " + step.getOrder() + ": " +
                             step.getDescription());
            System.out.println("  代理: " + step.getAssignedAgent());
            System.out.println("  状态: " + step.getStatus());
        }
    }
}
```

### 2. 并行规划

```java
import ai.core.agent.planning.ParallelPlanning;

public class ParallelPlanningExample {

    public AgentGroup createParallelPlanningGroup() {
        ParallelPlanning planning = new ParallelPlanning();

        return AgentGroup.builder()
            .name("parallel-team")
            .agents(createSpecializedAgents())
            .planningStrategy(planning)
            .maxParallelTasks(3)  // 最多并行3个任务
            .planningPrompt("""
                分析任务并识别可并行执行的部分：

                任务：{{task}}

                生成并行执行计划：
                PARALLEL_GROUP 1:
                  - TASK: [任务1] - AGENT: [代理A]
                  - TASK: [任务2] - AGENT: [代理B]

                SEQUENTIAL:
                  - TASK: [任务3] - AGENT: [代理C] - DEPENDS_ON: GROUP_1
                """)
            .build();
    }
}
```

### 3. 自适应规划

```java
import ai.core.agent.planning.AdaptivePlanning;

public class AdaptivePlanningExample {

    public AgentGroup createAdaptivePlanningGroup() {
        // 自适应规划：根据执行情况动态调整
        AdaptivePlanning planning = new AdaptivePlanning();

        return AgentGroup.builder()
            .name("adaptive-team")
            .agents(createAgents())
            .planningStrategy(planning)

            // 重规划条件
            .replanCondition(output -> {
                // 当遇到错误或偏离预期时重新规划
                return output.hasErrors() ||
                       output.getCompletionRate() < 0.5;
            })

            // 规划评估
            .planEvaluator(plan -> {
                // 评估计划质量
                double score = 0;
                score += plan.getSteps().size() <= 10 ? 1 : 0.5;
                score += plan.getParallelizationRate() * 0.5;
                return score;
            })

            .build();
    }
}
```

## 代理间通信

### 1. 消息传递

```java
import ai.core.a2a.AgentMessage;
import ai.core.a2a.MessageBus;

public class AgentCommunicationExample {

    private final MessageBus messageBus = new MessageBus();

    public AgentGroup createCommunicatingGroup() {
        // 创建可以相互通信的代理
        Agent dataCollector = Agent.builder()
            .name("data-collector")
            .systemPrompt("""
                收集数据。
                完成后发送消息给分析器：
                MESSAGE_TO: analyzer
                DATA: {{collected_data}}
                """)
            .messageHandler((message) -> {
                // 处理接收到的消息
                System.out.println("收到消息: " + message);
            })
            .build();

        Agent analyzer = Agent.builder()
            .name("analyzer")
            .systemPrompt("""
                分析接收到的数据。
                需要更多数据时：
                MESSAGE_TO: data-collector
                REQUEST: {{data_type}}
                """)
            .messageHandler((message) -> {
                if (message.getType().equals("DATA")) {
                    // 处理数据
                    return analyzeData(message.getContent());
                }
                return null;
            })
            .build();

        // 注册到消息总线
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

### 2. 共享上下文

```java
import ai.core.agent.SharedContext;

public class SharedContextExample {

    public AgentGroup createContextSharingGroup() {
        // 创建共享上下文
        SharedContext sharedContext = new SharedContext();

        Agent researcher = Agent.builder()
            .name("researcher")
            .sharedContext(sharedContext)
            .systemPrompt("""
                研究信息并更新共享上下文：
                CONTEXT_UPDATE: research_findings = {{findings}}
                """)
            .build();

        Agent writer = Agent.builder()
            .name("writer")
            .sharedContext(sharedContext)
            .systemPrompt("""
                使用共享上下文中的研究成果写作：
                可用上下文：{{shared_context}}
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

        // 第一个代理更新上下文
        group.execute("研究量子计算的最新进展");

        // 查看共享上下文
        SharedContext context = group.getSharedContext();
        System.out.println("共享的研究成果: " +
                         context.get("research_findings"));

        // 第二个代理使用上下文
        AgentGroupOutput output = group.execute("基于研究写一篇文章");
        System.out.println("文章: " + output.getFinalOutput());
    }
}
```

### 3. 协作协议

```java
public class CollaborationProtocolExample {

    public AgentGroup createProtocolBasedGroup() {
        // 定义协作协议
        CollaborationProtocol protocol = CollaborationProtocol.builder()
            .name("code-review-protocol")

            // 定义角色
            .role("author", "代码作者")
            .role("reviewer", "代码审查者")
            .role("approver", "审批者")

            // 定义流程
            .step(1, "author", "提交代码")
            .step(2, "reviewer", "审查代码", parallel: true)
            .step(3, "author", "修改代码", conditional: "hasIssues")
            .step(4, "approver", "最终审批")

            // 定义消息格式
            .messageFormat("REVIEW", Map.of(
                "code", "String",
                "comments", "List<String>",
                "approved", "Boolean"
            ))

            .build();

        // 创建遵循协议的代理
        List<Agent> agents = createProtocolAgents(protocol);

        return AgentGroup.builder()
            .name("protocol-team")
            .agents(agents)
            .collaborationProtocol(protocol)
            .protocolEnforcement(true)  // 强制遵循协议
            .build();
    }
}
```

## 实战案例

### 案例1：客户支持系统

```java
public class CustomerSupportSystemExample {

    public AgentGroup createCustomerSupportTeam() {
        // 接待代理
        Agent receptionist = Agent.builder()
            .name("receptionist")
            .description("接待客户，理解需求")
            .systemPrompt("""
                你是客服接待员。
                职责：
                1. 友好地接待客户
                2. 理解客户问题
                3. 分类问题（技术/账单/一般咨询）
                4. 转接给合适的专家
                """)
            .tools(List.of(
                new CustomerInfoLookupTool(),
                new TicketCreationTool()
            ))
            .build();

        // 技术支持代理
        Agent techSupport = Agent.builder()
            .name("tech-support")
            .description("解决技术问题")
            .systemPrompt("""
                你是技术支持专家。
                擅长：
                - 故障排除
                - 配置指导
                - 性能优化
                使用知识库解决问题。
                """)
            .enableRAG(true)
            .ragConfig(techKnowledgeBase())
            .build();

        // 账单专家
        Agent billingExpert = Agent.builder()
            .name("billing-expert")
            .description("处理账单和支付问题")
            .systemPrompt("你是账单专家...")
            .tools(List.of(
                new BillingSystemTool(),
                new RefundProcessingTool()
            ))
            .build();

        // 质量保证代理
        Agent qualityAssurance = Agent.builder()
            .name("qa-agent")
            .description("确保服务质量")
            .systemPrompt("""
                评估对话质量：
                - 问题是否解决
                - 客户是否满意
                - 需要跟进吗
                """)
            .build();

        // 创建支持团队
        return AgentGroup.builder()
            .name("customer-support-team")
            .agents(List.of(
                receptionist,
                techSupport,
                billingExpert,
                qualityAssurance
            ))

            // 使用自动切换
            .handoffStrategy(new AutoHandoff())

            // 设置起始代理
            .startAgent("receptionist")

            // 终止条件
            .terminationCondition(output ->
                output.getFinalOutput().contains("RESOLVED") ||
                output.getFinalOutput().contains("ESCALATED")
            )

            .build();
    }

    public void handleCustomerQuery() {
        AgentGroup supportTeam = createCustomerSupportTeam();

        String customerQuery = "我的应用无法连接到数据库，账单也有问题";

        AgentGroupOutput result = supportTeam.execute(customerQuery);

        // 查看处理流程
        System.out.println("处理流程:");
        for (AgentOutput step : result.getAgentOutputs()) {
            System.out.println(step.getAgentName() + ": " +
                             step.getOutput().substring(0, 100) + "...");
        }

        System.out.println("\n最终解决方案: " + result.getFinalOutput());
    }
}
```

### 案例2：研究助手团队

```java
public class ResearchAssistantTeamExample {

    public AgentGroup createResearchTeam() {
        // 研究规划师
        Agent planner = Agent.builder()
            .name("research-planner")
            .description("制定研究计划")
            .systemPrompt("""
                制定详细的研究计划：
                1. 确定研究问题
                2. 列出信息来源
                3. 分配研究任务
                4. 设定时间表
                """)
            .build();

        // 文献研究员
        Agent literatureResearcher = Agent.builder()
            .name("literature-researcher")
            .description("搜索和分析学术文献")
            .tools(List.of(
                new ArxivSearchTool(),
                new PubMedSearchTool(),
                new GoogleScholarTool()
            ))
            .build();

        // 数据分析师
        Agent dataAnalyst = Agent.builder()
            .name("data-analyst")
            .description("分析研究数据")
            .systemPrompt("分析数据，识别模式和趋势")
            .tools(List.of(
                new StatisticalAnalysisTool(),
                new DataVisualizationTool()
            ))
            .build();

        // 报告撰写者
        Agent reportWriter = Agent.builder()
            .name("report-writer")
            .description("撰写研究报告")
            .systemPrompt("""
                撰写结构化的研究报告：
                - 执行摘要
                - 研究方法
                - 发现
                - 结论
                - 参考文献
                """)
            .build();

        // 同行评审员
        Agent peerReviewer = Agent.builder()
            .name("peer-reviewer")
            .description("评审研究质量")
            .systemPrompt("""
                严格评审研究质量：
                - 方法论的严谨性
                - 数据的可靠性
                - 结论的合理性
                - 引用的准确性
                """)
            .build();

        // 创建研究团队
        return AgentGroup.builder()
            .name("research-team")
            .agents(List.of(
                planner,
                literatureResearcher,
                dataAnalyst,
                reportWriter,
                peerReviewer
            ))

            // 使用混合切换策略
            .handoffStrategy(new HybridAutoDirectHandoff(Map.of(
                "report-writer", List.of("peer-reviewer"),
                "peer-reviewer", List.of("report-writer", "FINISH")
            )))

            // 并行执行文献和数据研究
            .parallelExecution(true)
            .maxParallelTasks(2)

            // 共享研究上下文
            .sharedContext(new SharedContext())

            .build();
    }

    public void conductResearch(String topic) {
        AgentGroup researchTeam = createResearchTeam();

        // 执行研究
        AgentGroupOutput result = researchTeam.executeWithPlanning(
            "研究主题：" + topic +
            "\n要求：全面的文献综述，数据分析，和详细报告"
        );

        // 保存研究结果
        saveResearchResults(result);

        // 生成引用
        generateCitations(result);
    }
}
```

### 案例3：软件开发团队

```java
public class SoftwareDevelopmentTeamExample {

    public AgentGroup createDevTeam() {
        // 产品经理
        Agent productManager = Agent.builder()
            .name("product-manager")
            .description("定义需求和用户故事")
            .systemPrompt("将业务需求转换为清晰的用户故事和验收标准")
            .build();

        // 架构师
        Agent architect = Agent.builder()
            .name("architect")
            .description("设计系统架构")
            .systemPrompt("设计可扩展、可维护的系统架构")
            .tools(List.of(new DiagramGenerationTool()))
            .build();

        // 前端开发
        Agent frontendDev = Agent.builder()
            .name("frontend-developer")
            .description("开发用户界面")
            .systemPrompt("使用React开发响应式用户界面")
            .tools(List.of(new CodeGenerationTool("javascript")))
            .build();

        // 后端开发
        Agent backendDev = Agent.builder()
            .name("backend-developer")
            .description("开发API和业务逻辑")
            .systemPrompt("使用Java Spring Boot开发RESTful API")
            .tools(List.of(new CodeGenerationTool("java")))
            .build();

        // 测试工程师
        Agent tester = Agent.builder()
            .name("test-engineer")
            .description("编写和执行测试")
            .systemPrompt("编写全面的单元测试和集成测试")
            .tools(List.of(
                new TestGenerationTool(),
                new TestExecutionTool()
            ))
            .build();

        // DevOps工程师
        Agent devops = Agent.builder()
            .name("devops-engineer")
            .description("处理部署和基础设施")
            .systemPrompt("配置CI/CD和容器化部署")
            .tools(List.of(
                new DockerTool(),
                new KubernetesTool(),
                new JenkinsTool()
            ))
            .build();

        // 创建开发团队
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

            // 使用基于能力的自动切换
            .handoffStrategy(new CapabilityBasedHandoff())

            // 启用并行开发
            .parallelExecution(true)
            .parallelizationStrategy(task -> {
                // 前端和后端可以并行开发
                if (task.contains("frontend") || task.contains("backend")) {
                    return true;
                }
                return false;
            })

            // 使用看板方法跟踪进度
            .progressTracker(new KanbanProgressTracker())

            .build();
    }

    public void developFeature(String featureDescription) {
        AgentGroup devTeam = createDevTeam();

        // 创建开发计划
        DevelopmentPlan plan = createDevelopmentPlan(featureDescription);

        // 执行开发
        AgentGroupOutput result = devTeam.executeWithPlan(plan);

        // 代码审查
        performCodeReview(result);

        // 部署到测试环境
        deployToStaging(result);
    }
}
```

## 性能优化

### 1. 并行执行优化

```java
public class ParallelOptimizationExample {

    public AgentGroup createOptimizedParallelGroup() {
        return AgentGroup.builder()
            .name("optimized-team")
            .agents(createAgents())

            // 线程池配置
            .executorService(Executors.newFixedThreadPool(10))

            // 批处理配置
            .batchSize(5)
            .batchTimeout(Duration.ofSeconds(30))

            // 负载均衡
            .loadBalancer(new RoundRobinLoadBalancer())

            // 资源限制
            .maxConcurrentAgents(5)
            .maxMemoryPerAgent(512 * 1024 * 1024) // 512MB

            .build();
    }
}
```

### 2. 缓存策略

```java
public class CachingStrategyExample {

    public AgentGroup createCachedAgentGroup() {
        // 创建缓存配置
        CacheConfig cacheConfig = CacheConfig.builder()
            .maxSize(1000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .recordStats(true)
            .build();

        return AgentGroup.builder()
            .name("cached-team")
            .agents(createAgents())

            // 启用响应缓存
            .enableResponseCache(true)
            .cacheConfig(cacheConfig)

            // 缓存键生成策略
            .cacheKeyGenerator((agent, input) ->
                agent.getName() + ":" + input.hashCode()
            )

            .build();
    }
}
```

## 监控和调试

### 1. 执行追踪

```java
public class ExecutionTracingExample {

    @Inject
    private AgentTracer tracer;

    public void traceGroupExecution() {
        AgentGroup group = createTracedAgentGroup();

        // 添加执行监听器
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

        // 执行并追踪
        AgentGroupOutput output = group.execute("task");
    }
}
```

### 2. 调试模式

```java
public class DebuggingExample {

    public AgentGroup createDebugGroup() {
        return AgentGroup.builder()
            .name("debug-team")
            .agents(createAgents())

            // 启用调试模式
            .debugMode(true)
            .debugOutput(System.out)

            // 断点设置
            .breakpoint(output ->
                output.getAgentName().equals("critical-agent")
            )

            // 步进执行
            .stepExecution(true)

            .build();
    }
}
```

## 最佳实践

1. **专业化分工**：每个代理应该有明确的职责
2. **清晰的切换逻辑**：使用合适的切换策略
3. **共享上下文管理**：避免上下文膨胀
4. **错误处理**：每个代理都应该能够优雅地处理错误
5. **性能监控**：追踪执行时间和资源使用
6. **测试策略**：单独测试每个代理，然后测试集成

## 总结

通过本教程，您学习了：

1. ✅ 如何创建和配置代理组
2. ✅ 不同的切换策略及其应用场景
3. ✅ 规划策略的使用方法
4. ✅ 代理间的通信机制
5. ✅ 实际应用案例
6. ✅ 性能优化和监控技巧

下一步，您可以：
- 学习 [RAG 集成](tutorial-rag.md) 增强代理知识
- 探索 [工具调用](tutorial-tool-calling.md) 扩展代理能力
- 了解 [流程编排](tutorial-flow.md) 构建复杂工作流