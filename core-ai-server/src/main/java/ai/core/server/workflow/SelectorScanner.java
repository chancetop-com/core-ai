package ai.core.server.workflow;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the node ids a piece of node config references via {@code {{nodes.<id>...}}} selectors. This feeds
 * the publish-time dominator check (a node may only read outputs of nodes that dominate it). A lightweight
 * text scan is enough — the full template/variable model arrives in P2; the selector syntax is stable.
 *
 * @author Xander
 */
public final class SelectorScanner {
    private static final Pattern SELECTOR = Pattern.compile("\\{\\{\\s*nodes\\.([A-Za-z_][A-Za-z0-9_]*)");

    private SelectorScanner() {
    }

    public static List<String> referencedNodeIds(String text) {
        if (text == null) {
            return List.of();
        }
        var ids = new LinkedHashSet<String>();
        Matcher matcher = SELECTOR.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return List.copyOf(ids);
    }
}
