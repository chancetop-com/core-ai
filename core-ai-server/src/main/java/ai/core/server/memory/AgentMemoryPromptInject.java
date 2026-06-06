package ai.core.server.memory;

import ai.core.prompt.PromptInject;

/**
 * @author stephen
 */
public class AgentMemoryPromptInject implements PromptInject {
    private final String content;

    public AgentMemoryPromptInject(String content) {
        this.content = content;
    }

    @Override
    public String inject() {
        return content;
    }

    @Override
    public SectionType type() {
        return SectionType.MEMORY;
    }
}
