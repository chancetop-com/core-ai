package ai.core.server.messaging;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.utils.JsonUtil;

import java.util.Locale;
import java.util.Map;

/**
 * Shared JSON codec for session SSE events. The public SSE writer uses {@link JsonUtil}, so the
 * relay and the internal publish endpoint must use the same mapper and the event {@code type}
 * discriminator instead of the class-name framing used by the legacy Redis channel.
 *
 * @author stephen
 */
public final class SseEventCodec {
    public static String toJson(SseBaseEvent event) {
        return JsonUtil.toJson(event);
    }

    public static SseBaseEvent fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        Map<String, Object> map = JsonUtil.toMap(json);
        var rawType = map.get("type");
        if (rawType == null) return null;
        EventType type;
        try {
            type = EventType.valueOf(String.valueOf(rawType).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        var eventClass = SseEventHelper.EVENT_CLASSES.get(type);
        if (eventClass == null) return null;
        return JsonUtil.fromJson(eventClass, json);
    }

    private SseEventCodec() {
    }
}
