package ai.core.telemetry;

import ai.core.reflection.ReflectionEvaluation;
import ai.core.reflection.ReflectionHistory;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

import java.util.List;
import java.util.Map;

/**
 * 增强的Reflection追踪器 - 与OpenTelemetry集成
 * Enhanced reflection tracer integrated with OpenTelemetry
 *
 * @author xander
 */
public class ReflectionTracer {

    private final Tracer tracer;
    private final boolean enabled;

    // Reflection特定的属性键
    private static final AttributeKey<String> REFLECTION_AGENT_ID = AttributeKey.stringKey("reflection.agent.id");
    private static final AttributeKey<String> REFLECTION_AGENT_NAME = AttributeKey.stringKey("reflection.agent.name");
    private static final AttributeKey<Long> REFLECTION_ROUND = AttributeKey.longKey("reflection.round");
    private static final AttributeKey<Long> REFLECTION_MAX_ROUND = AttributeKey.longKey("reflection.max_round");
    private static final AttributeKey<String> REFLECTION_TASK = AttributeKey.stringKey("reflection.task");
    private static final AttributeKey<String> REFLECTION_CRITERIA = AttributeKey.stringKey("reflection.criteria");
    private static final AttributeKey<Long> REFLECTION_SCORE = AttributeKey.longKey("reflection.score");
    private static final AttributeKey<Double> REFLECTION_IMPROVEMENT_RATE = AttributeKey.doubleKey("reflection.improvement_rate");
    private static final AttributeKey<String> REFLECTION_STATUS = AttributeKey.stringKey("reflection.status");
    private static final AttributeKey<List<String>> REFLECTION_STRENGTHS = AttributeKey.stringArrayKey("reflection.strengths");
    private static final AttributeKey<List<String>> REFLECTION_WEAKNESSES = AttributeKey.stringArrayKey("reflection.weaknesses");
    private static final AttributeKey<List<String>> REFLECTION_SUGGESTIONS = AttributeKey.stringArrayKey("reflection.suggestions");
    private static final AttributeKey<Long> REFLECTION_TOKEN_COUNT = AttributeKey.longKey("reflection.token_count");
    private static final AttributeKey<String> REFLECTION_TERMINATION_REASON = AttributeKey.stringKey("reflection.termination_reason");

    public ReflectionTracer(Tracer tracer, boolean enabled) {
        this.tracer = tracer;
        this.enabled = enabled;
    }

    /**
     * 开始reflection过程的追踪
     */
    public Span startReflectionSpan(String agentId, String agentName, String task, String criteria) {
        if (!enabled) return Span.getInvalid();

        return tracer.spanBuilder("reflection.process")
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(REFLECTION_AGENT_ID, agentId)
                .setAttribute(REFLECTION_AGENT_NAME, agentName)
                .setAttribute(REFLECTION_TASK, truncate(task, 500))
                .setAttribute(REFLECTION_CRITERIA, truncate(criteria, 500))
                .startSpan();
    }

    /**
     * 开始单轮reflection的追踪
     */
    public Span startRoundSpan(Context parentContext, int round, int maxRound, String input) {
        if (!enabled) return Span.getInvalid();

        return tracer.spanBuilder("reflection.round")
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute(REFLECTION_ROUND, (long) round)
                .setAttribute(REFLECTION_MAX_ROUND, (long) maxRound)
                .setAttribute("reflection.input", truncate(input, 1000))
                .startSpan();
    }

    /**
     * 记录评估结果
     */
    public void recordEvaluation(Span span, ReflectionEvaluation evaluation) {
        if (!enabled || span == null || !span.isRecording()) return;

        span.setAttribute(REFLECTION_SCORE, (long) evaluation.getScore());

        if (evaluation.getStrengths() != null) {
            span.setAttribute(REFLECTION_STRENGTHS, evaluation.getStrengths());
        }

        if (evaluation.getWeaknesses() != null) {
            span.setAttribute(REFLECTION_WEAKNESSES, evaluation.getWeaknesses());
        }

        if (evaluation.getSuggestions() != null) {
            span.setAttribute(REFLECTION_SUGGESTIONS, evaluation.getSuggestions());
        }

        if (evaluation.getDimensionScores() != null) {
            for (Map.Entry<String, Integer> entry : evaluation.getDimensionScores().entrySet()) {
                span.setAttribute("reflection.dimension." + entry.getKey(), entry.getValue().longValue());
            }
        }

        span.setAttribute("reflection.confidence", evaluation.getConfidence());
        span.setAttribute("reflection.should_continue", evaluation.isShouldContinue());
    }

    /**
     * 记录改进率
     */
    public void recordImprovementRate(Span span, double improvementRate) {
        if (!enabled || span == null || !span.isRecording()) return;
        span.setAttribute(REFLECTION_IMPROVEMENT_RATE, improvementRate);
    }

    /**
     * 记录终止原因
     */
    public void recordTermination(Span span, String reason, int finalScore) {
        if (!enabled || span == null || !span.isRecording()) return;

        span.setAttribute(REFLECTION_TERMINATION_REASON, reason);
        span.setAttribute("reflection.final_score", (long) finalScore);

        // 根据终止原因设置span状态
        if ("score_achieved".equals(reason)) {
            span.setStatus(StatusCode.OK, "Target score achieved");
        } else if ("max_rounds".equals(reason)) {
            span.setStatus(StatusCode.OK, "Max rounds reached");
        } else if ("no_improvement".equals(reason)) {
            span.setStatus(StatusCode.OK, "No improvement detected");
        } else if ("error".equals(reason)) {
            span.setStatus(StatusCode.ERROR, "Reflection failed");
        }
    }

    /**
     * 记录完整的reflection历史
     */
    public void recordHistory(Span span, ReflectionHistory history) {
        if (!enabled || span == null || !span.isRecording()) return;

        span.setAttribute("reflection.total_rounds", (long) history.getRounds().size());
        span.setAttribute("reflection.total_duration_ms", history.getTotalDuration().toMillis());
        span.setAttribute(REFLECTION_TOKEN_COUNT, (long) history.getTotalTokensUsed());
        span.setAttribute("reflection.final_score", (long) history.getFinalScore());
        span.setAttribute("reflection.average_improvement_rate", history.getAverageImprovementRate());
        span.setAttribute(REFLECTION_STATUS, history.getStatus().toString());

        // 记录每轮的得分趋势
        List<Integer> scores = history.getRounds().stream()
                .map(r -> r.getEvaluation().getScore())
                .toList();
        span.setAttribute("reflection.score_progression", scores.toString());

        // 记录最佳轮次
        ReflectionHistory.ReflectionRound bestRound = history.getBestRound();
        if (bestRound != null) {
            span.setAttribute("reflection.best_round", (long) bestRound.getRoundNumber());
            span.setAttribute("reflection.best_score", (long) bestRound.getEvaluation().getScore());
        }
    }

    /**
     * 创建reflection事件
     */
    public void addReflectionEvent(Span span, String eventName, Attributes attributes) {
        if (!enabled || span == null || !span.isRecording()) return;
        span.addEvent(eventName, attributes);
    }

    /**
     * 记录错误
     */
    public void recordError(Span span, Exception error) {
        if (!enabled || span == null || !span.isRecording()) return;

        span.recordException(error);
        span.setStatus(StatusCode.ERROR, error.getMessage());
    }

    /**
     * 创建带追踪的reflection执行上下文
     */
    public ReflectionContext createContext(String agentId, String agentName,
                                            String task, String criteria) {
        Span span = startReflectionSpan(agentId, agentName, task, criteria);
        return new ReflectionContext(span, Context.current().with(span));
    }

    /**
     * Reflection追踪上下文
     */
    public static class ReflectionContext implements AutoCloseable {
        private final Span span;
        private final Context context;
        private final Scope scope;

        public ReflectionContext(Span span, Context context) {
            this.span = span;
            this.context = context;
            this.scope = context.makeCurrent();
        }

        public Span getSpan() { return span; }
        public Context getContext() { return context; }

        @Override
        public void close() {
            if (scope != null) scope.close();
            if (span != null) span.end();
        }
    }

    /**
     * 截断长文本
     */
    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
    }

    /**
     * 创建reflection指标
     */
    public static class ReflectionMetrics {
        // 可以添加Prometheus或其他指标收集
        private long totalReflections = 0;
        private long successfulReflections = 0;
        private long failedReflections = 0;
        private double averageRounds = 0;
        private double averageScore = 0;
        private double averageImprovementRate = 0;

        public void recordReflection(ReflectionHistory history) {
            totalReflections++;

            if (history.getStatus() == ReflectionHistory.ReflectionStatus.COMPLETED_SUCCESS) {
                successfulReflections++;
            } else if (history.getStatus() == ReflectionHistory.ReflectionStatus.FAILED) {
                failedReflections++;
            }

            // 更新平均值
            int rounds = history.getRounds().size();
            averageRounds = ((averageRounds * (totalReflections - 1)) + rounds) / totalReflections;

            int score = history.getFinalScore();
            averageScore = ((averageScore * (totalReflections - 1)) + score) / totalReflections;

            double improvementRate = history.getAverageImprovementRate();
            averageImprovementRate = ((averageImprovementRate * (totalReflections - 1)) + improvementRate) / totalReflections;
        }

        public Map<String, Object> getMetrics() {
            return Map.of(
                "total_reflections", totalReflections,
                "successful_reflections", successfulReflections,
                "failed_reflections", failedReflections,
                "success_rate", totalReflections > 0 ? (double) successfulReflections / totalReflections : 0,
                "average_rounds", averageRounds,
                "average_score", averageScore,
                "average_improvement_rate", averageImprovementRate
            );
        }
    }
}