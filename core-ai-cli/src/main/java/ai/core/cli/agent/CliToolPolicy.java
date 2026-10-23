package ai.core.cli.agent;

import ai.core.bootstrap.PropertiesFileSource;
import ai.core.tool.BuiltinTools;
import ai.core.tool.ToolCall;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the tools hidden from the agent through {@code agent.tools.disabled}.
 * <p>
 * The value is a comma-separated list of tool names, e.g. {@code web_search,web_fetch}. Builtin
 * tool-set ids from {@link ai.core.tool.registry.ToolProvider} may be used to disable a whole
 * group, e.g. {@code builtin-web}.
 *
 * @author stephen
 */
public final class CliToolPolicy {
    public static final String DISABLED_TOOLS_PROPERTY = "agent.tools.disabled";

    public static Set<String> disabledTools(PropertiesFileSource props) {
        return props.property(DISABLED_TOOLS_PROPERTY).map(CliToolPolicy::parse).orElseGet(Set::of);
    }

    static Set<String> parse(String raw) {
        var names = new LinkedHashSet<String>();
        if (raw == null) {
            return Set.of();
        }
        for (var token : raw.split(",")) {
            var name = token.trim().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            var group = BuiltinTools.GROUPED_SETS.get(name);
            if (group == null) {
                names.add(name);
                continue;
            }
            addGroup(names, group);
        }
        return Set.copyOf(names);
    }

    private static void addGroup(Set<String> names, List<ToolCall> group) {
        for (var tool : group) {
            names.add(tool.getName().toLowerCase(Locale.ROOT));
        }
    }

    private CliToolPolicy() {
    }
}
