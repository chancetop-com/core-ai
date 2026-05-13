package ai.core.cli.memory;

import ai.core.agent.Agent;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Handles memory section reloading in the agent's system prompt.
 *
 * <p>The old two-phase extraction (LLM extract → agent write) is replaced by
 * {@link MemoryTriggerService} which runs T1/T3 extraction agents.
 */
public final class MemorySectionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemorySectionManager.class);

    /**
     * Reload the {@code <memories>} section in the agent's system prompt and first message.
     * Called after T1/T3 extraction agents complete, or on session resume / compression.
     */
    public static void reloadAgentMemorySection(Agent agent, MdMemoryProvider memoryProvider) {
        String fresh = memoryProvider.load();
        String replacement = "<memories>\n" + (fresh.isBlank() ? "(empty)" : fresh) + "\n</memories>";
        String current = agent.getSystemPrompt();
        if (current != null) {
            agent.setSystemPrompt(current.replaceAll("(?s)<memories>.*?</memories>", replacement));
        }
        if (!agent.getMessages().isEmpty()) {
            updateSystemMessage(agent.getMessages(), replacement);
        }
        LOGGER.debug("Memory section reloaded in system prompt");
    }

    private static void updateSystemMessage(List<Message> messages, String replacement) {
        var systemMsg = messages.getFirst();
        if (systemMsg.content == null || systemMsg.content.isEmpty()) return;
        String text = systemMsg.content.getFirst().text;
        if (text == null) return;
        systemMsg.content = List.of(Content.of(text.replaceAll("(?s)<memories>.*?</memories>", replacement)));
    }

    private MemorySectionManager() { /* static utility */ }
}
