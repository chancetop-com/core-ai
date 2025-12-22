package ai.core.benchmark.evaluator;

import ai.core.agent.Agent;

import java.util.function.Supplier;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
public interface Evaluator {
    void evaluate(Supplier<Agent> agentSupplier);
}
