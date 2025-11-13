package ai.core.termination.terminations;

import ai.core.agent.Agent;
import ai.core.agent.Node;
import ai.core.termination.Termination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于改进率的终止条件 - 当连续几轮没有改进时终止
 * Terminates when no improvement is detected for consecutive rounds
 *
 * @author xander
 */
public class NoImprovementTermination implements Termination {

    private static final Logger logger = LoggerFactory.getLogger(NoImprovementTermination.class);

    private final int maxNoImprovementRounds;
    private final double minImprovementRate;
    private final List<Integer> scoreHistory;
    private int noImprovementCount;

    /**
     * 创建基于改进率的终止条件
     * @param maxNoImprovementRounds 最大无改进轮数
     * @param minImprovementRate 最小改进率（百分比，如5表示5%）
     */
    public NoImprovementTermination(int maxNoImprovementRounds, double minImprovementRate) {
        this.maxNoImprovementRounds = maxNoImprovementRounds;
        this.minImprovementRate = minImprovementRate;
        this.scoreHistory = new ArrayList<>();
        this.noImprovementCount = 0;
    }

    /**
     * 使用默认值创建（连续2轮改进小于5%则终止）
     */
    public NoImprovementTermination() {
        this(2, 5.0);
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

        // 提取当前得分
        Integer currentScore = extractScore(output);

        if (currentScore == null) {
            return false;
        }

        // 第一轮，只记录分数
        if (scoreHistory.isEmpty()) {
            scoreHistory.add(currentScore);
            return false;
        }

        // 计算改进率
        Integer lastScore = scoreHistory.get(scoreHistory.size() - 1);
        double improvementRate = calculateImprovementRate(lastScore, currentScore);

        logger.debug("Reflection round {}: score={}, last_score={}, improvement_rate={}%",
                agent.getRound(), currentScore, lastScore, improvementRate);

        // 记录当前分数
        scoreHistory.add(currentScore);

        // 检查改进率
        if (improvementRate < minImprovementRate) {
            noImprovementCount++;
            logger.info("No significant improvement detected (count={}/{})",
                    noImprovementCount, maxNoImprovementRounds);

            if (noImprovementCount >= maxNoImprovementRounds) {
                logger.info("Terminating due to lack of improvement after {} rounds",
                        maxNoImprovementRounds);
                return true;
            }
        } else {
            // 有改进，重置计数器
            noImprovementCount = 0;
        }

        // 额外检查：如果分数下降且已经运行了多轮，也可以终止
        if (currentScore < lastScore && agent.getRound() > 2) {
            logger.info("Terminating due to score decrease: {} -> {}",
                    lastScore, currentScore);
            return true;
        }

        return false;
    }

    /**
     * 计算改进率
     */
    private double calculateImprovementRate(int lastScore, int currentScore) {
        if (lastScore == 0) {
            return currentScore > 0 ? 100.0 : 0.0;
        }
        return ((double)(currentScore - lastScore) / lastScore) * 100.0;
    }

    /**
     * 从输出中提取分数
     */
    private Integer extractScore(String output) {
        // 多种模式匹配分数
        String[] patterns = {
            "\"score\"\\s*:\\s*(\\d+)",           // JSON格式
            "(?i)score\\s*[：:=]\\s*(\\d+)",      // Score: 8
            "得分\\s*[：:=]\\s*(\\d+)",            // 得分：8
            "评分\\s*[：:=]\\s*(\\d+)",            // 评分：8
            "(\\d+)\\s*/\\s*10",                   // 8/10
            "(?i)\\bscore\\b.*?(\\d+)"            // 任何包含score和数字的情况
        };

        for (String patternStr : patterns) {
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(output);

            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (NumberFormatException e) {
                    // Continue to next pattern
                }
            }
        }

        return null;
    }

    /**
     * 获取分数历史
     */
    public List<Integer> getScoreHistory() {
        return new ArrayList<>(scoreHistory);
    }

    /**
     * 获取平均改进率
     */
    public double getAverageImprovementRate() {
        if (scoreHistory.size() <= 1) {
            return 0.0;
        }

        double totalImprovement = 0;
        int count = 0;

        for (int i = 1; i < scoreHistory.size(); i++) {
            double rate = calculateImprovementRate(
                scoreHistory.get(i - 1),
                scoreHistory.get(i)
            );
            totalImprovement += rate;
            count++;
        }

        return count > 0 ? totalImprovement / count : 0.0;
    }

    /**
     * 重置状态（用于新的reflection过程）
     */
    public void reset() {
        scoreHistory.clear();
        noImprovementCount = 0;
    }
}