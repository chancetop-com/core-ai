package ai.core.server.web;

import ai.core.api.server.session.Message;
import ai.core.api.server.session.SessionArtifact;
import ai.core.api.server.session.SessionHistoryResponse;
import ai.core.server.session.ChatMessageService;

import java.util.ArrayList;

/**
 * Builds the session history response (messages + artifacts) from the display-layer persistence.
 * Extracted from the session web service to keep it under the file length limit.
 *
 * @author stephen
 */
final class SessionHistoryHelper {
    static SessionHistoryResponse build(ChatMessageService chatMessageService, String sessionId) {
        var records = chatMessageService.history(sessionId);
        var sessionArtifacts = chatMessageService.artifacts(sessionId);
        var messages = new ArrayList<Message>(records.size());
        for (var record : records) {
            var msg = new Message();
            msg.role = record.role;
            msg.content = record.content;
            msg.thinking = record.thinking;
            msg.seq = record.seq;
            msg.traceId = record.traceId;
            msg.timestamp = record.createdAt != null ? record.createdAt.toInstant() : null;
            if (record.tools != null) {
                msg.tools = record.tools.stream().map(t -> {
                    var r = new Message.ToolCallRecord();
                    r.callId = t.callId;
                    r.name = t.name;
                    r.arguments = t.arguments;
                    r.result = t.result;
                    r.status = t.status;
                    return r;
                }).toList();
            }
            if (record.sandbox != null) {
                msg.sandbox = toSandboxRecord(record.sandbox);
            }
            messages.add(msg);
        }
        var response = new SessionHistoryResponse();
        response.messages = messages;
        if (sessionArtifacts != null && !sessionArtifacts.isEmpty()) {
            response.artifacts = sessionArtifacts.stream().map(a -> {
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
        return response;
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

    private SessionHistoryHelper() {
    }
}
