package ai.core.server.trigger.filter;

import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filters Slack (or other webhook) events before running the agent,
 * so that only matching events consume tokens.
 *
 * <p>Filter rules are stored in {@code actionConfig} as comma-separated values:
 * <ul>
 *   <li>{@code filter_event_types} — only process events whose {@code event.type} is in this list</li>
 *   <li>{@code filter_ignore_subtypes} — skip events whose {@code event.subtype} is in this list</li>
 *   <li>{@code filter_channels} — only process events whose {@code event.channel} is in this list</li>
 * </ul>
 *
 * @author stephen
 */
public class EventFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(EventFilter.class);

    private final Set<String> allowedEventTypes;
    private final Set<String> ignoredSubtypes;
    private final Set<String> allowedChannels;

    public EventFilter(Map<String, String> actionConfig) {
        this.allowedEventTypes = parseSet(actionConfig, "filter_event_types");
        this.ignoredSubtypes = parseSet(actionConfig, "filter_ignore_subtypes");
        this.allowedChannels = parseSet(actionConfig, "filter_channels");
    }

    /**
     * @return true if the payload matches the configured filter rules (or if no filter is configured),
     * false if the event should be skipped
     */
    public boolean matches(String payload) {
        if (!hasAnyFilter()) return true;

        Map<String, Object> parsed = parsePayload(payload);
        if (parsed == null) {
            LOGGER.warn("cannot parse payload for filtering, allowing through");
            return true;
        }

        // For Slack Events API, the event details are nested under "event"
        @SuppressWarnings("unchecked")
        var event = (Map<String, Object>) parsed.get("event");
        if (event == null) {
            // Not a Slack event_callback (e.g. url_verification), let it through
            return true;
        }

        return matchesEventType(event) && matchesSubtype(event) && matchesChannel(event);
    }

    private boolean matchesEventType(Map<String, Object> event) {
        if (allowedEventTypes.isEmpty()) return true;
        var type = stringField(event, "type");
        if (type == null) return false;
        if (allowedEventTypes.contains(type)) return true;
        LOGGER.debug("event type '{}' not in allowed list {}, skipping", type, allowedEventTypes);
        return false;
    }

    private boolean matchesSubtype(Map<String, Object> event) {
        if (ignoredSubtypes.isEmpty()) return true;
        var subtype = stringField(event, "subtype");
        if (subtype == null) return true; // no subtype = nothing to ignore
        if (ignoredSubtypes.contains(subtype)) {
            LOGGER.debug("event subtype '{}' is in ignore list {}, skipping", subtype, ignoredSubtypes);
            return false;
        }
        return true;
    }

    private boolean matchesChannel(Map<String, Object> event) {
        if (allowedChannels.isEmpty()) return true;
        var channel = stringField(event, "channel");
        if (channel == null) return false;
        if (allowedChannels.contains(channel)) return true;
        LOGGER.debug("event channel '{}' not in allowed list {}, skipping", channel, allowedChannels);
        return false;
    }

    private boolean hasAnyFilter() {
        return !allowedEventTypes.isEmpty() || !ignoredSubtypes.isEmpty() || !allowedChannels.isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            return (Map<String, Object>) JSON.fromJSON(Map.class, payload);
        } catch (Exception e) {
            LOGGER.warn("failed to parse payload JSON", e);
            return null;
        }
    }

    private static String stringField(Map<String, Object> map, String key) {
        var value = map.get(key);
        return value instanceof String s ? s : null;
    }

    private static Set<String> parseSet(Map<String, String> config, String key) {
        if (config == null) return Set.of();
        var value = config.get(key);
        if (value == null || value.isBlank()) return Set.of();
        return List.of(value.split(",")).stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
