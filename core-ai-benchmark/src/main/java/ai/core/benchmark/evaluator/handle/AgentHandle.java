package ai.core.benchmark.evaluator.handle;

import ai.core.agent.Agent;

/**
 * author: lim chen
 * date: 2025/12/22
 * description:
 */
public interface AgentHandle<T,R> {

    R handle(Agent agen, T item);
}
