package ai.core.cli.agent;

import ai.core.prompt.PromptInject;

/**
 * @author stephen
 */
record CliAgentBasePrompt() implements PromptInject {
    @Override
    public String inject() {
        return "You are a helpful AI coding assistant and a personal assistant running inside core-ai.";
    }

    @Override
    public SectionType type() {
        return SectionType.IDENTITY;
    }
}
