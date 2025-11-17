package ai.core.reflection;

import ai.core.IntegrationTest;
import ai.core.agent.Agent;
import ai.core.agent.ExecutionContext;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviders;
import core.framework.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reflection mechanism test.
 * Demonstrates different ways to use reflection with agents.
 *
 * @author stephen
 * @author xander
 */
@org.junit.jupiter.api.Disabled("Requires valid Azure OpenAI endpoint configuration")
class RefTest extends IntegrationTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(RefTest.class);

    /**
     * 示例1: 代码质量反思 - 使用具体的编码标准
     * Example 1: Code Quality Reflection - Using specific coding standards
     */
    private static void runCodeQualityExample(LLMProvider llmProvider) {
        LOGGER.info("\n" + "=".repeat(60));
        LOGGER.info("示例1: 代码质量反思 / Code Quality Reflection");
        LOGGER.info("=".repeat(60) + "\n");

        // 定义代码质量标准
        String codeStandards = """
            代码必须满足以下标准:
            1. 性能: 时间复杂度必须是O(n)或更好
            2. 可读性: 使用描述性的变量名和注释
            3. 错误处理: 必须处理空值和边界情况
            4. 最佳实践: 遵循Java命名规范
            """;

        // 创建带有评估标准的Agent
        Agent codeAgent = Agent.builder()
                .name("code-quality-checker")
                .llmProvider(llmProvider)
                .systemPrompt("你是一个Java专家，编写高质量的代码。")
                .reflectionEvaluationCriteria(codeStandards)  // 设置评估标准
                .build();

        // 运行任务
        String task = "编写一个Java方法，找出数组中的最大值";
        LOGGER.info("任务 / Task: {}", task);
        LOGGER.info("\n评估标准 / Evaluation Criteria:");
        LOGGER.info(codeStandards);

        String result = codeAgent.run(task, ExecutionContext.builder().build());

        LOGGER.info("\n最终结果 / Final Result:");
        LOGGER.info(result);
        LOGGER.info("\n反思轮数 / Reflection Rounds: {}", codeAgent.getRound());
    }

    /**
     * 示例2: 简单反思 - 不使用评估标准（向后兼容）
     * Example 2: Simple Reflection - Without evaluation criteria (backward compatible)
     */
    static void runSimpleReflectionExample(LLMProvider llmProvider) {
        LOGGER.info("\n" + "=".repeat(60));
        LOGGER.info("示例2: 简单反思 / Simple Reflection");
        LOGGER.info("=".repeat(60) + "\n");

        // 使用传统方式启用反思
        Agent simpleAgent = Agent.builder()
                .name("simple-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是一个有帮助的助手。")
                .enableReflection(true)  // 简单启用反思，不设置标准
                .build();

        String task = "解释什么是递归，并给出一个简单的例子";
        LOGGER.info("任务 / Task: {}", task);

        String result = simpleAgent.run(task, ExecutionContext.builder().build());

        LOGGER.info("\n最终结果 / Final Result:");
        LOGGER.info(result);
        LOGGER.info("\n反思轮数 / Reflection Rounds: {}", simpleAgent.getRound());
    }

    /**
     * 示例3: 写作优化 - 使用写作质量标准
     * Example 3: Writing Optimization - Using writing quality standards
     */
    static void runWritingExample(LLMProvider llmProvider) {
        LOGGER.info("\n" + "=".repeat(60));
        LOGGER.info("示例3: 写作优化 / Writing Optimization");
        LOGGER.info("=".repeat(60) + "\n");

        // 定义写作质量标准
        String writingStandards = """
            文章必须满足以下标准:
            1. 清晰度: 观点明确，逻辑清晰
            2. 结构: 有引言、主体和结论
            3. 吸引力: 开头要吸引读者
            4. 长度: 150-200字
            5. 语言: 使用简洁、专业的语言
            """;

        // 创建写作Agent
        Agent writingAgent = Agent.builder()
                .name("writing-optimizer")
                .llmProvider(llmProvider)
                .systemPrompt("你是一个专业的写作专家。")
                .reflectionEvaluationCriteria(writingStandards)
                .build();

        String task = "写一段关于人工智能对教育影响的短文";
        LOGGER.info("任务 / Task: {}", task);
        LOGGER.info("\n写作标准 / Writing Standards:");
        LOGGER.info(writingStandards);

        String result = writingAgent.run(task, ExecutionContext.builder().build());

        LOGGER.info("\n最终文章 / Final Article:");
        LOGGER.info(result);
        LOGGER.info("\n反思轮数 / Reflection Rounds: {}", writingAgent.getRound());
    }

    @Inject
    LLMProviders llmProviders;

    @Test
    void test() {
        var llmProvider = llmProviders.getProvider();

        // 示例1: 使用评估标准的代码质量检查
        runCodeQualityExample(llmProvider);

        // 示例2: 不使用评估标准的简单反思
//        runSimpleReflectionExample(llmProvider);

        // 示例3: 使用评估标准的写作优化
//        runWritingExample(llmProvider);
    }
}
