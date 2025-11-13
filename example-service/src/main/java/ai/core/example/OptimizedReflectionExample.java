package ai.core.example;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.reflection.Reflection;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionHistory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 展示优化后的Reflection机制
 * Demonstrates optimized reflection mechanism
 *
 * @author xander
 */
public class OptimizedReflectionExample {

    private static final Logger logger = LoggerFactory.getLogger(OptimizedReflectionExample.class);

    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("优化后的Reflection机制示例 / Optimized Reflection Example");
        System.out.println("=".repeat(80) + "\n");

        // 环境配置
        String endpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String apiKey = System.getenv("AZURE_OPENAI_API_KEY");
        String deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT");

        if (endpoint == null || apiKey == null || deployment == null) {
            logger.error("Missing environment variables");
            return;
        }

        LLMProviderConfig config = new LLMProviderConfig(deployment, 0.7, null);
        LLMProvider llmProvider = new AzureOpenAIProvider(config, apiKey, endpoint);

        // 运行各种优化示例
        runScoreBasedExample(llmProvider);
        runNoImprovementExample(llmProvider);
        runMultiCriteriaExample(llmProvider);
        runStructuredEvaluationExample(llmProvider);
    }

    /**
     * 示例1：基于分数的终止
     */
    private static void runScoreBasedExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例1：基于分数的终止 / Score-based Termination");
        System.out.println("=".repeat(60) + "\n");

        // 定义评估标准
        String evaluationCriteria = """
            代码质量评估标准（满分10分）：
            - 正确性 (3分)：算法逻辑正确，能处理所有情况
            - 性能 (3分)：时间复杂度优化
            - 可读性 (2分)：代码清晰，有注释
            - 健壮性 (2分)：处理边界情况和异常
            """;

        // 使用结构化评估模板
        String structuredPrompt = """
            请评估解决方案并返回JSON格式的结果：
            {
              "score": <1-10的总分>,
              "pass": <true/false是否通过>,
              "dimensions": {
                "correctness": <1-3>,
                "performance": <1-3>,
                "readability": <1-2>,
                "robustness": <1-2>
              },
              "strengths": ["优点列表"],
              "weaknesses": ["缺点列表"],
              "suggestions": ["改进建议"],
              "improved_solution": "改进后的代码",
              "confidence": <0.0-1.0的置信度>
            }

            评估标准：
            {{evaluationCriteria}}

            任务：{{task}}
            当前方案：{{solution}}

            如果总分达到8分以上，在响应开头添加TERMINATE。
            """;

        // 创建带有多种终止条件的Agent
        Agent agent = Agent.builder()
                .name("score-based-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是代码质量专家，严格按照标准评分。")
                .reflectionConfig(new ReflectionConfig(
                    true, 5, 1, structuredPrompt, evaluationCriteria
                ))
                .build();

        // Note: ReflectionConfig automatically adds MaxRoundTermination and StopMessageTermination

        String task = "实现一个高效的二分查找算法";
        System.out.println("任务: " + task);
        System.out.println("目标: 达到8分以上\n");

        String result = agent.run(task, new HashMap<>());

        System.out.println("最终结果:");
        System.out.println(result);
        System.out.println("\n完成轮数: " + agent.getRound());
    }

    /**
     * 示例2：基于改进率的终止
     */
    private static void runNoImprovementExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例2：无改进时终止 / No-Improvement Termination");
        System.out.println("=".repeat(60) + "\n");

        String evaluationCriteria = """
            文章质量标准（每轮都要给出1-10分）：
            - 内容深度：是否有见解
            - 逻辑清晰：结构是否合理
            - 语言流畅：表达是否自然
            - 创新性：是否有新颖观点

            请在每次评估时明确给出分数，格式如: Score: 7/10
            """;

        Agent agent = Agent.builder()
                .name("improvement-tracking-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是写作专家，追求持续改进。")
                .reflectionConfig(new ReflectionConfig(
                    true, 10, 2, Reflection.DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE, evaluationCriteria
                ))
                .build();

        // Note: ReflectionConfig automatically adds MaxRoundTermination and StopMessageTermination

        String task = "写一篇关于AI对未来工作影响的短文（200字）";
        System.out.println("任务: " + task);
        System.out.println("终止条件: 连续2轮改进率<5%\n");

        String result = agent.run(task, new HashMap<>());

        System.out.println("最终结果:");
        System.out.println(result);
        System.out.println("\n完成轮数: " + agent.getRound());
    }

    /**
     * 示例3：多维度评估
     */
    private static void runMultiCriteriaExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例3：多维度评估 / Multi-Criteria Evaluation");
        System.out.println("=".repeat(60) + "\n");

        // 多维度评估标准
        String multiCriteria = """
            请从以下维度评估（每个维度1-10分）：

            技术维度：
            - 代码质量 (code_quality): 规范、可维护性
            - 性能优化 (performance): 时间/空间复杂度
            - 安全性 (security): 是否有安全漏洞

            业务维度：
            - 功能完整性 (functionality): 是否满足需求
            - 用户体验 (user_experience): 易用性
            - 可扩展性 (scalability): 未来扩展能力

            返回格式：
            {
              "score": <总分>,
              "dimensions": {
                "code_quality": <分数>,
                "performance": <分数>,
                "security": <分数>,
                "functionality": <分数>,
                "user_experience": <分数>,
                "scalability": <分数>
              },
              "analysis": "详细分析",
              "should_continue": <true/false>
            }
            """;

        Agent agent = Agent.builder()
                .name("multi-criteria-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是全栈技术专家，从多个维度评估方案。")
                .reflectionConfig(new ReflectionConfig(
                    true, 4, 1,
                    Reflection.DEFAULT_REFLECTION_WITH_CRITERIA_TEMPLATE,
                    multiCriteria
                ))
                .build();

        // Note: ReflectionConfig automatically adds MaxRoundTermination and StopMessageTermination

        String task = "设计一个用户登录系统的架构方案";
        System.out.println("任务: " + task);
        System.out.println("评估维度: 6个维度\n");

        Map<String, Object> variables = new HashMap<>();
        variables.put("weight_technical", 0.6);  // 技术维度权重60%
        variables.put("weight_business", 0.4);   // 业务维度权重40%

        String result = agent.run(task, variables);

        System.out.println("最终方案:");
        System.out.println(result);
        System.out.println("\n完成轮数: " + agent.getRound());
    }

    /**
     * 示例4：结构化评估与历史追踪
     */
    private static void runStructuredEvaluationExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例4：结构化评估与历史追踪 / Structured Evaluation");
        System.out.println("=".repeat(60) + "\n");

        // 使用更严格的JSON评估模板
        String jsonEvaluationTemplate = """
            你是严格的评估者。请按以下JSON格式返回评估结果。

            任务：{{task}}
            评估标准：{{evaluationCriteria}}
            当前方案：{{solution}}

            必须返回有效的JSON（不要包含其他文字）：
            {
              "score": <整数1-10>,
              "pass": <布尔值>,
              "strengths": [
                "优点1",
                "优点2"
              ],
              "weaknesses": [
                "缺点1",
                "缺点2"
              ],
              "suggestions": [
                "建议1",
                "建议2"
              ],
              "dimensions": {
                "completeness": <1-10>,
                "accuracy": <1-10>,
                "efficiency": <1-10>
              },
              "improved_solution": "改进后的完整方案",
              "confidence": <0.0-1.0>,
              "should_continue": <布尔值>
            }

            如果score >= 9或should_continue为false，在JSON后添加TERMINATE。
            """;

        String criteria = """
            算法实现标准：
            1. 完整性：覆盖所有用例
            2. 准确性：结果正确
            3. 效率：优化的时间复杂度
            目标：实现production-ready的代码
            """;

        Agent agent = Agent.builder()
                .name("structured-eval-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是算法专家，提供结构化的评估。")
                .reflectionConfig(new ReflectionConfig(
                    true, 6, 1, jsonEvaluationTemplate, criteria
                ))
                .build();

        // Note: ReflectionConfig automatically adds MaxRoundTermination and StopMessageTermination

        String task = "实现LRU缓存，支持get和put操作，时间复杂度O(1)";
        System.out.println("任务: " + task);
        System.out.println("目标: Score >= 9 或 改进停滞\n");

        // 模拟历史记录（实际使用时应集成到Agent中）
        ReflectionHistory history = new ReflectionHistory(
            agent.getId(), agent.getName(), task, criteria
        );

        String result = agent.run(task, new HashMap<>());

        // 标记完成
        history.complete(ReflectionHistory.ReflectionStatus.COMPLETED_SUCCESS);

        System.out.println("最终实现:");
        System.out.println(result);
        System.out.println("\n统计信息:");
        System.out.println("- 完成轮数: " + agent.getRound());
        System.out.println("- 状态: " + history.getStatus());

        // 如果有JSON评估结果，尝试解析
        try {
            if (result.contains("{") && result.contains("}")) {
                int start = result.indexOf("{");
                int end = result.lastIndexOf("}") + 1;
                String json = result.substring(start, end);
                System.out.println("\n解析的评估结果:");
                System.out.println(json);
            }
        } catch (Exception e) {
            logger.debug("Could not extract JSON evaluation");
        }
    }
}