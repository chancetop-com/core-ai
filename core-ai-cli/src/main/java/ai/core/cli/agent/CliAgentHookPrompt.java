package ai.core.cli.agent;

import ai.core.prompt.PromptInject;

/**
 * @author stephen
 */
record CliAgentHookPrompt(String output) implements PromptInject {
    @Override
    public SectionType type() {
        return SectionType.HOOK;
    }

    @Override
    public String inject() {
        return output;
    }
}
