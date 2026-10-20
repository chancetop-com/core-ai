package ai.core.server.session;

import ai.core.agent.AgentFootprint;

/**
 * One live session's footprint and ages, sampled by the runtime stats job.
 *
 * @author stephen
 */
public record SessionSnapshot(String sessionId, AgentFootprint footprint, long idleMillis, long residentMillis) {
}
