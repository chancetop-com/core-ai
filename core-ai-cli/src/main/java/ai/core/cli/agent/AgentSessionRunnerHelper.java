package ai.core.cli.agent;

import ai.core.llm.domain.ReasoningEffort;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/**
 * @author stephen
 */
public class AgentSessionRunnerHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(AgentSessionRunnerHelper.class);
    private static final Path CONFIG_FILE = Path.of(System.getProperty("user.home"), ".core-ai", "agent.properties");

    // read active provider from agent.properties, merge reasoning_effort into its extra_body
    // returns null on success, or error message on failure
    public static String persistReasoningEffortToExtraBody(ReasoningEffort level) {
        var props = loadAgentPropertiesQuietly();
        String prefix = props == null ? "llm" : resolveProviderPrefix(props);
        return persistReasoningEffortToExtraBody(prefix,
                level == null ? null : level.name().toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Provider-scoped variant used by the app-server: the level is written to {@code <prefix>.request.extra_body}
     * so a direct provider's thinking is independent from the gateway-wide value. A null value removes the key.
     */
    public static String persistReasoningEffortToExtraBody(String prefix, String wireLevel) {
        try {
            var props = loadAgentProperties();
            String safePrefix = prefix == null || prefix.isBlank() ? "llm" : prefix.trim();
            String key = safePrefix + ".request.extra_body";
            String existingJson = props.getProperty(key, "{}").trim();
            if (existingJson.isEmpty()) existingJson = "{}";
            @SuppressWarnings("unchecked")
            var extraMap = (java.util.Map<String, Object>) JsonUtil.fromJson(java.util.Map.class, existingJson);
            if (wireLevel == null || wireLevel.isBlank()) {
                extraMap.remove("reasoning_effort");
            } else {
                extraMap.put("reasoning_effort", wireLevel);
            }
            Path parent = CONFIG_FILE.getParent();
            if (parent == null) return "Invalid config file path";
            Files.createDirectories(parent);
            writePropertyToFile(CONFIG_FILE, key, extraMap.isEmpty() ? null : JsonUtil.toJson(extraMap));
            return null;
        } catch (IOException e) {
            LOGGER.warn("Failed to persist reasoning effort to extra_body", e);
            return "Failed to write config: " + e.getMessage();
        }
    }

    /**
     * Persists {@code agent.max.turn} (the max-turns-per-run limit shared with the CLI) and returns an
     * error message, or null on success. The value is read at engine/CLI start, so a running engine
     * applies it only after a reload.
     */
    public static String persistMaxTurn(int maxTurn) {
        return persistMaxTurnAt(CONFIG_FILE, maxTurn);
    }

    /** Path-injected variant of {@link #persistMaxTurn(int)} for tests. */
    static String persistMaxTurnAt(Path configFile, int maxTurn) {
        try {
            var parent = configFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            writePropertyToFile(configFile, "agent.max.turn", String.valueOf(maxTurn));
            return null;
        } catch (IOException e) {
            LOGGER.warn("Failed to persist max turn to {}", configFile, e);
            return "Failed to write config: " + e.getMessage();
        }
    }

    private static void writePropertyToFile(Path configFile, String key, String value) throws IOException {
        var lines = new java.util.ArrayList<String>();
        boolean found = false;
        if (Files.exists(configFile)) {
            for (String line : Files.readAllLines(configFile)) {
                String stripped = line.stripLeading();
                if (tryReplaceIfProperty(lines, stripped, key, value, found)) {
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        }
        if (!found && value != null) {
            lines.add(key + "=" + value);
        }
        Files.write(configFile, lines);
    }

    private static boolean tryReplaceIfProperty(List<String> lines, String stripped,
                                                String key, String value, boolean alreadyFound) {
        if (alreadyFound || !isPropertyLine(stripped, key)) return false;
        writePropertyLineIfNotNull(lines, key, value);
        return true;
    }

    private static void writePropertyLineIfNotNull(List<String> lines, String key, String value) {
        if (value != null) lines.add(key + "=" + value);
    }

    private static boolean isPropertyLine(String line, String key) {
        int sep = -1;
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '=' || c == ':') {
                sep = i;
                break;
            }
            i++;
        }
        if (sep < 0) return false;
        String lineKey = line.substring(0, sep);
        var sb = new StringBuilder();
        i = 0;
        while (i < lineKey.length()) {
            char c = lineKey.charAt(i);
            if (c == '\\' && i + 1 < lineKey.length()) {
                i++;
                sb.append(lineKey.charAt(i));
            } else {
                sb.append(c);
            }
            i++;
        }
        return sb.toString().equals(key);
    }

    public static ReasoningEffort loadReasoningEffortFromExtraBody() {
        var props = loadAgentPropertiesQuietly();
        String prefix = props == null ? "llm" : resolveProviderPrefix(props);
        return loadReasoningEffortFromExtraBody(prefix, true);
    }

    /**
     * Provider-scoped read. Direct profiles do not inherit the gateway-wide value (their config strips
     * {@code reasoning_effort}); legacy/bootstrap providers keep falling back to {@code llm.*}.
     */
    public static ReasoningEffort loadReasoningEffortFromExtraBody(String prefix, boolean fallbackToLlm) {
        try {
            var props = loadAgentProperties();
            String safePrefix = prefix == null || prefix.isBlank() ? "llm" : prefix.trim();
            String json = props.getProperty(safePrefix + ".request.extra_body");
            if ((json == null || json.isBlank()) && fallbackToLlm && !"llm".equals(safePrefix)) {
                json = props.getProperty("llm.request.extra_body");
            }
            if (json == null || json.isBlank() || "{}".equals(json.trim())) return null;
            @SuppressWarnings("unchecked")
            var extraMap = (java.util.Map<String, Object>) JsonUtil.fromJson(java.util.Map.class, json.trim());
            Object effort = extraMap.get("reasoning_effort");
            if (effort instanceof String s) {
                return parseLevel(s.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read reasoning effort from extra_body", e);
        }
        return null;
    }

    /**
     * Provider prefix for effort storage: the active provider when set, otherwise the base
     * "llm" config prefix that every provider inherits from (AgentBootstrap.setupLLMProperties),
     * so single-provider setups without active.provider still work.
     */
    private static String resolveProviderPrefix(Properties props) {
        var active = props.getProperty("active.provider");
        return active != null && !active.isBlank() ? active : "llm";
    }

    private static Properties loadAgentProperties() throws IOException {
        var props = new Properties();
        if (Files.exists(CONFIG_FILE)) {
            try (InputStream is = Files.newInputStream(CONFIG_FILE)) {
                props.load(is);
            }
        }
        return props;
    }

    private static Properties loadAgentPropertiesQuietly() {
        try {
            return loadAgentProperties();
        } catch (IOException e) {
            LOGGER.warn("Failed to read agent.properties", e);
            return null;
        }
    }

    public static ReasoningEffort parseLevel(String level) {
        return ReasoningEffort.fromString(level);
    }
}
