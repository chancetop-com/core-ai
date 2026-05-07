package ai.core.server.messaging;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseCompressionEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SsePlanUpdateEvent;
import ai.core.api.server.session.sse.SseReasoningChunkEvent;
import ai.core.api.server.session.sse.SseSandboxEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTextChunkEvent;
import ai.core.api.server.session.sse.SseToolApprovalRequestEvent;
import ai.core.api.server.session.sse.SseEnvironmentOutputChunkEvent;
import ai.core.api.server.session.sse.SseToolResultEvent;
import ai.core.api.server.session.sse.SseToolStartEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public class SseEventHelper {
    private static final Map<Class<? extends SseBaseEvent>, EventType> TYPE_MAP = new HashMap<>();

    static {
        TYPE_MAP.put(SseTextChunkEvent.class, EventType.TEXT_CHUNK);
        TYPE_MAP.put(SseReasoningChunkEvent.class, EventType.REASONING_CHUNK);
        TYPE_MAP.put(SseToolStartEvent.class, EventType.TOOL_START);
        TYPE_MAP.put(SseToolResultEvent.class, EventType.TOOL_RESULT);
        TYPE_MAP.put(SseToolApprovalRequestEvent.class, EventType.TOOL_APPROVAL_REQUEST);
        TYPE_MAP.put(SseTurnCompleteEvent.class, EventType.TURN_COMPLETE);
        TYPE_MAP.put(SseErrorEvent.class, EventType.ERROR);
        TYPE_MAP.put(SseStatusChangeEvent.class, EventType.STATUS_CHANGE);
        TYPE_MAP.put(SsePlanUpdateEvent.class, EventType.PLAN_UPDATE);
        TYPE_MAP.put(SseCompressionEvent.class, EventType.COMPRESSION);
        TYPE_MAP.put(SseSandboxEvent.class, EventType.SANDBOX);
        TYPE_MAP.put(SseEnvironmentOutputChunkEvent.class, EventType.ENVIRONMENT_OUTPUT_CHUNK);
    }

    /**
     * Mapping from EventType to concrete class for event deserialization.
     */
    public static final Map<EventType, Class<? extends SseBaseEvent>> EVENT_CLASSES = Map.ofEntries(
            Map.entry(EventType.TEXT_CHUNK, SseTextChunkEvent.class),
            Map.entry(EventType.REASONING_CHUNK, SseReasoningChunkEvent.class),
            Map.entry(EventType.REASONING_COMPLETE, SseReasoningChunkEvent.class),
            Map.entry(EventType.TOOL_START, SseToolStartEvent.class),
            Map.entry(EventType.TOOL_RESULT, SseToolResultEvent.class),
            Map.entry(EventType.TOOL_APPROVAL_REQUEST, SseToolApprovalRequestEvent.class),
            Map.entry(EventType.TURN_COMPLETE, SseTurnCompleteEvent.class),
            Map.entry(EventType.ERROR, SseErrorEvent.class),
            Map.entry(EventType.STATUS_CHANGE, SseStatusChangeEvent.class),
            Map.entry(EventType.PLAN_UPDATE, SsePlanUpdateEvent.class),
            Map.entry(EventType.COMPRESSION, SseCompressionEvent.class),
            Map.entry(EventType.SANDBOX, SseSandboxEvent.class),
            Map.entry(EventType.ENVIRONMENT_OUTPUT_CHUNK, SseEnvironmentOutputChunkEvent.class)
    );

    public static void initEvent(SseBaseEvent event, String sessionId) {
        event.sessionId = sessionId;
        event.timestamp = ZonedDateTime.now();
        var type = TYPE_MAP.get(event.getClass());
        if (type != null) {
            event.type = type;
        }
    }

    /**
     * Reverse mapping: concrete class name → EventType for event serialization.
     */
    public static EventType eventTypeFor(SseBaseEvent event) {
        return TYPE_MAP.get(event.getClass());
    }
}
