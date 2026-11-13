package ai.core.server.share;

import ai.core.api.server.session.Message;
import ai.core.api.server.share.SharedSessionArtifact;
import ai.core.api.server.trace.SpanStatusView;
import ai.core.api.server.trace.SpanTypeView;
import ai.core.api.server.trace.SpanView;
import ai.core.api.server.trace.TraceStatusView;
import ai.core.api.server.trace.TraceView;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.SessionShare;
import ai.core.server.domain.SessionShareFile;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Xander
 */
public class SessionShareProjection {
    private static final int MAX_TEXT_LENGTH = 2_000_000;
    private static final String OMITTED_PAYLOAD = "[Payload omitted: exceeds the public sharing size limit]";

    @Inject
    SessionShareFileService fileService;

    public String text(String value, SessionShare share) {
        if (value == null) return null;
        if (value.length() > MAX_TEXT_LENGTH) return OMITTED_PAYLOAD;
        var rewritten = share == null ? value : fileService.rewrite(value, share);
        return SessionShareSanitizer.sanitize(rewritten);
    }

    public Message message(ChatMessage record, SessionShare share) {
        var view = new Message();
        view.role = record.role;
        view.content = text(record.content, share);
        view.thinking = text(record.thinking, share);
        view.seq = record.seq;
        view.traceId = record.traceId != null && share.traceIds != null && share.traceIds.contains(record.traceId) ? record.traceId : null;
        view.timestamp = record.createdAt == null ? null : record.createdAt.toInstant();
        if (record.tools != null) {
            view.tools = record.tools.stream().map(tool -> {
                var result = new Message.ToolCallRecord();
                result.callId = text(tool.callId, share);
                result.name = text(tool.name, share);
                result.arguments = text(tool.arguments, share);
                result.result = text(tool.result, share);
                result.status = text(tool.status, share);
                return result;
            }).toList();
        }
        if (record.events != null) {
            view.events = record.events.stream().map(event -> {
                var result = new Message.EventRecord();
                result.name = text(event.name, share);
                result.data = text(event.data, share);
                result.callId = text(event.callId, share);
                return result;
            }).toList();
        }
        if (record.compression != null) {
            var compression = new Message.CompressionRecord();
            compression.beforeCount = record.compression.beforeCount;
            compression.afterCount = record.compression.afterCount;
            compression.contextTokens = record.compression.contextTokens;
            compression.maxContextTokens = record.compression.maxContextTokens;
            compression.triggerThreshold = record.compression.triggerThreshold;
            view.compression = compression;
        }
        return view;
    }

    public List<SharedSessionArtifact> artifacts(List<SessionShareFile> files, SessionShare share) {
        if (files == null) return List.of();
        return files.stream().map(file -> {
            var view = new SharedSessionArtifact();
            view.fileId = file.id;
            view.fileName = text(file.fileName, share);
            view.contentType = file.contentType;
            view.size = file.size;
            view.title = text(file.title, share);
            view.description = text(file.description, share);
            if (share != null) view.downloadUrl = "/api/public/session-shares/" + share.token + "/files/" + file.id + "/content";
            return view;
        }).toList();
    }

    public TraceView trace(Trace trace, SessionShare share) {
        var view = new TraceView();
        view.id = trace.id;
        view.traceId = trace.traceId;
        view.name = text(trace.name, share);
        view.model = text(trace.model, share);
        view.type = text(trace.type, share);
        view.source = text(trace.source, share);
        view.agentName = text(trace.agentName, share);
        view.status = trace.status == null ? null : TraceStatusView.valueOf(trace.status.name());
        view.errorMessage = text(trace.errorMessage, share);
        view.input = text(trace.input, share);
        view.output = text(trace.output, share);
        view.metadata = attributes(trace.metadata, share);
        view.inputTokens = trace.inputTokens;
        view.outputTokens = trace.outputTokens;
        view.totalTokens = trace.totalTokens;
        view.cachedTokens = trace.cachedTokens;
        view.costUsd = trace.costUsd;
        view.durationMs = trace.durationMs;
        view.startedAt = trace.startedAt;
        view.completedAt = trace.completedAt;
        view.createdAt = trace.createdAt;
        view.updatedAt = trace.updatedAt;
        return view;
    }

    public SpanView span(Span span, SessionShare share) {
        var view = new SpanView();
        view.id = span.id;
        view.traceId = span.traceId;
        view.spanId = span.spanId;
        view.parentSpanId = span.parentSpanId;
        view.name = text(span.name, share);
        view.type = span.type == null ? null : SpanTypeView.valueOf(span.type.name());
        view.model = text(span.model, share);
        view.input = text(span.input, share);
        view.output = text(span.output, share);
        view.inputTokens = span.inputTokens;
        view.outputTokens = span.outputTokens;
        view.cachedTokens = span.cachedTokens;
        view.costUsd = span.costUsd;
        view.costSource = text(span.costSource, share);
        view.pricingModelId = text(span.pricingModelId, share);
        view.inputPricePer1MTokens = span.inputPricePer1MTokens;
        view.outputPricePer1MTokens = span.outputPricePer1MTokens;
        view.durationMs = span.durationMs;
        view.status = span.status == null ? null : SpanStatusView.valueOf(span.status.name());
        view.errorMessage = text(span.errorMessage, share);
        view.attributes = attributes(span.attributes, share);
        view.startedAt = span.startedAt;
        view.completedAt = span.completedAt;
        view.createdAt = span.createdAt;
        return view;
    }

    private Map<String, String> attributes(Map<String, String> values, SessionShare share) {
        if (values == null) return null;
        var json = JsonUtil.toJson(values);
        if (json.length() > MAX_TEXT_LENGTH) return Map.of("notice", OMITTED_PAYLOAD);
        var sanitized = JsonUtil.toMap(text(json, share));
        var result = new LinkedHashMap<String, String>();
        sanitized.forEach((key, value) -> result.put(key, value == null ? null : value instanceof String string ? string : JsonUtil.toJson(value)));
        return result;
    }
}
