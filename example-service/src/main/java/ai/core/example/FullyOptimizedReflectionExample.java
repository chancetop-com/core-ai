package ai.core.example;

import ai.core.agent.Agent;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.AzureOpenAIProvider;
import ai.core.reflection.ReflectionConfig;
import ai.core.reflection.ReflectionEvaluation;
import ai.core.reflection.ReflectionHistory;
import ai.core.reflection.ReflectionListener;
import ai.core.telemetry.AgentTracer;
import ai.core.telemetry.ReflectionTracer;
import ai.core.telemetry.TelemetryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 完整优化的Reflection示例 - 集成所有优化功能
 * Fully optimized reflection example with all features
 *
 * @author xander
 */
public class FullyOptimizedReflectionExample {

    private static final Logger logger = LoggerFactory.getLogger(FullyOptimizedReflectionExample.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 增强型Reflection模板 - 支持结构化评估和历史
     */
    private static final String ENHANCED_REFLECTION_TEMPLATE = """
        你是一个专业的评估者，请基于评估标准和历史记录进行严格评估。

        === 任务信息 ===
        任务: {{task}}
        当前轮次: {{currentRound}}/{{maxRound}}

        === 评估标准（业务要求） ===
        {{evaluationCriteria}}

        {{#hasHistory}}
        === 评估历史（前{{historyCount}}轮） ===
        整体趋势: {{trend}}

        {{#evaluations}}
        第{{round}}轮:
        - 得分: {{score}}/10 (改进率: {{improvementRate}}%)
        - 主要优点: {{topStrengths}}
        - 主要缺点: {{topWeaknesses}}
        - 关键建议: {{keySuggestion}}
        {{/evaluations}}

        需要重点改进的持续性问题:
        {{persistentIssues}}
        === 历史结束 ===
        {{/hasHistory}}

        === 当前方案 ===
        {{solution}}

        === 评估要求 ===
        请提供结构化的JSON评估结果，格式如下：
        {
          "score": <整数1-10>,
          "pass": <布尔值，是否达到业务标准>,
          "dimensions": {
            <各维度名称>: <分数1-10>
          },
          "strengths": ["具体的优点"],
          "weaknesses": ["需要改进的地方"],
          "suggestions": ["下一步具体的改进建议"],
          "persistent_issues": ["持续存在的问题"],
          "improved_solution": "完整的改进方案",
          "confidence": <0.0-1.0置信度>,
          "should_continue": <布尔值，是否需要继续改进>
        }

        评分标准：
        - 9-10分: 优秀，完全满足所有要求
        - 7-8分: 良好，满足主要要求
        - 5-6分: 一般，基本可用但需改进
        - 3-4分: 较差，存在明显问题
        - 1-2分: 很差，需要重写

        如果score >= {{targetScore}}或should_continue为false，在JSON后添加TERMINATE。
        """;

    /**
     * 完整优化的Agent包装器
     */
    public static class OptimizedReflectionAgent {
        private final Agent agent;
        private final String task;
        private final String evaluationCriteria;
        private final int targetScore;

        // 历史和追踪
        private final ReflectionHistory history;
        private final List<ReflectionEvaluation> evaluations;
        private final List<ReflectionListener> listeners;
        private final ReflectionTracer tracer;
        private final ReflectionTracer.ReflectionMetrics metrics;

        // 分析数据
        private final Map<String, Integer> dimensionTrends;
        private final Set<String> persistentIssues;

        public OptimizedReflectionAgent(Agent agent, String task, String evaluationCriteria,
                                         int targetScore, ReflectionTracer tracer) {
            this.agent = agent;
            this.task = task;
            this.evaluationCriteria = evaluationCriteria;
            this.targetScore = targetScore;
            this.history = new ReflectionHistory(agent.getId(), agent.getName(), task, evaluationCriteria);
            this.evaluations = new ArrayList<>();
            this.listeners = new ArrayList<>();
            this.tracer = tracer;
            this.metrics = new ReflectionTracer.ReflectionMetrics();
            this.dimensionTrends = new HashMap<>();
            this.persistentIssues = new HashSet<>();
        }

        public void addListener(ReflectionListener listener) {
            listeners.add(listener);
        }

        /**
         * 执行完整优化的reflection
         */
        public String execute() {
            // 创建追踪上下文
            try (ReflectionTracer.ReflectionContext tracingContext =
                    tracer.createContext(agent.getId(), agent.getName(), task, evaluationCriteria)) {

                Span mainSpan = tracingContext.getSpan();

                // 通知开始
                listeners.forEach(l -> l.onReflectionStart(agent, task, evaluationCriteria));

                // 初始执行
                String currentSolution = agent.run(task, new HashMap<>());
                int round = 1;
                int maxRound = agent.getReflectionConfig() != null ?
                    agent.getReflectionConfig().maxRound() : 5;

                while (round <= maxRound) {
                    // 开始轮次追踪
                    Span roundSpan = tracer.startRoundSpan(
                        tracingContext.getContext(), round, maxRound, currentSolution
                    );

                    try {
                        Instant roundStart = Instant.now();

                        // 准备模板变量
                        Map<String, Object> variables = prepareTemplateVariables(
                            round, maxRound, currentSolution
                        );

                        // 通知轮次开始
                        final int currentRound = round;
                        final String solution = currentSolution;
                        listeners.forEach(l -> l.onBeforeRound(agent, currentRound, solution));

                        // 执行reflection
                        String evaluationOutput = performReflection(variables);

                        // 解析评估结果
                        ReflectionEvaluation evaluation = parseEvaluation(evaluationOutput);

                        if (evaluation != null) {
                            // 记录评估
                            evaluations.add(evaluation);
                            tracer.recordEvaluation(roundSpan, evaluation);

                            // 分析趋势
                            analyzeTrends(evaluation);

                            // 更新解决方案
                            if (evaluation.getImprovedSolution() != null) {
                                currentSolution = evaluation.getImprovedSolution();
                            }

                            // 计算改进率
                            double improvementRate = calculateImprovementRate();
                            tracer.recordImprovementRate(roundSpan, improvementRate);

                            // 记录到历史
                            Duration duration = Duration.between(roundStart, Instant.now());
                            ReflectionHistory.ReflectionRound roundData =
                                new ReflectionHistory.ReflectionRound(
                                    round, task, evaluationOutput, evaluation, duration, 500
                                );
                            history.addRound(roundData);

                            // 通知轮次完成
                            final int finalRound = round;
                            listeners.forEach(l ->
                                l.onAfterRound(agent, finalRound, evaluationOutput, evaluation)
                            );

                            // 检查终止条件
                            String terminationReason = checkTermination(evaluation, round, improvementRate);
                            if (terminationReason != null) {
                                handleTermination(terminationReason, evaluation.getScore(), round);
                                tracer.recordTermination(mainSpan, terminationReason, evaluation.getScore());
                                break;
                            }
                        }

                    } finally {
                        roundSpan.end();
                    }

                    round++;
                }

                // 完成reflection
                completeReflection(mainSpan);

                return currentSolution;

            } catch (Exception e) {
                logger.error("Reflection failed", e);
                history.complete(ReflectionHistory.ReflectionStatus.FAILED);
                throw new RuntimeException("Reflection failed", e);
            }
        }

        /**
         * 准备模板变量
         */
        private Map<String, Object> prepareTemplateVariables(int round, int maxRound, String solution) {
            Map<String, Object> variables = new HashMap<>();
            variables.put("task", task);
            variables.put("evaluationCriteria", evaluationCriteria);
            variables.put("solution", solution);
            variables.put("currentRound", round);
            variables.put("maxRound", maxRound);
            variables.put("targetScore", targetScore);

            // 添加历史信息
            if (!evaluations.isEmpty()) {
                Map<String, Object> historyData = new HashMap<>();
                historyData.put("hasHistory", true);
                historyData.put("historyCount", evaluations.size());
                historyData.put("trend", analyzeTrend());

                // 准备评估历史
                List<Map<String, Object>> evalList = new ArrayList<>();
                for (int i = 0; i < evaluations.size(); i++) {
                    ReflectionEvaluation eval = evaluations.get(i);
                    Map<String, Object> evalMap = new HashMap<>();
                    evalMap.put("round", i + 1);
                    evalMap.put("score", eval.getScore());
                    evalMap.put("improvementRate", i > 0 ? calculateImprovementRate(i-1, i) : 0);
                    evalMap.put("topStrengths", getTop(eval.getStrengths(), 2));
                    evalMap.put("topWeaknesses", getTop(eval.getWeaknesses(), 2));
                    evalMap.put("keySuggestion", getFirst(eval.getSuggestions()));
                    evalList.add(evalMap);
                }
                historyData.put("evaluations", evalList);
                historyData.put("persistentIssues", String.join(", ", persistentIssues));

                variables.putAll(historyData);
            } else {
                variables.put("hasHistory", false);
            }

            return variables;
        }

        /**
         * 执行reflection（模拟）
         */
        private String performReflection(Map<String, Object> variables) {
            // 实际应该处理模板并调用LLM
            // 这里返回模拟数据
            int simulatedScore = 5 + Math.min(evaluations.size() * 2, 4);
            return String.format("""
                {
                  "score": %d,
                  "pass": %s,
                  "dimensions": {
                    "correctness": %d,
                    "performance": %d,
                    "readability": %d
                  },
                  "strengths": ["实现正确", "逻辑清晰"],
                  "weaknesses": ["缺少错误处理", "性能可优化"],
                  "suggestions": ["添加异常处理", "使用更高效算法"],
                  "improved_solution": "// Improved code here",
                  "confidence": 0.85,
                  "should_continue": %s
                }
                """, simulatedScore, simulatedScore >= 8,
                simulatedScore - 1, simulatedScore, simulatedScore - 1,
                simulatedScore < 9);
        }

        /**
         * 解析评估结果
         */
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

        /**
         * 分析趋势
         */
        private void analyzeTrends(ReflectionEvaluation evaluation) {
            // 跟踪维度分数变化
            if (evaluation.getDimensionScores() != null) {
                evaluation.getDimensionScores().forEach((dim, score) -> {
                    dimensionTrends.merge(dim, score, Integer::sum);
                });
            }

            // 识别持续性问题
            if (evaluation.getWeaknesses() != null) {
                evaluation.getWeaknesses().forEach(weakness -> {
                    // 简单的持续性检测（实际应该更智能）
                    if (evaluations.size() > 1) {
                        persistentIssues.add(weakness);
                    }
                });
            }
        }

        /**
         * 分析整体趋势
         */
        private String analyzeTrend() {
            if (evaluations.size() < 2) return "初始阶段";

            double avgImprovement = calculateAverageImprovement();
            if (avgImprovement > 10) return "快速改进";
            if (avgImprovement > 5) return "稳定改进";
            if (avgImprovement > 0) return "缓慢改进";
            if (avgImprovement == 0) return "停滞";
            return "退步";
        }

        /**
         * 计算改进率
         */
        private double calculateImprovementRate() {
            if (evaluations.size() < 2) return 0;
            return calculateImprovementRate(evaluations.size() - 2, evaluations.size() - 1);
        }

        private double calculateImprovementRate(int fromIndex, int toIndex) {
            if (fromIndex < 0 || toIndex >= evaluations.size()) return 0;
            int fromScore = evaluations.get(fromIndex).getScore();
            int toScore = evaluations.get(toIndex).getScore();
            if (fromScore == 0) return 100;
            return ((double)(toScore - fromScore) / fromScore) * 100;
        }

        private double calculateAverageImprovement() {
            if (evaluations.size() < 2) return 0;
            double total = 0;
            for (int i = 1; i < evaluations.size(); i++) {
                total += calculateImprovementRate(i-1, i);
            }
            return total / (evaluations.size() - 1);
        }

        /**
         * 检查终止条件
         */
        private String checkTermination(ReflectionEvaluation evaluation, int round, double improvementRate) {
            // 达到目标分数
            if (evaluation.getScore() >= targetScore) {
                return "score_achieved";
            }

            // should_continue标记
            if (!evaluation.isShouldContinue()) {
                return "evaluation_complete";
            }

            // 无改进检测
            if (round > 2 && Math.abs(improvementRate) < 1.0) {
                return "no_improvement";
            }

            // 分数下降
            if (improvementRate < -10) {
                return "score_decreased";
            }

            return null;
        }

        /**
         * 处理终止
         */
        private void handleTermination(String reason, int score, int round) {
            switch (reason) {
                case "score_achieved" -> {
                    listeners.forEach(l -> l.onScoreAchieved(agent, score, round));
                    history.complete(ReflectionHistory.ReflectionStatus.COMPLETED_SUCCESS);
                }
                case "no_improvement" -> {
                    listeners.forEach(l -> l.onNoImprovement(agent, score, round));
                    history.complete(ReflectionHistory.ReflectionStatus.COMPLETED_NO_IMPROVEMENT);
                }
                case "evaluation_complete" -> {
                    history.complete(ReflectionHistory.ReflectionStatus.COMPLETED_SUCCESS);
                }
                default -> {
                    history.complete(ReflectionHistory.ReflectionStatus.COMPLETED_MAX_ROUNDS);
                }
            }
        }

        /**
         * 完成reflection
         */
        private void completeReflection(Span span) {
            // 记录历史
            tracer.recordHistory(span, history);

            // 更新指标
            metrics.recordReflection(history);

            // 通知完成
            listeners.forEach(l -> l.onReflectionComplete(agent, history));

            // 输出摘要
            logger.info("Reflection completed: {} rounds, final score: {}, status: {}",
                history.getRounds().size(), history.getFinalScore(), history.getStatus());
        }

        // 辅助方法
        private String getTop(List<String> items, int n) {
            if (items == null || items.isEmpty()) return "";
            return String.join(", ", items.subList(0, Math.min(n, items.size())));
        }

        private String getFirst(List<String> items) {
            return (items != null && !items.isEmpty()) ? items.get(0) : "";
        }

        // Getters
        public ReflectionHistory getHistory() { return history; }
        public List<ReflectionEvaluation> getEvaluations() { return evaluations; }
        public ReflectionTracer.ReflectionMetrics getMetrics() { return metrics; }
    }

    /**
     * 主方法 - 运行完整优化示例
     */
    public static void main(String[] args) {
        System.out.println("=".repeat(80));
        System.out.println("完整优化的Reflection机制 / Fully Optimized Reflection");
        System.out.println("=".repeat(80) + "\n");

        // 配置
        String endpoint = System.getenv("AZURE_OPENAI_ENDPOINT");
        String apiKey = System.getenv("AZURE_OPENAI_API_KEY");
        String deployment = System.getenv("AZURE_OPENAI_DEPLOYMENT");

        if (endpoint == null || apiKey == null || deployment == null) {
            logger.error("Missing environment variables");
            return;
        }

        // 创建Telemetry配置
        TelemetryConfig telemetryConfig = TelemetryConfig.builder()
            .serviceName("reflection-example")
            .enabled(true)
            .build();

        // 创建追踪器
        AgentTracer agentTracer = new AgentTracer(
            telemetryConfig.getOpenTelemetry(), telemetryConfig.isEnabled()
        );
        ReflectionTracer reflectionTracer = new ReflectionTracer(
            telemetryConfig.getOpenTelemetry().getTracer("reflection"), true
        );

        // 创建LLM Provider
        LLMProviderConfig config = new LLMProviderConfig(deployment, 0.7, null);
        LLMProvider llmProvider = new AzureOpenAIProvider(config, apiKey, endpoint);

        // 定义任务和标准
        String task = "设计并实现一个高性能的分布式缓存系统";
        String criteria = """
            系统必须满足以下业务标准：

            性能要求：
            - 读操作延迟 < 1ms
            - 写操作延迟 < 5ms
            - QPS > 100K

            功能要求：
            - 支持LRU/LFU淘汰策略
            - 支持分布式一致性
            - 支持数据持久化
            - 支持热点数据识别

            可靠性要求：
            - 99.99%可用性
            - 自动故障转移
            - 数据不丢失
            """;

        // 创建Agent
        Agent agent = Agent.builder()
            .name("cache-system-designer")
            .llmProvider(llmProvider)
            .systemPrompt("你是分布式系统架构专家。")
            .reflectionConfig(new ReflectionConfig(
                true, 10, 2, ENHANCED_REFLECTION_TEMPLATE, criteria
            ))
            .tracer(agentTracer)
            .build();

        // Note: ReflectionConfig automatically adds MaxRoundTermination and StopMessageTermination
        // Custom terminations like ScoreBasedTermination and NoImprovementTermination
        // can be implemented in the reflection evaluation logic

        // 创建优化的reflection执行器
        OptimizedReflectionAgent optimizedAgent = new OptimizedReflectionAgent(
            agent, task, criteria, 9, reflectionTracer
        );

        // 添加详细的监听器
        optimizedAgent.addListener(new ReflectionListener() {
            @Override
            public void onReflectionStart(Agent agent, String task, String criteria) {
                System.out.println("\n🚀 开始Reflection过程");
                System.out.println("  任务: " + task);
                System.out.println("  目标分数: 9/10");
            }

            @Override
            public void onAfterRound(Agent agent, int round, String output, ReflectionEvaluation eval) {
                if (eval != null) {
                    System.out.println(String.format("\n📊 第%d轮完成:", round));
                    System.out.println(String.format("  得分: %d/10 (置信度: %.2f)",
                        eval.getScore(), eval.getConfidence()));
                    System.out.println("  优势: " + eval.getStrengths());
                    System.out.println("  不足: " + eval.getWeaknesses());

                    if (eval.getDimensionScores() != null) {
                        System.out.println("  维度得分: " + eval.getDimensionScores());
                    }
                }
            }

            @Override
            public void onScoreAchieved(Agent agent, int finalScore, int rounds) {
                System.out.println(String.format("\n✅ 成功！达到目标分数: %d/10 (用时%d轮)",
                    finalScore, rounds));
            }

            @Override
            public void onNoImprovement(Agent agent, int lastScore, int rounds) {
                System.out.println(String.format("\n⚠️ 改进停滞，终止于第%d轮 (分数: %d/10)",
                    rounds, lastScore));
            }

            @Override
            public void onReflectionComplete(Agent agent, ReflectionHistory history) {
                System.out.println("\n" + "=".repeat(60));
                System.out.println("📈 Reflection完成统计:");
                System.out.println("  总轮数: " + history.getRounds().size());
                System.out.println("  最终得分: " + history.getFinalScore() + "/10");
                System.out.println("  总耗时: " + history.getTotalDuration().toSeconds() + "秒");
                System.out.println("  平均改进率: " +
                    String.format("%.2f%%", history.getAverageImprovementRate()));
                System.out.println("  状态: " + history.getStatus());
                System.out.println("=".repeat(60));
            }
        });

        // 执行
        System.out.println("\n开始执行完整优化的Reflection...");
        String finalSolution = optimizedAgent.execute();

        // 输出最终方案
        System.out.println("\n" + "=".repeat(80));
        System.out.println("💡 最终方案:");
        System.out.println("=".repeat(80));
        System.out.println(finalSolution);

        // 输出详细报告
        System.out.println("\n" + "=".repeat(80));
        System.out.println("📊 详细报告:");
        System.out.println("=".repeat(80));
        System.out.println(optimizedAgent.getHistory().generateSummary());

        // 输出指标
        System.out.println("\n📈 性能指标:");
        optimizedAgent.getMetrics().getMetrics().forEach((key, value) ->
            System.out.println("  " + key + ": " + value)
        );
    }
}