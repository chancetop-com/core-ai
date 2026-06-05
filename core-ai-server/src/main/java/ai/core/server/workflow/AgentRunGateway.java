package ai.core.server.workflow;

import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;

/**
 * The seam between an AGENT/LLM node and the existing agent run subsystem. It hides AgentRunner, the agent_runs
 * polling, and the snapshot -> transient AgentDefinition build behind a small contract, so {@link
 * ai.core.server.workflow.executor.AgentExecutor}'s submit/await/collect logic is unit-testable with a fake.
 * The Mongo/AgentRunner-backed implementation lands once the server module compiles.
 *
 * @author Xander
 */
public interface AgentRunGateway {
    /** Start a decoupled child AgentRun from the node's embedded published snapshot; returns its run id. */
    String startChildRun(WorkflowRun run, WorkflowNode node, String input);

    /** Block until the child AgentRun reaches a terminal status, then return its result. */
    AgentRunResult awaitResult(String childRunId);

    /** Best-effort cancel of an in-flight child AgentRun (forwarded on workflow cancel). */
    void cancel(String childRunId);
}
