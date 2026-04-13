package ai.core.cli.hook;

import ai.core.cli.plugin.PluginManager;
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
    private static final Logger LOGGER = LoggerFactory.getLogger(HookConfig.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * Load hooks with priority (nearest wins).
     * Priority: local plugins &lt; global plugins &lt; workspace hooks.json
     * Plugin discovery and disabled state are delegated to PluginManager.
     */
    public static HookConfig load(Path workspace, PluginManager pluginManager) {
        Map<HookEvent, List<HookEntry>> allHooks = new EnumMap<>(HookEvent.class);

        for (var plugin : pluginManager.getLoadedPlugins()) {
            String pluginName = plugin.manifest().name();
            if (pluginManager.isDisabled(pluginName)) {
                LOGGER.debug("Skipping disabled plugin hooks: {}", pluginName);
                continue;
            }
            var hooksFile = plugin.dir().resolve("hooks").resolve("hooks.json");
            if (Files.isRegularFile(hooksFile)) {
                try {
                    String content = Files.readString(hooksFile);
                    // Resolve relative paths against the plugin dir, not workspace
                    var pluginHooks = parse(content, plugin.dir(), pluginName);
                    mergeHooksWithDedupe(allHooks, pluginHooks.hooks, pluginName);
                    LOGGER.debug("Loaded hooks from plugin: {}", pluginName);
                } catch (IOException e) {
                    LOGGER.warn("Failed to load hooks from plugin '{}': {}", pluginName, e.getMessage());
                }
            }
        }

        // workspace hooks.json has highest priority
        var userHooksFile = workspace.resolve(".core-ai").resolve("hooks.json");
        if (Files.isRegularFile(userHooksFile)) {
            try {
                String content = Files.readString(userHooksFile);
                mergeHooksWithDedupe(allHooks, parse(content, workspace, null).hooks, null);
                LOGGER.debug("Loaded hooks from workspace hooks.json");
            } catch (IOException e) {
                LOGGER.warn("Failed to load hooks.json: {}", e.getMessage());
            }
        }

        return new HookConfig(allHooks);
    }
    
    /**
     * Merge hooks with deduplication by plugin + command.
     * Later sources override earlier ones with the same key.
     * Plugin hooks (plugin != null) are only overridden by same plugin or hooks.json.
     * Hooks.json hooks (plugin == null) can override any plugin.
     */
    private static void mergeHooksWithDedupe(Map<HookEvent, List<HookEntry>> target, 
                                             Map<HookEvent, List<HookEntry>> source,
                                             String sourcePlugin) {
        for (var entry : source.entrySet()) {
            var list = target.computeIfAbsent(entry.getKey(), k -> new ArrayList<>());
            for (var hook : entry.getValue()) {
                int existingIdx = findByDedupeKey(list, hook.dedupeKey());
                if (existingIdx >= 0) {
                    LOGGER.debug("Hook '{}' from {} overrides previous", hook.dedupeKey(), sourcePlugin);
                    list.set(existingIdx, hook);
                } else {
                    list.add(hook);
                }
            }
        }
    }
    
    private static int findByDedupeKey(List<HookEntry> list, String key) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).dedupeKey().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    // Format (compatible with Claude Code):
    // { "hooks": { "UserPromptSubmit": [{ "matcher": "", "hooks": [{ "type": "command", "command": "..." }] }] } }
    static HookConfig parse(String json, Path workspace, String pluginName) throws IOException {
        JsonNode root = OBJECT_MAPPER.readTree(json);
        JsonNode hooksNode = root.has("hooks") ? root.get("hooks") : root;

        Map<HookEvent, List<HookEntry>> hooks = new EnumMap<>(HookEvent.class);
        for (HookEvent event : HookEvent.values()) {
            JsonNode eventNode = hooksNode.get(event.jsonName());
            if (eventNode == null || !eventNode.isArray()) continue;

            List<HookEntry> entries = parseEventEntries(eventNode, workspace, pluginName);
            if (!entries.isEmpty()) {
                hooks.put(event, entries);
            }
        }
        return new HookConfig(hooks);
    }

    private static List<HookEntry> parseEventEntries(JsonNode eventNode, Path workspace, String pluginName) {
        List<HookEntry> entries = new ArrayList<>();
        for (JsonNode matcherGroup : eventNode) {
            String matcher = matcherGroup.has("matcher") ? matcherGroup.get("matcher").asText() : null;
            JsonNode hooksArray = matcherGroup.get("hooks");
            if (hooksArray == null || !hooksArray.isArray()) continue;
            addHookEntries(entries, hooksArray, matcher, workspace, pluginName);
        }
        return entries;
    }

    private static void addHookEntries(List<HookEntry> entries, JsonNode hooksArray, String matcher, 
                                       Path workspace, String pluginName) {
        for (JsonNode hookNode : hooksArray) {
            String command = hookNode.has("command") ? hookNode.get("command").asText() : null;
            if (command == null || command.isBlank()) continue;
            entries.add(HookEntry.ofPlugin(pluginName, resolveCommand(command, workspace), matcher));
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
