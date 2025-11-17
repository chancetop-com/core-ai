package ai.core.reflection;

import ai.core.agent.Agent;

/**
 * Reflection回调接口 - 在reflection过程中接收事件通知
 * Callback interface for reflection events
 *
 * @author xander
 */
public interface ReflectionListener {

    /**
     * 在reflection开始前调用
     *
     * @param agent 执行reflection的agent
     * @param task 原始任务
     * @param evaluationCriteria 评估标准
     */
    default void onReflectionStart(Agent agent, String task, String evaluationCriteria) {
        // 默认空实现
    }

    /**
     * 每轮reflection前调用
     *
     * @param agent 执行reflection的agent
     * @param round 当前轮次
     * @param input 输入内容
     */
    default void onBeforeRound(Agent agent, int round, String input) {
        // 默认空实现
    }

    /**
     * 每轮reflection后调用
     *
     * @param agent 执行reflection的agent
     * @param round 当前轮次
     * @param output 输出内容
     * @param evaluation 评估结果（如果可解析）
     */
    default void onAfterRound(Agent agent, int round, String output, ReflectionEvaluation evaluation) {
        // 默认空实现
    }

    /**
     * 当reflection因得分达标而终止时调用
     *
     * @param agent 执行reflection的agent
     * @param finalScore 最终得分
     * @param rounds 总轮数
     */
    default void onScoreAchieved(Agent agent, int finalScore, int rounds) {
        // 默认空实现
    }

    /**
     * 当reflection因无改进而终止时调用
     *
     * @param agent 执行reflection的agent
     * @param lastScore 最后得分
     * @param rounds 总轮数
     */
    default void onNoImprovement(Agent agent, int lastScore, int rounds) {
        // 默认空实现
    }

    /**
     * 当reflection因达到最大轮数而终止时调用
     *
     * @param agent 执行reflection的agent
     * @param finalScore 最终得分
     */
    default void onMaxRoundsReached(Agent agent, int finalScore) {
        // 默认空实现
    }

    /**
     * reflection完成后调用
     *
     * @param agent 执行reflection的agent
     * @param history 完整的reflection历史
     */
    default void onReflectionComplete(Agent agent, ReflectionHistory history) {
        // 默认空实现
    }

    /**
     * 当reflection过程中发生错误时调用
     *
     * @param agent 执行reflection的agent
     * @param round 当前轮次
     * @param error 错误信息
     */
    default void onError(Agent agent, int round, Exception error) {
        // 默认空实现
    }
}