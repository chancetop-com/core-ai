package ai.core.server.workflow;

import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Redacts secret-like node config fields before serving another user's public workflow graph. The runtime still uses
 * the stored graph; this is only the read projection.
 *
 * @author Xander
 */
public final class WorkflowGraphSanitizer {
    private static final String REDACTED = "[redacted]";
    private static final Set<String> EXACT_SECRET_KEYS = Set.of(
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "api-key",
        "api_key",
        "apikey",
        "access_token",
        "refresh_token",
        "client_secret",
        "private-key",
        "private_key",
        "proxy-authorization",
        "password"
    );
    private static final List<String> SECRET_KEY_PARTS = List.of("secret", "token");

    private WorkflowGraphSanitizer() {
    }

    public static String sanitize(String graphJson) {
        if (graphJson == null || graphJson.isBlank()) {
            return graphJson;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) JSON.fromJSON(Map.class, graphJson);
        return JSON.toJSON(sanitizeValue(root, ""));
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object value, String key) {
        if (value instanceof Map<?, ?> map) {
            var sanitized = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String childKey = String.valueOf(entry.getKey());
                Object childValue = shouldRedactKey(childKey) ? REDACTED : sanitizeValue(entry.getValue(), childKey);
                sanitized.put(childKey, childValue);
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            var sanitized = new ArrayList<>(list.size());
            for (Object item : list) {
                sanitized.add(sanitizeValue(item, key));
            }
            return sanitized;
        }
        return shouldRedactKey(key) && value != null ? REDACTED : value;
    }

    static boolean shouldRedactKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        if (EXACT_SECRET_KEYS.contains(normalized)) {
            return true;
        }
        return SECRET_KEY_PARTS.stream().anyMatch(normalized::contains);
    }
}
