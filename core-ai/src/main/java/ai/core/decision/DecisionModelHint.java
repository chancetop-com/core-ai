package ai.core.decision;

/**
 * One decision model available to a tool description or a hub client.
 *
 * @author stephen
 */
public record DecisionModelHint(String modelId, String displayName, String providerName, String upstreamModel) {
}
