package ai.core.cli.helper;

import ai.core.cli.hub.agent.AgentHubClient;
import ai.core.cli.hub.agent.LocalAttachmentUploader;
import ai.core.tool.ToolCall;
import ai.core.tool.registry.ToolProvider;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Mounts each enabled helper as its own delegation tool. Tool names are slugged from the agent
 * name and renamed with an id suffix on collision (with existing tools or between helpers), so a
 * helper is never silently shadowed by the registry's name merge.
 *
 * @author stephen
 */
public final class HelperAgentToolProvider implements ToolProvider {
    public static final String ID = "helper-agent";
    private static final int MAX_TOOL_NAME_LENGTH = 64;
    private static final int PRIORITY = 16;

    public static HelperAgentToolProvider create(List<HelperEntry> helpers, Set<String> reservedToolNames,
                                                 Supplier<AgentHubClient> clientSupplier,
                                                 Supplier<LocalAttachmentUploader> uploaderSupplier) {
        var tools = new LinkedHashMap<String, ToolCall>();
        var names = new HashSet<String>();
        if (reservedToolNames != null) {
            for (var name : reservedToolNames) {
                names.add(normalizeName(name));
            }
        }
        if (helpers != null) {
            for (var entry : helpers) {
                if (entry == null || entry.agentId == null || !HelperEntry.enabled(entry)) {
                    continue;
                }
                var tool = HelperAgentToolCall.builder()
                        .helper(entry)
                        .name(uniqueName(entry, names))
                        .clientSupplier(clientSupplier)
                        .uploaderSupplier(uploaderSupplier)
                        .build();
                tools.put(tool.getName(), tool);
            }
        }
        return new HelperAgentToolProvider(tools);
    }

    static String uniqueName(HelperEntry entry, Set<String> takenNames) {
        var base = slug(HelperEntry.displayName(entry));
        var stem = base.isEmpty() ? "helper" : base;
        var suffix = "_" + shortId(entry.agentId);
        var candidate = base.isEmpty() ? stem + suffix : stem;
        if (takenNames.add(normalizeName(candidate))) {
            return candidate;
        }
        candidate = trimToLength(stem, MAX_TOOL_NAME_LENGTH - suffix.length()) + suffix;
        var count = 2;
        while (!takenNames.add(normalizeName(candidate))) {
            var numbered = suffix + "_" + count;
            candidate = trimToLength(stem, MAX_TOOL_NAME_LENGTH - numbered.length()) + numbered;
            count++;
        }
        return candidate;
    }

    static String slug(String value) {
        if (value == null) {
            return "";
        }
        var result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            var c = Character.toLowerCase(value.charAt(i));
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                result.append(c);
            } else if (!result.isEmpty() && result.charAt(result.length() - 1) != '_') {
                result.append('_');
            }
        }
        while (!result.isEmpty() && result.charAt(result.length() - 1) == '_') {
            result.deleteCharAt(result.length() - 1);
        }
        return result.toString();
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static String shortId(String agentId) {
        var normalized = agentId.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.isBlank()) {
            return "agent";
        }
        return normalized.length() <= 8 ? normalized : normalized.substring(0, 8);
    }

    private static String trimToLength(String value, int maxLength) {
        if (maxLength < 1) {
            return value;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private final Map<String, ToolCall> tools;

    private HelperAgentToolProvider(Map<String, ToolCall> tools) {
        this.tools = Map.copyOf(tools);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public Map<String, ToolCall> provide() {
        return tools;
    }

    @Override
    public RefreshPolicy refreshPolicy() {
        return RefreshPolicy.ONCE;
    }
}
