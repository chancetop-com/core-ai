package ai.core.cli.appserver;

import ai.core.agent.Agent;
import ai.core.cli.memory.MdMemoryProvider;
import ai.core.cli.memory.MemorySectionManager;
import ai.core.llm.domain.RoleType;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionLock;
import ai.core.session.TurnPersistence;
import ai.core.session.transcript.TranscriptReader;
import ai.core.session.transcript.TranscriptRecorder;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One live engine session: the agent, its {@link InProcessAgentSession} and the headless equivalents
 * of the REPL control-plane commands (stats / export / compact / undo).
 *
 * @author stephen
 */
public class EngineSession {
    private static final Logger LOGGER = LoggerFactory.getLogger(EngineSession.class);

    private final String id;
    private final Agent agent;
    private final InProcessAgentSession session;
    private final MdMemoryProvider memoryProvider;
    private final SessionLock lock;
    private final TranscriptRecorder recorder;
    private final TranscriptReader transcript;
    private volatile long lastActivityMillis = System.currentTimeMillis();

    EngineSession(String id, Agent agent, InProcessAgentSession session, MdMemoryProvider memoryProvider,
                  SessionLock lock, TranscriptRecorder recorder) {
        this.id = id;
        this.agent = agent;
        this.session = session;
        this.memoryProvider = memoryProvider;
        this.lock = lock;
        this.recorder = recorder;
        this.transcript = new TranscriptReader(recorder.store());
    }

    void touch() {
        lastActivityMillis = System.currentTimeMillis();
    }

    boolean idle(long cutoff) {
        return lastActivityMillis < cutoff && !running() && !session.hasPendingWork();
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

    @SuppressWarnings("PMD.UseTryWithResources")
    public void close() {
        try {
            session.close();
        } finally {
            TurnPersistence.clear(id);
            if (lock != null) lock.close();
        }
    }

    /** Persist before closing so a graceful engine shutdown never loses the last turn. */
    public boolean save() {
        touch();
        return TurnPersistence.save(agent, id, LOGGER, "engine session save");
    }

    public ObjectNode stats() {
        var usage = agent.getCurrentTokenUsage();
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        node.put("turns", transcript.totalTurns());
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
        boolean persisted = TurnPersistence.save(agent, id, LOGGER, "compact");
        if (memoryProvider != null) {
            MemorySectionManager.reloadAgentMemorySection(agent, memoryProvider);
        }
        node.put("compacted", true);
        node.put("persisted", persisted);
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
        boolean persisted = TurnPersistence.save(agent, id, LOGGER, "undo");
        rewindTranscript(index);
        node.put("removed", removed);
        node.put("persisted", persisted);
        return node;
    }

    public ObjectNode records(ObjectNode params) {
        var beforeSeq = params.path("beforeSeq").asLong(transcript.maxSeq() + 1);
        var turns = params.path("turns").asInt(30);
        var maxRecords = params.path("maxRecords").asInt(1000);
        var page = transcript.page(beforeSeq, turns, maxRecords);
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        var array = node.putArray("records");
        for (var record : page.records()) {
            array.add(JsonUtil.OBJECT_MAPPER.valueToTree(record.toMap()));
        }
        node.put("total_turns", page.totalTurns());
        node.put("max_seq", page.maxSeq());
        if (page.nextBeforeSeq() != null) node.put("next_before_seq", page.nextBeforeSeq());
        var partial = node.putArray("partialTurns");
        for (var turn : page.partialTurns()) {
            var item = partial.addObject();
            item.put("turnId", turn.turnId());
            item.put("side", turn.side());
        }
        node.put("oversized", page.oversized());
        node.put("source", page.source());
        return node;
    }

    public ObjectNode recordPayload(ObjectNode params) {
        var seq = params.path("seq").asLong();
        var field = params.path("field").asText(null);
        Integer index = params.has("index") ? params.path("index").asInt() : null;
        var payload = transcript.payload(seq, field, index);
        if (payload == null) {
            throw RpcException.business("RECORD_NOT_FOUND", "transcript payload not found", "seq", String.valueOf(seq));
        }
        var node = JsonUtil.OBJECT_MAPPER.createObjectNode();
        if (payload.text() != null) node.put("text", payload.text());
        if (payload.path() != null) node.put("path", payload.path());
        node.put("size", payload.size());
        return node;
    }

    private void rewindTranscript(int lastUserIndex) {
        if (lastUserIndex < 0) return;
        var start = recorder.lastTurnStartMessageCount();
        if (start == null || lastUserIndex < start) return;
        var usage = recorder.rewindLast();
        if (usage == null || usage.isEmpty()) return;
        agent.subtractTokenUsage(usage.toUsage(), usage.costUsd());
    }
}
