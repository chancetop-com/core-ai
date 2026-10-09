package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemorySectionManager;
import ai.core.llm.domain.RoleType;
import ai.core.session.InProcessAgentSession;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One live engine session: the agent, its {@link InProcessAgentSession} and the headless equivalents
 * of the REPL control-plane commands (stats / export / compact / undo).
 *
 * @author stephen
 */
public class EngineSession {
    private final String id;
    private final Agent agent;
    private final InProcessAgentSession session;
    private final MdMemoryProvider memoryProvider;

    EngineSession(String id, Agent agent, InProcessAgentSession session, MdMemoryProvider memoryProvider) {
        this.id = id;
        this.agent = agent;
        this.session = session;
        this.memoryProvider = memoryProvider;
    }

    public String id() {
        return id;
    }

    public Agent agent() {
        return agent;
    }

    public InProcessAgentSession inProcess() {
        return session;
    }

    public boolean running() {
        return session.isTurnRunning();
    }

    public void close() {
        session.close();
    }

    /** Persist before closing so a graceful engine shutdown never loses the last turn. */
    public void save() {
        if (agent.hasPersistenceProvider() && agent.hasUserMessage()) {
            agent.save(id);
        }
    }

    public ObjectNode stats() {
        var usage = agent.getCurrentTokenUsage();
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("turns", (int) agent.getHistory().stream().filter(message -> message.role == RoleType.USER).count());
        node.put("inputTokens", usage.getPromptTokens());
        node.put("outputTokens", usage.getCompletionTokens());
        node.put("totalTokens", usage.getTotalTokens());
        node.put("costUsd", agent.getCurrentCostUsd());
        node.put("tools", agent.getToolCalls().size());
        return node;
    }

    public ObjectNode exportMarkdown() {
        var builder = new StringBuilder(4096);
        builder.append("# Session: ").append(id).append("\n\n");
        for (var message : agent.getHistory()) {
            var text = message.getTextContent();
            if (text != null) {
                builder.append("## ").append(message.role.name()).append("\n\n").append(text).append("\n\n");
            }
        }
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("content", builder.toString());
        return node;
    }

    public ObjectNode compact() {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var messages = agent.getMessages();
        var compression = agent.getCompression();
        if (messages.size() <= 4 || compression == null) {
            node.put("compacted", false);
            node.put("reason", "nothing to compact");
            return node;
        }
        int beforeCount = messages.size();
        var compressed = compression.forceCompress(messages);
        if (compressed.equals(messages)) {
            node.put("compacted", false);
            var failure = compression.getLastFailure();
            if (failure != null) {
                node.put("reason", failure);
            }
            return node;
        }
        messages.clear();
        messages.addAll(compressed);
        if (agent.hasPersistenceProvider()) {
            agent.save(id);
        }
        if (memoryProvider != null) {
            MemorySectionManager.reloadAgentMemorySection(agent, memoryProvider);
        }
        node.put("compacted", true);
        node.put("beforeCount", beforeCount);
        node.put("afterCount", messages.size());
        return node;
    }

    public ObjectNode undo() {
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var messages = agent.getMessages();
        int index = messages.size() - 1;
        while (index >= 0 && messages.get(index).role != RoleType.USER) {
            index--;
        }
        if (index < 0) {
            node.put("removed", 0);
            return node;
        }
        int removed = messages.size() - index;
        var removedMessages = messages.subList(index, messages.size());
        var history = agent.getHistory();
        for (var message : removedMessages) {
            history.remove(message);
        }
        removedMessages.clear();
        if (agent.hasPersistenceProvider()) {
            agent.save(id);
        }
        node.put("removed", removed);
        return node;
    }
}
