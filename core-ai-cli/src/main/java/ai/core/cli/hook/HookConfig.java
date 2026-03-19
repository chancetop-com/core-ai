package ai.core.cli.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public final class HookConfig {
    private static final Logger logger = LoggerFactory.getLogger(HookConfig.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static HookConfig load(Path workspace) {
        var file = workspace.resolve(".core-ai/hooks.json");
        if (!Files.isRegularFile(file)) {
            return new HookConfig(Collections.emptyMap());
        }
        try {
            String content = Files.readString(file);
            return parse(content, workspace);
        } catch (IOException e) {
            logger.warn("Failed to load hooks.json: {}", e.getMessage());
            return new HookConfig(Collections.emptyMap());
        }
    }

    // Format (compatible with Claude Code):
    // { "hooks": { "UserPromptSubmit": [{ "matcher": "", "hooks": [{ "type": "command", "command": "..." }] }] } }
    static HookConfig parse(String json, Path workspace) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode hooksNode = root.has("hooks") ? root.get("hooks") : root;

        Map<HookEvent, List<HookEntry>> hooks = new EnumMap<>(HookEvent.class);
        for (HookEvent event : HookEvent.values()) {
            JsonNode eventNode = hooksNode.get(event.jsonName());
            if (eventNode == null || !eventNode.isArray()) continue;

            List<HookEntry> entries = parseEventEntries(eventNode, workspace);
            if (!entries.isEmpty()) {
                hooks.put(event, entries);
            }
        }
        return new HookConfig(hooks);
    }

    private static List<HookEntry> parseEventEntries(JsonNode eventNode, Path workspace) {
        List<HookEntry> entries = new ArrayList<>();
        for (JsonNode matcherGroup : eventNode) {
            String matcher = matcherGroup.has("matcher") ? matcherGroup.get("matcher").asText() : null;
            JsonNode hooksArray = matcherGroup.get("hooks");
            if (hooksArray == null || !hooksArray.isArray()) continue;
            addHookEntries(entries, hooksArray, matcher, workspace);
        }
        return entries;
    }

    private static void addHookEntries(List<HookEntry> entries, JsonNode hooksArray, String matcher, Path workspace) {
        for (JsonNode hookNode : hooksArray) {
            String command = hookNode.has("command") ? hookNode.get("command").asText() : null;
            if (command == null || command.isBlank()) continue;
            entries.add(new HookEntry(resolveCommand(command, workspace), matcher));
        }
    }

    private static String resolveCommand(String command, Path workspace) {
        if (command.startsWith("/")) return command;
        if (command.startsWith("~")) {
            return System.getProperty("user.home") + command.substring(1);
        }
        return workspace.resolve(command).toAbsolutePath().toString();
    }

    private final Map<HookEvent, List<HookEntry>> hooks;

    private HookConfig(Map<HookEvent, List<HookEntry>> hooks) {
        this.hooks = hooks;
    }

    public List<HookEntry> getHooks(HookEvent event) {
        return hooks.getOrDefault(event, Collections.emptyList());
    }

    public boolean isEmpty() {
        return hooks.isEmpty();
    }
}
