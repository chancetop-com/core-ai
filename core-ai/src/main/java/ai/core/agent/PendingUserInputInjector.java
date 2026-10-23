package ai.core.agent;

import ai.core.agent.internal.AgentHelper;

/**
 * Injects messages accepted while the turn was running. The agent loop calls this only at the top
 * of an iteration, after the previous model call and its whole tool batch were recorded, so a user
 * message can never split an assistant tool call from its tool result.
 *
 * @author stephen
 */
final class PendingUserInputInjector {
    static void inject(Agent agent) {
        var queue = agent.getExecutionContext().getPendingUserInput();
        if (queue == null || queue.isEmpty()) return;
        // A cancelled tool batch may leave an unanswered tool call; normalize before adding user text.
        MessageNormalizer.normalize(agent);
        var context = agent.getExecutionContext();
        for (var input : queue.drain()) {
            if (input.variables() != null && !input.variables().isEmpty()) {
                context.getCustomVariables().putAll(input.variables());
            }
            var previousAttachments = context.getAttachedContents();
            context.setAttachedContents(input.attachedContents());
            try {
                agent.addMessage(AgentHelper.buildUserMessage(input.message(), context));
            } finally {
                context.setAttachedContents(previousAttachments);
            }
        }
    }

    static boolean hasPending(Agent agent) {
        var queue = agent.getExecutionContext().getPendingUserInput();
        return queue != null && !queue.isEmpty();
    }

    private PendingUserInputInjector() {
    }
}
