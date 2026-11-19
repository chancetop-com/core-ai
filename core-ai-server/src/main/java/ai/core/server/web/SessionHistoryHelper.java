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
        return records.stream().map(record -> {
            var tool = new Message.ToolCallRecord();
            tool.callId = record.callId;
            tool.name = record.name;
            tool.arguments = record.arguments;
            tool.result = record.result;
            tool.status = record.status;
            tool.toolType = record.toolType;
            tool.durationMs = record.durationMs;
            return tool;
        }).toList();
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
