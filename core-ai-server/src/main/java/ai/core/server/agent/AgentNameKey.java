package ai.core.server.agent;

import java.util.Locale;

public final class AgentNameKey {
    public static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private AgentNameKey() {
    }
}
