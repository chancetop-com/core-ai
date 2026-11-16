package ai.core.server.skill.quality;

import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Static descriptor of the capabilities a skill may declare. It intentionally does not instantiate the runtime
 * tool sets: several of them need providers (media, GitHub, video understanding) that do not exist in the
 * publish path. A parity test keeps this table aligned with {@link BuiltinTools}.
 *
 * @author core-ai
 */
public final class SkillCapabilityCatalog {
    private static final List<String> EXTRA_TOOLS = List.of(
        "transcribe_audio", "understand_video", "submit_artifacts", "decide", "powershell"
    );

    private static final Set<String> TOOLS = buildTools();
    private static final Map<String, Set<String>> GROUPS = buildGroups();

    private static Set<String> buildTools() {
        var tools = new LinkedHashSet<String>();
        BuiltinTools.ALL.forEach(tool -> tools.add(canonical(tool.getName())));
        BuiltinTools.GROUPED_SETS.values().forEach(list -> list.forEach(tool -> tools.add(canonical(tool.getName()))));
        EXTRA_TOOLS.forEach(tool -> tools.add(canonical(tool)));
        return Set.copyOf(tools);
    }

    private static Map<String, Set<String>> buildGroups() {
        var groups = new LinkedHashMap<String, Set<String>>();
        BuiltinTools.GROUPED_SETS.forEach((key, tools) -> groups.put(alias(key), names(tools)));
        groups.put("all", TOOLS);
        return Map.copyOf(groups);
    }

    private static Set<String> names(List<ToolCall> tools) {
        var result = new LinkedHashSet<String>();
        tools.forEach(tool -> result.add(canonical(tool.getName())));
        return Set.copyOf(result);
    }

    private static String alias(String groupId) {
        return canonical(groupId.startsWith("builtin-") ? groupId.substring("builtin-".length()) : groupId);
    }

    public static boolean isKnownTool(String name) {
        return name != null && TOOLS.contains(canonical(name));
    }

    public static boolean isKnownGroup(String name) {
        return name != null && GROUPS.containsKey(canonical(name));
    }

    public static Set<String> groupMembers(String name) {
        var members = GROUPS.get(canonical(name));
        return members == null ? Set.of() : members;
    }

    /** SDK namespace keys treat {@code -} and {@code _} as interchangeable; the catalog does the same. */
    public static String canonical(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private SkillCapabilityCatalog() {
    }
}
