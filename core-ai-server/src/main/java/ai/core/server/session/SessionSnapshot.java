package ai.core.server.session;

import ai.core.agent.AgentFootprint;

/**
 * One live session's footprint, its high-water marks and its ages, sampled by the runtime stats job.
 *
 * @author stephen
 */
public record SessionSnapshot(String sessionId, AgentFootprint footprint, long idleMillis, long residentMillis,
                              long peakEstimatedBytes, long peakInlineImageBytes, long peakContextTokens) {
}
