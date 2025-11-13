package ai.core.example;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.reflection.Reflection;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionEvaluation;
import ai.core.reflection.ReflectionHistory;
import ai.core.reflection.ReflectionListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 展示如何将评估历史传递给Agent，让它基于历史记忆进行改进
 * Shows how to pass evaluation history to agent for memory-based improvement
 *
 * @author xander
 */
public class ReflectionWithMemoryExample {

    private static final Logger logger = LoggerFactory.getLogger(ReflectionWithMemoryExample.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 带历史记忆的Reflection模板
     */
    private static final String REFLECTION_WITH_HISTORY_TEMPLATE = """
        你是一个专业的评估者。请基于之前的评估历史进行改进。

        任务：{{task}}

        评估标准：
        {{evaluationCriteria}}

        {{#previousEvaluations}}
        === 历史评估记录 ===
        {{#evaluations}}
        轮次 {{round}}:
        - 得分: {{score}}/10
        - 优点: {{strengths}}
        - 缺点: {{weaknesses}}
        - 改进建议: {{suggestions}}
        {{/evaluations}}
        === 历史记录结束 ===

        基于以上历史，你需要：
        1. 避免重复之前的缺点
        2. 保持并加强之前的优点
        3. 实施之前提出的改进建议
        {{/previousEvaluations}}

        当前方案：
        {{solution}}

        请提供改进后的方案，并给出JSON格式的评估：
        {
          "score": <1-10>,
          "strengths": ["优点列表"],
          "weaknesses": ["缺点列表"],
          "suggestions": ["下一步改进建议"],
          "improved_solution": "改进后的完整方案"
        }

        如果score >= 9，在响应开头添加TERMINATE。
        """;

    /**
     * 自定义的Agent包装器，支持历史记忆
     */
    public static class MemoryAwareAgent {
        private final Agent agent;
        private final List<ReflectionEvaluation> evaluationHistory;
        private final ReflectionHistory reflectionHistory;
        private final List<ReflectionListener> listeners;

        public MemoryAwareAgent(Agent agent, String task, String evaluationCriteria) {
            this.agent = agent;
            this.evaluationHistory = new ArrayList<>();
            this.reflectionHistory = new ReflectionHistory(
                agent.getId(), agent.getName(), task, evaluationCriteria
            );
            this.listeners = new ArrayList<>();
        }

        /**
         * 添加监听器
         */
        public void addListener(ReflectionListener listener) {
            listeners.add(listener);
        }

        /**
         * 运行带历史记忆的reflection
         */
        public String runWithMemory(String task, String evaluationCriteria) {
            // 通知开始
            listeners.forEach(l -> l.onReflectionStart(agent, task, evaluationCriteria));

            Map<String, Object> variables = new HashMap<>();
            variables.put("task", task);
            variables.put("evaluationCriteria", evaluationCriteria);

            String currentSolution = agent.run(task, new HashMap<>());  // 初始方案
            int round = 1;

            while (round <= agent.getReflectionConfig().maxRound() && !shouldTerminate(currentSolution)) {
                Instant roundStart = Instant.now();

                // 准备历史评估数据
                if (!evaluationHistory.isEmpty()) {
                    List<Map<String, Object>> historyData = new ArrayList<>();
                    for (int i = 0; i < evaluationHistory.size(); i++) {
                        ReflectionEvaluation eval = evaluationHistory.get(i);
                        Map<String, Object> evalMap = new HashMap<>();
                        evalMap.put("round", i + 1);
                        evalMap.put("score", eval.getScore());
                        evalMap.put("strengths", String.join(", ", eval.getStrengths()));
                        evalMap.put("weaknesses", String.join(", ", eval.getWeaknesses()));
                        evalMap.put("suggestions", String.join(", ", eval.getSuggestions()));
                        historyData.add(evalMap);
                    }

                    Map<String, Object> historyWrapper = new HashMap<>();
                    historyWrapper.put("evaluations", historyData);
                    variables.put("previousEvaluations", historyWrapper);
                }

                variables.put("solution", currentSolution);

                // 通知轮次开始
                final int currentRound = round;
                final String solution = currentSolution;
                listeners.forEach(l -> l.onBeforeRound(agent, currentRound, solution));

                // 执行reflection（这里简化处理，实际应该调用agent的内部reflection方法）
                String reflectionPrompt = processTemplate(REFLECTION_WITH_HISTORY_TEMPLATE, variables);
                String evaluationOutput = simulateReflection(reflectionPrompt);

                // 解析评估结果
                ReflectionEvaluation evaluation = parseEvaluation(evaluationOutput);
                if (evaluation != null) {
                    evaluationHistory.add(evaluation);
                    currentSolution = evaluation.getImprovedSolution() != null ?
                        evaluation.getImprovedSolution() : currentSolution;

                    // 记录到历史
                    Duration roundDuration = Duration.between(roundStart, Instant.now());
                    ReflectionHistory.ReflectionRound roundData = new ReflectionHistory.ReflectionRound(
                        round, task, evaluationOutput, evaluation, roundDuration, 1000  // 模拟token数
                    );
                    reflectionHistory.addRound(roundData);

                    // 通知轮次完成
                    final int finalRound = round;
                    listeners.forEach(l -> l.onAfterRound(agent, finalRound, evaluationOutput, evaluation));

                    // 检查终止条件
                    if (evaluation.getScore() >= 9) {
                        final int roundAtScore = round;
                        listeners.forEach(l -> l.onScoreAchieved(agent, evaluation.getScore(), roundAtScore));
                        reflectionHistory.complete(ReflectionHistory.ReflectionStatus.COMPLETED_SUCCESS);
                        break;
                    }
                }

                round++;
            }

            // 完成reflection
            reflectionHistory.complete(ReflectionHistory.ReflectionStatus.COMPLETED_MAX_ROUNDS);
            listeners.forEach(l -> l.onReflectionComplete(agent, reflectionHistory));

            // 返回最终方案
            return currentSolution;
        }

        private boolean shouldTerminate(String output) {
            return output != null && output.contains("TERMINATE");
        }

        private String processTemplate(String template, Map<String, Object> variables) {
            // 简化的模板处理（实际应使用Mustache）
            String result = template;
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                String key = "{{" + entry.getKey() + "}}";
                result = result.replace(key, String.valueOf(entry.getValue()));
            }
            return result;
        }

        private String simulateReflection(String prompt) {
            // 模拟LLM调用（实际应该调用agent.chat）
            return """
                {
                  "score": 7,
                  "strengths": ["逻辑清晰", "代码规范"],
                  "weaknesses": ["缺少错误处理", "性能未优化"],
                  "suggestions": ["添加异常处理", "使用更高效的数据结构"],
                  "improved_solution": "// 改进后的代码\\npublic class Solution {\\n  // improved implementation\\n}"
                }
                """;
        }

        private ReflectionEvaluation parseEvaluation(String output) {
            try {
                int start = output.indexOf("{");
                int end = output.lastIndexOf("}") + 1;
                if (start >= 0 && end > start) {
                    String json = output.substring(start, end);
                    return objectMapper.readValue(json, ReflectionEvaluation.class);
                }
            } catch (Exception e) {
                logger.error("Failed to parse evaluation", e);
            }
            return null;
        }

        public ReflectionHistory getHistory() {
            return reflectionHistory;
        }
    }

    /**
     * 示例：使用带历史记忆的Reflection
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("Reflection with Memory Example - 带历史记忆的反思机制");
        System.out.println("=".repeat(80) + "\n");

        // 配置
        String endpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String apiKey = System.getenv("AZURE_OPENAI_API_KEY");
        String deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT");

        if (endpoint == null || apiKey == null || deployment == null) {
            logger.error("Missing environment variables");
            return;
        }

        LLMProviderConfig config = new LLMProviderConfig(deployment, 0.7, null);
        LLMProvider llmProvider = new AzureOpenAIProvider(config, apiKey, endpoint);

        // 创建Agent
        Agent baseAgent = Agent.builder()
                .name("memory-aware-agent")
                .llmProvider(llmProvider)
                .systemPrompt("你是代码优化专家，基于历史反馈持续改进。")
                .reflectionConfig(new ReflectionConfig(true, 5, 1,
                    REFLECTION_WITH_HISTORY_TEMPLATE, null))
                .build();

        // Note: ReflectionConfig automatically adds MaxRoundTermination and StopMessageTermination

        String task = "实现一个线程安全的单例模式";
        String criteria = """
            评估标准：
            1. 线程安全性（必须）
            2. 性能优化（延迟初始化）
            3. 防止反射攻击
            4. 防止序列化破坏
            5. 代码简洁性
            """;

        // 创建带记忆的Agent
        MemoryAwareAgent memoryAgent = new MemoryAwareAgent(baseAgent, task, criteria);

        // 添加回调监听器
        memoryAgent.addListener(new ReflectionListener() {
            @Override
            public void onAfterRound(Agent agent, int round, String output, ReflectionEvaluation evaluation) {
                if (evaluation != null) {
                    System.out.println(String.format("\n[回调] 轮次 %d 完成:", round));
                    System.out.println("  - 得分: " + evaluation.getScore() + "/10");
                    System.out.println("  - 优点: " + evaluation.getStrengths());
                    System.out.println("  - 缺点: " + evaluation.getWeaknesses());
                    System.out.println("  - 改进建议: " + evaluation.getSuggestions());
                }
            }

            @Override
            public void onScoreAchieved(Agent agent, int finalScore, int rounds) {
                System.out.println(String.format("\n✅ [回调] 目标达成！得分: %d, 轮数: %d", finalScore, rounds));
            }

            @Override
            public void onReflectionComplete(Agent agent, ReflectionHistory history) {
                System.out.println("\n[回调] Reflection完成");
                System.out.println("  - 总轮数: " + history.getRounds().size());
                System.out.println("  - 最终得分: " + history.getFinalScore());
                System.out.println("  - 平均改进率: " + history.getAverageImprovementRate() + "%");
            }
        });

        // 运行带记忆的reflection
        System.out.println("开始Reflection（带历史记忆）...\n");
        String finalSolution = memoryAgent.runWithMemory(task, criteria);

        // 输出最终结果
        System.out.println("\n" + "=".repeat(80));
        System.out.println("最终方案:");
        System.out.println(finalSolution);

        // 输出完整历史报告
        System.out.println("\n" + "=".repeat(80));
        System.out.println(memoryAgent.getHistory().generateSummary());
    }
}