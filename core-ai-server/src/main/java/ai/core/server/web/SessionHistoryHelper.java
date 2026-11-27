package ai.core.server.web;

import ai.core.api.server.session.Message;
import ai.core.api.server.session.SessionArtifact;
import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.server.session.ChatMessageService;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the session history response (messages + artifacts) from the display-layer persistence.
 * Extracted from the session web service to keep it under the file length limit.
 *
 * @author stephen
 */
public final class SessionHistoryHelper {
    // Tool payloads dominate the stored transcript (UAT: 761 MB of 782 MB) and a single result
    // can exceed 1 MB. The transcript only needs a readable prefix; the full payload stays in the
    // message store and the run trace.
    private static final int MAX_TOOL_ARGUMENTS_CHARS = 4_000;
    private static final int MAX_TOOL_RESULT_CHARS = 16_000;
    private static final int MAX_TOOL_PAYLOAD_CHARS_PER_MESSAGE = 64_000;

    public static SessionHistoryResponse build(ChatMessageService chatMessageService, String sessionId) {
        var response = new SessionHistoryResponse();
        response.messages = toMessages(chatMessageService.history(sessionId));
        response.artifacts = toArtifacts(chatMessageService.artifacts(sessionId));
        return response;
    }

    public static List<Message> toMessages(List<ai.core.server.domain.ChatMessage> records) {
        var messages = new ArrayList<Message>(records.size());
        for (var record : records) {
            var msg = new Message();
            msg.role = record.role;
            msg.content = record.content;
            msg.thinking = record.thinking;
            if (record.thinkingBlocks != null) {
                msg.thinkingBlocks = toThinkingBlocks(record.thinkingBlocks);
            }
            msg.seq = record.seq;
            msg.traceId = record.traceId;
            msg.timestamp = record.createdAt != null ? record.createdAt.toInstant() : null;
            if (record.tools != null) {
                msg.tools = toToolRecords(record.tools);
            }
            if (record.sandbox != null) {
                msg.sandbox = toSandboxRecord(record.sandbox);
            }
            if (record.compression != null) {
                msg.compression = toCompressionRecord(record.compression);
            }
            if (record.events != null) {
                msg.events = toEventRecords(record.events);
            }
            messages.add(msg);
        }
        return messages;
    }

    public static List<SessionArtifact> toArtifacts(List<ai.core.server.domain.AgentRunArtifact> sessionArtifacts) {
        if (sessionArtifacts == null || sessionArtifacts.isEmpty()) return null;
        return sessionArtifacts.stream().map(a -> {
            var v = new SessionArtifact();
            v.fileId = a.fileId;
            v.fileName = a.fileName;
            v.contentType = a.contentType;
            v.size = a.size;
            v.title = a.title;
            v.description = a.description;
            return v;
        }).toList();
    }

    private static List<Message.ThinkingBlock> toThinkingBlocks(
            List<ai.core.server.domain.ChatMessage.ThinkingRecord> records) {
        return records.stream().map(record -> {
            var block = new Message.ThinkingBlock();
            block.text = record.text;
            block.durationMs = record.durationMs;
            return block;
        }).toList();
    }

    private static List<Message.ToolCallRecord> toToolRecords(
            List<ai.core.server.domain.ChatMessage.ToolCallRecord> records) {
        var budget = MAX_TOOL_PAYLOAD_CHARS_PER_MESSAGE;
        var tools = new ArrayList<Message.ToolCallRecord>(records.size());
        for (var record : records) {
            var tool = new Message.ToolCallRecord();
            tool.callId = record.callId;
            tool.name = record.name;
            var argumentsLimit = Math.min(MAX_TOOL_ARGUMENTS_CHARS, Math.max(budget, 0));
            tool.arguments = truncate(record.arguments, argumentsLimit);
            budget = Math.max(budget - keptChars(tool.arguments, argumentsLimit), 0);
            var resultLimit = Math.min(MAX_TOOL_RESULT_CHARS, Math.max(budget, 0));
            tool.result = truncate(record.result, resultLimit);
            budget = Math.max(budget - keptChars(tool.result, resultLimit), 0);
            tool.status = record.status;
            tool.toolType = record.toolType;
            tool.parentCallId = record.parentCallId;
            tool.durationMs = record.durationMs;
            tools.add(tool);
        }
        return tools;
    }

    private static int keptChars(String value, int limit) {
        return value == null ? 0 : Math.min(value.length(), limit);
    }

    private static String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit)
                + "\n\n… [" + (value.length() - limit) + " characters truncated; full output is available in the run trace]";
    }

    private static Message.SandboxRecord toSandboxRecord(
            ai.core.server.domain.ChatMessage.SandboxRecord record) {
        var sandbox = new Message.SandboxRecord();
        sandbox.sandboxId = record.sandboxId;
        sandbox.sandboxType = record.sandboxType;
        sandbox.message = record.message;
        sandbox.durationMs = record.durationMs;
        sandbox.hostname = record.hostname;
        sandbox.ip = record.ip;
        sandbox.image = record.image;
        return sandbox;
    }

    private static Message.CompressionRecord toCompressionRecord(
            ai.core.server.domain.ChatMessage.CompressionRecord record) {
        var compression = new Message.CompressionRecord();
        compression.beforeCount = record.beforeCount;
        compression.afterCount = record.afterCount;
        compression.contextTokens = record.contextTokens;
        compression.afterContextTokens = record.afterContextTokens;
        compression.maxContextTokens = record.maxContextTokens;
        compression.triggerThreshold = record.triggerThreshold;
        return compression;
    }

    private static List<Message.EventRecord> toEventRecords(
            List<ai.core.server.domain.ChatMessage.EventRecord> records) {
        return records.stream().map(record -> {
            var event = new Message.EventRecord();
            event.name = record.name;
            event.data = record.data;
            event.card = record.card;
            event.callId = record.callId;
            return event;
        }).toList();
    }

    private SessionHistoryHelper() {
    }
}
