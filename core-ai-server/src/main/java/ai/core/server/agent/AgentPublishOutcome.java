package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;

/**
 * Publish result including the transparent WARN delivery channel. The body stays {@link AgentDefinitionView};
 * run id / gate result / warn count travel in response headers.
 *
 * @author core-ai
 */
public record AgentPublishOutcome(AgentDefinitionView view, String runId, String gateResult, int warnCount) {
}
