package ai.core.session.transcript;

import ai.core.agent.AttachedContent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.ApprovalDecision;
import ai.core.api.server.session.BatchToolStartEvent;
import ai.core.api.server.session.CompressionEvent;
import ai.core.api.server.session.CustomEvent;
import ai.core.api.server.session.EnvironmentOutputChunkEvent;
import ai.core.api.server.session.ErrorEvent;
import ai.core.api.server.session.PlanUpdateEvent;
import ai.core.api.server.session.ReasoningCompleteEvent;
import ai.core.api.server.session.SandboxEvent;
import ai.core.api.server.session.TaskStatusEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.ToolResultEvent;
import ai.core.api.server.session.ToolStartEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The single writer of a session transcript. It owns seq/turn_id allocation, text folding and the
 * append lock; turns end only through {@link #completeTurn} / {@link #failTurn}, never through the
 * listener callbacks.
 *
 * @author stephen
 */
@SuppressWarnings({"this-escape", "PMD.AvoidSynchronizedAtMethodLevel"})
public class TranscriptRecorder implements AgentEventListener {
    public static final String CONTEXT_KEY = "__transcript_recorder";

    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptRecorder.class);

    @SuppressWarnings("PMD.SystemPrintln")
    private static void debug(TranscriptRecord record) {
        if (!Boolean.getBoolean("core.ai.transcript.debug")) return;
        System.err.println("[transcript] " + record.seq + " " + record.kind
            + (record.turnId == null ? "" : " " + record.turnId));
    }

    private static long number(TranscriptRecord record, String field) {
        var value = record.number(field);
        return value == null ? 0 : value;
    }

    private static double decimal(TranscriptRecord record, String field) {
        var value = record.decimal(field);
        return value == null ? 0 : value;
    }

    private final TranscriptStore store;
    private final TranscriptMetadata metadata;
    private final LongSupplier messageCount;
    private final Consumer<TranscriptRecord> sink;
    private final TranscriptEventWriter writer = new TranscriptEventWriter(this);
    private long nextSeq;
    private String currentTurnId;
    private ErrorEvent terminalError;
    private boolean closed;

    public TranscriptRecorder(TranscriptStore store, TranscriptMetadata metadata,
                              LongSupplier messageCount, Consumer<TranscriptRecord> sink) {
        this.store = store;
        this.metadata = metadata;
        this.messageCount = messageCount;
        this.sink = sink;
        this.nextSeq = store.maxSeq() + 1;
    }

    /**
     * Reconciles the file with {@code .data}: base projection, dangling recovery, gap materialization
     * or reset. Returns the usage the host must seed on the node.
     */
    public synchronized TranscriptUsage sessionStart(List<Message> messages) {
        var result = new TranscriptStartup(store, metadata, messages).start();
        for (var record : result.append()) {
            store.append(record);
            debug(record);
            notifySink(record);
        }
        nextSeq = store.maxSeq() + 1;
        return result.seed();
    }

    public synchronized void beginTurn(List<TranscriptInput> inputs, String source,
                                       String model, String thinkingLevel) {
        writer.flushLocked();
        var seq = nextSeq;
        var turnId = "t" + seq;
        var record = writeLocked(TranscriptKinds.TURN_START, turnId);
        record.put("source", source);
        var values = new ArrayList<Map<String, Object>>();
        for (var i = 0; i < inputs.size(); i++) {
            var input = inputs.get(i);
            var value = new LinkedHashMap<String, Object>();
            TranscriptPayloads.putInput(store, record, value, input.text(), i);
            value.put("attachments", attachments(seq, input.attachments()));
            value.put("client_turn_id", input.clientTurnId());
            values.add(value);
        }
        record.put("inputs", values);
        record.put("model", model);
        record.put("thinking_level", thinkingLevel);
        record.put("message_count_before", messageCount.getAsLong());
        commitLocked(record);
        currentTurnId = turnId;
    }

    public synchronized long steerPending(TranscriptInput input) {
        writer.flushLocked();
        var record = writeLocked(TranscriptKinds.STEER, currentTurnId);
        record.put("state", TranscriptKinds.STEER_PENDING);
        TranscriptPayloads.putText(store, record, "text", input.text());
        record.put("attachments", attachments(record.seq, input.attachments()));
        record.put("client_turn_id", input.clientTurnId());
        commitLocked(record);
        return record.seq;
    }

    public synchronized void steerCommit(long refSeq) {
        var record = writeLocked(TranscriptKinds.STEER, currentTurnId);
        record.put("state", TranscriptKinds.STEER_COMMITTED);
        record.put("ref_seq", refSeq);
        commitLocked(record);
    }

    public synchronized void steerCancel(long refSeq) {
        var record = writeLocked(TranscriptKinds.STEER, null);
        record.put("state", TranscriptKinds.STEER_CANCELLED);
        record.put("ref_seq", refSeq);
        commitLocked(record);
    }

    public synchronized void approval(String callId, ApprovalDecision decision) {
        var record = writeLocked(TranscriptKinds.TOOL_APPROVAL, currentTurnId);
        record.put("callId", callId);
        record.put("decision", decision == null ? null : decision.name());
        commitLocked(record);
    }

    public synchronized void completeTurn(TurnCompleteEvent event) {
        if (currentTurnId == null) return;
        writer.flushLocked();
        var record = writeLocked(TranscriptKinds.TURN_COMPLETE, currentTurnId);
        TranscriptPayloads.putText(store, record, "output", event.output);
        record.put("cancelled", event.cancelled);
        record.put("max_turns_reached", event.maxTurnsReached);
        record.put("duration_ms", event.durationMs);
        record.put("input_tokens", event.inputTokens);
        record.put("output_tokens", event.outputTokens);
        record.put("cached_tokens", event.cachedTokens);
        record.put("cost_usd", event.costUsd);
        record.put("budget_exhausted", event.budgetExhausted);
        record.put("budget_level", event.budgetLevel);
        record.put("budget_binding", event.budgetBinding);
        record.put("budget_tokens_spent", event.budgetTokensSpent);
        record.put("budget_cost_spent", event.budgetCostSpent);
        record.put("budget_cost_estimated", event.budgetCostEstimated);
        record.put("message_count", messageCount.getAsLong());
        commitLocked(record);
        currentTurnId = null;
    }

    public synchronized void failTurn(ErrorEvent event, TranscriptUsage usage) {
        writer.flushLocked();
        var record = writeLocked(TranscriptKinds.ERROR, currentTurnId);
        record.put("terminal", Boolean.TRUE);
        record.put("message", event.message);
        record.put("detail", event.detail);
        record.put("message_count", messageCount.getAsLong());
        putUsage(record, usage);
        commitLocked(record);
        terminalError = event;
        currentTurnId = null;
    }

    /** Writes a rewind for the last file turn and returns its usage; null when there is no file turn. */
    public synchronized TranscriptUsage rewindLast() {
        var turn = lastTurnStart();
        if (turn == null) return null;
        var seq = TranscriptRewind.turnSeq(turn.turnId);
        var usage = turnUsage(seq);
        var record = writeLocked(TranscriptKinds.REWIND, null);
        record.put("to_seq", seq);
        record.put("message_count", messageCount.getAsLong());
        commitLocked(record);
        return usage;
    }

    public synchronized Long lastTurnStartMessageCount() {
        var turn = lastTurnStart();
        return turn == null ? null : turn.number("message_count_before");
    }

    public synchronized void close() {
        if (closed) return;
        writer.flushLocked();
        if (currentTurnId != null) {
            var record = writeLocked(TranscriptKinds.TURN_COMPLETE, currentTurnId);
            record.put("cancelled", Boolean.TRUE);
            record.put("message_count", messageCount.getAsLong());
            commitLocked(record);
            currentTurnId = null;
        }
        closed = true;
        store.close();
    }

    public TranscriptStore store() {
        return store;
    }

    public synchronized boolean hasOpenTurn() {
        return currentTurnId != null;
    }

    @Override
    public void onTextChunk(TextChunkEvent event) {
        synchronized (this) {
            writer.onTextChunkLocked(event);
        }
    }

    @Override
    public void onReasoningComplete(ReasoningCompleteEvent event) {
        synchronized (this) {
            writer.onReasoningCompleteLocked(event);
        }
    }

    @Override
    public void onToolStart(ToolStartEvent event) {
        synchronized (this) {
            writer.onToolStartLocked(event);
        }
    }

    @Override
    public void onToolResult(ToolResultEvent event) {
        synchronized (this) {
            writer.onToolResultLocked(event);
        }
    }

    @Override
    public void onToolApprovalRequest(ToolApprovalRequestEvent event) {
        synchronized (this) {
            writer.onToolApprovalRequestLocked(event);
        }
    }

    @Override
    public void onPlanUpdate(PlanUpdateEvent event) {
        synchronized (this) {
            writer.onPlanUpdateLocked(event);
        }
    }

    @Override
    public void onCompression(CompressionEvent event) {
        synchronized (this) {
            writer.onCompressionLocked(event);
        }
    }

    @Override
    public void onSandbox(SandboxEvent event) {
        synchronized (this) {
            writer.onSandboxLocked(event);
        }
    }

    @Override
    public void onBatchToolStart(BatchToolStartEvent event) {
        synchronized (this) {
            writer.onBatchToolStartLocked(event);
        }
    }

    @Override
    public void onTaskStatus(TaskStatusEvent event) {
        synchronized (this) {
            writer.onTaskStatusLocked(event);
        }
    }

    @Override
    public void onCustomEvent(CustomEvent event) {
        synchronized (this) {
            writer.onCustomEventLocked(event);
        }
    }

    @Override
    public void onEnvironmentOutput(EnvironmentOutputChunkEvent event) {
        synchronized (this) {
            writer.onEnvironmentOutputLocked(event);
        }
    }

    @Override
    public void onError(ErrorEvent event) {
        synchronized (this) {
            writer.onErrorLocked(event);
        }
    }

    TranscriptRecord writeLocked(String kind, String turnId) {
        var record = new TranscriptRecord();
        record.seq = nextSeq++;
        record.ts = System.currentTimeMillis();
        record.kind = kind;
        record.turnId = turnId;
        return record;
    }

    void commitLocked(TranscriptRecord record) {
        store.append(record);
        debug(record);
        notifySink(record);
    }

    synchronized String currentTurnId() {
        return currentTurnId;
    }

    ErrorEvent terminalError() {
        return terminalError;
    }

    void clearTerminalError() {
        terminalError = null;
    }

    private List<Map<String, Object>> attachments(long seq, List<AttachedContent> attachments) {
        return TranscriptPayloads.attachments(store, seq, attachments);
    }

    private void notifySink(TranscriptRecord record) {
        if (sink == null) return;
        try {
            sink.accept(record);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to notify transcript record, sessionId={}, error={}", store.sessionId(), e.getMessage());
        }
    }

    private void putUsage(TranscriptRecord record, TranscriptUsage usage) {
        if (usage == null) return;
        record.put("input_tokens", usage.inputTokens());
        record.put("output_tokens", usage.outputTokens());
        record.put("cached_tokens", usage.cachedTokens());
        record.put("cost_usd", usage.costUsd());
    }

    private TranscriptRecord lastTurnStart() {
        var rewinds = TranscriptRewind.of(store.records());
        TranscriptRecord last = null;
        for (var record : store.records()) {
            if (!TranscriptKinds.TURN_START.equals(record.kind)) continue;
            if (TranscriptRewind.turnSeq(record.turnId) < 0 || rewinds.covers(record.turnId)) continue;
            last = record;
        }
        return last;
    }

    private TranscriptUsage turnUsage(long turnSeq) {
        var turnId = "t" + turnSeq;
        return store.records().stream()
            .filter(record -> turnId.equals(record.turnId)
                && (TranscriptKinds.TURN_COMPLETE.equals(record.kind) || TranscriptKinds.ERROR.equals(record.kind)))
            .findFirst()
            .map(this::usage)
            .orElse(TranscriptUsage.EMPTY);
    }

    private TranscriptUsage usage(TranscriptRecord record) {
        return new TranscriptUsage(number(record, "input_tokens"), number(record, "output_tokens"),
            number(record, "cached_tokens"), decimal(record, "cost_usd"));
    }

}
