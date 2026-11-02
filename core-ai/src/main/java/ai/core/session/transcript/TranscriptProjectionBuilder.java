package ai.core.session.transcript;

import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.session.transcript.TranscriptProjection.BlobSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns one slice of legacy messages into records. Base slices get synthetic ids and negative seqs;
 * gap slices get real ids and are marked synthetic.
 *
 * @author stephen
 */
final class TranscriptProjectionBuilder {
    private static final int MAX_TEXT = 32 * 1024;
    private static final int PREVIEW = 4 * 1024;

    private final List<Message> messages;
    private final int start;
    private final int end;
    private final boolean negative;
    private final boolean synthetic;
    private final long startSeq;
    private final BlobSink sink;
    private final List<TranscriptRecord> out = new ArrayList<>();
    private String currentTurnId;

    TranscriptProjectionBuilder(List<Message> messages, int start, int end, boolean negative,
                                long startSeq, BlobSink sink) {
        this.messages = messages;
        this.start = start;
        this.end = end;
        this.negative = negative;
        this.synthetic = !negative;
        this.startSeq = startSeq;
        this.sink = sink;
    }

    List<TranscriptRecord> build() {
        for (var i = start; i < end; i++) {
            var message = messages.get(i);
            switch (message.role) {
                case USER -> user(message, i);
                case ASSISTANT -> assistant(message, i);
                case TOOL -> tool(message, i);
                default -> {
                }
            }
        }
        closeTurn(end);
        return out;
    }

    private void user(Message message, int index) {
        var text = TranscriptProjection.joinedText(message);
        if (TranscriptProjection.isSystemReminder(text)) {
            notice("system", text);
            return;
        }
        if (TranscriptProjection.isTaskNotification(text)) {
            newTurn(TranscriptKinds.SOURCE_TASK_NOTE, text, index);
            return;
        }
        newTurn(TranscriptKinds.SOURCE_USER, text, index);
    }

    private void newTurn(String source, String text, int index) {
        closeTurn(index);
        var turnId = negative ? TranscriptProjection.turnHash(index, text) : "t" + (startSeq + out.size());
        var record = add(TranscriptKinds.TURN_START, turnId);
        record.put("source", source);
        var inputs = new ArrayList<Map<String, Object>>();
        var input = new LinkedHashMap<String, Object>();
        putInput(record, input, text);
        inputs.add(input);
        record.put("inputs", inputs);
        record.put("message_count_before", (long) index);
        currentTurnId = turnId;
    }

    private void closeTurn(int index) {
        if (currentTurnId == null) return;
        var record = add(TranscriptKinds.TURN_COMPLETE, currentTurnId);
        record.put("message_count", (long) index);
        currentTurnId = null;
    }

    private void assistant(Message message, int index) {
        ensureTurn(index);
        var reasoning = message.reasoningContent;
        if (reasoning != null && !reasoning.isBlank()) {
            var record = add(TranscriptKinds.REASONING, currentTurnId);
            putText(record, "reasoning", reasoning);
        }
        appendText(message);
        if (message.toolCalls == null) return;
        for (var call : message.toolCalls) {
            toolStart(call);
        }
    }

    private void appendText(Message message) {
        var text = message.getJoinedTextContent();
        if (text == null || text.isBlank()) return;
        var last = out.isEmpty() ? null : out.getLast();
        if (last != null && TranscriptKinds.ASSISTANT_TEXT.equals(last.kind)
            && Objects.equals(last.turnId, currentTurnId) && last.text("text") != null) {
            last.put("text", last.text("text") + "\n\n" + text);
            return;
        }
        var record = add(TranscriptKinds.ASSISTANT_TEXT, currentTurnId);
        putText(record, "text", text);
    }

    private void toolStart(FunctionCall call) {
        if (call == null || call.function == null) return;
        if (TranscriptProjection.isCompressionTool(call.function.name)) return;
        var record = add(TranscriptKinds.TOOL_START, currentTurnId);
        record.put("callId", call.id);
        record.put("toolName", call.function.name);
        putText(record, "arguments", call.function.arguments);
    }

    private void tool(Message message, int index) {
        ensureTurn(index);
        var text = message.getJoinedTextContent();
        if (TranscriptProjection.isCompressionTool(message.name)) {
            var notice = add(TranscriptKinds.NOTICE, currentTurnId);
            notice.put("level", "compression");
            putText(notice, "text", text);
            return;
        }
        var record = add(TranscriptKinds.TOOL_RESULT, currentTurnId);
        record.put("callId", message.toolCallId);
        record.put("toolName", message.name);
        record.put("status", "unknown");
        putText(record, "result", text);
    }

    private void notice(String level, String text) {
        var record = add(TranscriptKinds.NOTICE, currentTurnId);
        record.put("level", level);
        putText(record, "text", text);
    }

    private void ensureTurn(int index) {
        if (currentTurnId == null) newTurn(TranscriptKinds.SOURCE_USER, null, index);
    }

    private void putInput(TranscriptRecord record, Map<String, Object> input, String text) {
        if (text == null) return;
        if (text.length() <= MAX_TEXT) {
            input.put("text", text);
            return;
        }
        if (sink != null) {
            input.put("text_ref", sink.write(record.seq, "inputs", 0, text));
            input.put("text_preview", text.substring(0, PREVIEW));
            return;
        }
        input.put("text_preview", text.substring(0, PREVIEW));
        input.put("text_truncated", true);
    }

    private void putText(TranscriptRecord record, String field, String value) {
        if (value == null) return;
        if (value.length() <= MAX_TEXT) {
            record.put(field, value);
            return;
        }
        if (sink != null) {
            record.put(field + "_ref", sink.write(record.seq, field, 0, value));
            record.put(field + "_preview", value.substring(0, PREVIEW));
            return;
        }
        record.put(field + "_preview", value.substring(0, PREVIEW));
        record.put(field + "_truncated", true);
    }

    private TranscriptRecord add(String kind, String turnId) {
        var record = new TranscriptRecord();
        record.kind = kind;
        record.ts = System.currentTimeMillis();
        record.turnId = turnId;
        if (synthetic) record.synthetic = true;
        if (!negative) record.seq = startSeq + out.size();
        out.add(record);
        return record;
    }
}
