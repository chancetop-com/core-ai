package ai.core.cli.agent;

import ai.core.prompt.PromptInject;
import ai.core.prompt.Prompts;

/**
 * The assistant personality shared with the server default assistant, so the CLI talks with the same voice
 * whichever mode it runs in (coding or plain) and whether it runs locally or against a server.
 *
 * @author stephen
 */
record CliAgentPersonalityPrompt() implements PromptInject {
    @Override
    public String inject() {
        return Prompts.ASSISTANT_PERSONALITY_PROMPT;
    }

    @Override
    public SectionType type() {
        return SectionType.IDENTITY;
    }
}
