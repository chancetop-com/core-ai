package ai.core.termination.terminations;

import ai.core.agent.Agent;
import ai.core.agent.Node;
import ai.core.termination.Termination;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.core.reflection.ReflectionEvaluation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于评分的终止条件
 * Score-based termination for reflection
 *
 * @author xander
 */
public class ScoreBasedTermination implements Termination {

    private static final Logger logger = LoggerFactory.getLogger(ScoreBasedTermination.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final int targetScore;
    private final boolean requirePass;

    /**
     * 创建基于目标分数的终止条件
     * @param targetScore 目标分数（1-10）
     */
    public ScoreBasedTermination(int targetScore) {
        this(targetScore, false);
    }

    /**
     * 创建基于目标分数的终止条件
     * @param targetScore 目标分数（1-10）
     * @param requirePass 是否需要pass字段为true
     */
    public ScoreBasedTermination(int targetScore, boolean requirePass) {
        if (targetScore < 1 || targetScore > 10) {
            throw new IllegalArgumentException("Target score must be between 1 and 10");
        }
        this.targetScore = targetScore;
        this.requirePass = requirePass;
    }

    @Override
    public boolean terminate(Node<?> node) {
        if (!(node instanceof Agent)) {
            return false;
        }

        Agent agent = (Agent) node;
        String output = agent.getOutput();

        if (output == null || output.isEmpty()) {
            return false;
        }

        try {
            // 尝试从输出中提取JSON格式的评估结果
            ReflectionEvaluation evaluation = extractEvaluation(output);

            if (evaluation != null) {
                boolean scoreReached = evaluation.getScore() >= targetScore;
                boolean passCondition = !requirePass || evaluation.isPass();

                if (scoreReached && passCondition) {
                    logger.info("Score-based termination triggered: score={}/{}, pass={}",
                            evaluation.getScore(), targetScore, evaluation.isPass());
                    return true;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not extract evaluation from output: {}", e.getMessage());
        }

        // 回退：检查是否包含分数文本
        return checkScoreInText(output);
    }

    /**
     * 从输出中提取评估结果
     */
    private ReflectionEvaluation extractEvaluation(String output) {
        try {
            // 查找JSON部分
            int startIdx = output.indexOf("{");
            int endIdx = output.lastIndexOf("}");

            if (startIdx >= 0 && endIdx > startIdx) {
                String jsonStr = output.substring(startIdx, endIdx + 1);
                return objectMapper.readValue(jsonStr, ReflectionEvaluation.class);
            }
        } catch (Exception e) {
            logger.debug("Failed to parse JSON evaluation: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从文本中检查分数
     */
    private boolean checkScoreInText(String output) {
        // 查找类似 "Score: 8/10" 或 "得分: 9" 的模式
        String[] patterns = {
            "(?i)score[:\\s]+(\\d+)",
            "(?i)得分[：:\\s]+(\\d+)",
            "(\\d+)/10",
            "评分[：:\\s]+(\\d+)"
        };

        for (String pattern : patterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(output);

            if (m.find()) {
                try {
                    int score = Integer.parseInt(m.group(1));
                    if (score >= targetScore) {
                        logger.info("Score-based termination triggered from text: score={}/{}",
                                score, targetScore);
                        return true;
                    }
                } catch (NumberFormatException e) {
                    // Ignore
                }
            }
        }

        return false;
    }

    public int getTargetScore() {
        return targetScore;
    }

    public boolean isRequirePass() {
        return requirePass;
    }
}