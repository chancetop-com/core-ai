package ai.core.llm.providers.inner;

import ai.core.agent.AgentRole;

import java.util.List;

/**
 * @author stephen
 */
public class VisionMessage {
    public AgentRole role;
    public List<VisionContent> content;
}
