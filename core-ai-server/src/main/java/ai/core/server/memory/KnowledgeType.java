package ai.core.server.memory;

import java.util.List;
import java.util.Locale;

/**
 * Layer 1 (knowledge) types an agent may write through extract_memory_now. The value is stored verbatim in
 * {@link AgentMemory#type}, so the names are part of the stored data and must not be renamed.
 *
 * @author Xander
 */
public enum KnowledgeType {
    USER_PREFERENCE,
    DOMAIN_KNOWLEDGE,
    GOTCHA;

    public static List<String> names() {
        return List.of(USER_PREFERENCE.name(), DOMAIN_KNOWLEDGE.name(), GOTCHA.name());
    }

    /** Unknown or missing values fall back to {@link #DOMAIN_KNOWLEDGE}, the neutral knowledge type. */
    public static KnowledgeType resolve(String value) {
        var parsed = fromString(value);
        return parsed != null ? parsed : DOMAIN_KNOWLEDGE;
    }

    public static KnowledgeType fromString(String value) {
        if (value == null || value.isBlank()) return null;
        var normalized = value.trim().toUpperCase(Locale.ROOT);
        for (var type : values()) {
            if (type.name().equals(normalized)) return type;
        }
        return null;
    }
}
