package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;

/**
 * Publish outcome before view conversion; the service that owns user/skill name lookup builds the view.
 *
 * @author core-ai
 */
public record AgentPublishResult(AgentDefinition entity, String runId, int warnCount) {
}
