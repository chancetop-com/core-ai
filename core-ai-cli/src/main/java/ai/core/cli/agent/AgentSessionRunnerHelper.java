package ai.core.cli.agent;

import ai.core.llm.domain.ReasoningEffort;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try {
            var props = loadAgentProperties();
            String activeProvider = props.getProperty("active.provider");
            if (activeProvider == null || activeProvider.isBlank()) {
                return "No active.provider in agent.properties";
            }
            String key = activeProvider + ".request.extra_body";
            String existingJson = props.getProperty(key, "{}").trim();
            if (existingJson.isEmpty()) existingJson = "{}";
            @SuppressWarnings("unchecked")
            var extraMap = (java.util.Map<String, Object>) JsonUtil.fromJson(java.util.Map.class, existingJson);
            if (level == null) {
                extraMap.remove("reasoning_effort");
            } else {
                extraMap.put("reasoning_effort", level.name().toLowerCase(java.util.Locale.ROOT));
            }
            Files.createDirectories(CONFIG_FILE.getParent());
            writePropertyToFile(key, extraMap.isEmpty() ? null : JsonUtil.toJson(extraMap));
            return null;
        } catch (IOException e) {
            LOGGER.warn("Failed to persist reasoning effort to extra_body", e);
            return "Failed to write config: " + e.getMessage();
        }
    }

    private static void writePropertyToFile(String key, String value) throws IOException {
        var lines = new java.util.ArrayList<String>();
        boolean found = false;
        if (Files.exists(CONFIG_FILE)) {
            for (String line : Files.readAllLines(CONFIG_FILE)) {
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
        Files.write(CONFIG_FILE, lines);
    }

    private static boolean tryReplaceIfProperty(java.util.ArrayList<String> lines, String stripped,
                                                 String key, String value, boolean alreadyFound) {
        if (alreadyFound || !isPropertyLine(stripped, key)) return false;
        writePropertyLineIfNotNull(lines, key, value);
        return true;
    }

    private static void writePropertyLineIfNotNull(java.util.ArrayList<String> lines, String key, String value) {
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
        try {
            var props = loadAgentProperties();
            String activeProvider = props.getProperty("active.provider");
            if (activeProvider == null || activeProvider.isBlank()) return null;
            String key = activeProvider + ".request.extra_body";
            String json = props.getProperty(key, "{}").trim();
            if (json.isEmpty() || "{}".equals(json)) return null;
            @SuppressWarnings("unchecked")
            var extraMap = (java.util.Map<String, Object>) JsonUtil.fromJson(java.util.Map.class, json);
            Object effort = extraMap.get("reasoning_effort");
            if (effort instanceof String s) {
                return parseLevel(s.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to read reasoning effort from extra_body", e);
        }
        return null;
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

    public static ReasoningEffort parseLevel(String level) {
        return switch (level) {
            case "low" -> ReasoningEffort.LOW;
            case "medium" -> ReasoningEffort.MEDIUM;
            case "high" -> ReasoningEffort.HIGH;
            case "max" -> ReasoningEffort.MAX;
            default -> null;
        };
    }
}
