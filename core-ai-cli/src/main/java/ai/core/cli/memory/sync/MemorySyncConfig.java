package ai.core.cli.memory.sync;

import ai.core.bootstrap.PropertySource;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Configuration for Neo4j-backed memory sync.
 *
 * <p>Values are read from agent.properties. The config is immutable once constructed.
 * Default {@code enabled=false} means sync is opt-in per user.
 *
 * <p>Fields serve as independent retrieval dimensions — RESTORE queries filter by
 * ({@code system}, {@code project}) without string parsing.
 */
public record MemorySyncConfig(
        boolean enabled,
        String uri,
        String username,
        String password,
        String system) {

    private static final String DEFAULT_URI = "bolt://localhost:7687";
    private static final String DEFAULT_USERNAME = "neo4j";

    public static MemorySyncConfig disabled() {
        return new MemorySyncConfig(false, null, null, null, null);
    }

    public static MemorySyncConfig load(PropertySource props) {
        boolean enabled = props.property("agent.memory.neo4j.enabled")
                .map(Boolean::parseBoolean)
                .orElse(false);
        if (!enabled) {
            return disabled();
        }
        String uri = props.property("agent.memory.neo4j.uri").orElse(DEFAULT_URI);
        String username = props.property("agent.memory.neo4j.username").orElse(DEFAULT_USERNAME);
        String password = props.property("agent.memory.neo4j.password").orElse("");
        String system = props.property("agent.memory.neo4j.system")
                .orElseGet(MemorySyncConfig::hostname);
        return new MemorySyncConfig(true, uri, username, password, system);
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
