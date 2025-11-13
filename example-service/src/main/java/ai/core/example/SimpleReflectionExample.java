package ai.core.example;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.AzureOpenAIProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;

/**
 * 简单的Reflection示例 - 展示如何使用带有业务标准的反思机制
 * Simple Reflection Example - Demonstrates reflection with business standards
 *
 * @author Xander
 */
public class SimpleReflectionExample {

    private static final Logger logger = LoggerFactory.getLogger(SimpleReflectionExample.class);

    public static void main(String[] args) {
        System.out.println("=".repeat(60));
        System.out.println("简单的Reflection示例 / Simple Reflection Example");
        System.out.println("=".repeat(60) + "\n");

        // 从环境变量获取配置
        String endpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String apiKey = System.getenv("AZURE_OPENAI_API_KEY");
        String deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT");

        // 检查环境变量
        if (endpoint == null || apiKey == null || deployment == null) {
            logger.error("请设置环境变量: AZURE_OPENAI_ENDPOINT, AZURE_OPENAI_API_KEY, AZURE_OPENAI_DEPLOYMENT");
            System.out.println("\n使用示例 / Usage example:");
            System.out.println("export AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/");
            System.out.println("export AZURE_OPENAI_API_KEY=your-api-key");
            System.out.println("export AZURE_OPENAI_DEPLOYMENT=your-deployment-name");
            return;
        }

        // 创建LLM Provider
        LLMProviderConfig config = new LLMProviderConfig(deployment, 0.7, null);
        LLMProvider llmProvider = new AzureOpenAIProvider(config, apiKey, endpoint);

        // 示例1: 使用评估标准的代码质量检查
        runCodeQualityExample(llmProvider);

        // 示例2: 不使用评估标准的简单反思
        runSimpleReflectionExample(llmProvider);

        // 示例3: 使用评估标准的写作优化
        runWritingExample(llmProvider);
    }

    /**
     * 示例1: 代码质量反思 - 使用具体的编码标准
     */
    private static void runCodeQualityExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例1: 代码质量反思 / Code Quality Reflection");
        System.out.println("=".repeat(60) + "\n");

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
        System.out.println("任务 / Task: " + task);
        System.out.println("\n评估标准 / Evaluation Criteria:");
        System.out.println(codeStandards);

        String result = codeAgent.run(task, new HashMap<>());

        System.out.println("\n最终结果 / Final Result:");
        System.out.println(result);
        System.out.println("\n反思轮数 / Reflection Rounds: " + codeAgent.getRound());
    }

    /**
     * 示例2: 简单反思 - 不使用评估标准（向后兼容）
     */
    private static void runSimpleReflectionExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例2: 简单反思 / Simple Reflection");
        System.out.println("=".repeat(60) + "\n");

        // 使用传统方式启用反思
        Agent simpleAgent = Agent.builder()
                .name("simple-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是一个有帮助的助手。")
                .enableReflection(true)  // 简单启用反思，不设置标准
                .build();

        String task = "解释什么是递归，并给出一个简单的例子";
        System.out.println("任务 / Task: " + task);

        String result = simpleAgent.run(task, new HashMap<>());

        System.out.println("\n最终结果 / Final Result:");
        System.out.println(result);
        System.out.println("\n反思轮数 / Reflection Rounds: " + simpleAgent.getRound());
    }

    /**
     * 示例3: 写作优化 - 使用写作质量标准
     */
    private static void runWritingExample(LLMProvider llmProvider) {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("示例3: 写作优化 / Writing Optimization");
        System.out.println("=".repeat(60) + "\n");

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
        System.out.println("任务 / Task: " + task);
        System.out.println("\n写作标准 / Writing Standards:");
        System.out.println(writingStandards);

        String result = writingAgent.run(task, new HashMap<>());

        System.out.println("\n最终文章 / Final Article:");
        System.out.println(result);
        System.out.println("\n反思轮数 / Reflection Rounds: " + writingAgent.getRound());
    }
}