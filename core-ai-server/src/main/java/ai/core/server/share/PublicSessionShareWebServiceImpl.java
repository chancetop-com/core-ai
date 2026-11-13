package ai.core.server.share;

import ai.core.api.server.share.ListSharedMessagesRequest;
import ai.core.api.server.share.PublicSessionShareWebService;
import ai.core.api.server.share.SharedSessionMessagesResponse;
import ai.core.api.server.share.SharedSessionView;
import ai.core.api.server.trace.ListSpansResponse;
import ai.core.api.server.trace.SpanView;
import ai.core.api.server.trace.TraceView;
import core.framework.inject.Inject;

/**
 * @author Xander
 */
public class PublicSessionShareWebServiceImpl implements PublicSessionShareWebService {
    @Inject
    SessionShareService shareService;
    @Inject
    SessionShareTraceService traceService;

    @Override
    public SharedSessionView get(String token) {
        return shareService.get(token);
    }

    @Override
    public SharedSessionMessagesResponse messages(String token, ListSharedMessagesRequest request) {
        return shareService.messages(token, request);
    }

    @Override
    public TraceView trace(String token, String traceId) {
        return traceService.trace(token, traceId);
    }

    @Override
    public ListSpansResponse spans(String token, String traceId) {
        return traceService.spans(token, traceId);
    }

    @Override
    public SpanView span(String token, String traceId, String spanId) {
        return traceService.span(token, traceId, spanId);
    }
}
