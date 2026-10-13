package ai.core.server.sandbox.agentsandbox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a directly created sandbox pod should be placed: the priority class that decides its share under
 * contention, and the node labels that keep it off nodes it must not share. Both come from deployment
 * configuration and are applied only on the direct-CR path — a warm-pool claim takes its pod spec from
 * the pool template.
 *
 * @author stephen
 */
public class SandboxPodPlacement {
    public static final SandboxPodPlacement NONE = new SandboxPodPlacement(null, null);

    /**
     * Parses the {@code key=value,key=value} form used by the deployment property. Blank input means
     * "no placement constraint", which keeps the previous behaviour for deployments that do not set it.
     */
    public static Map<String, String> parseNodeSelector(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var labels = new LinkedHashMap<String, String>();
        for (var pair : raw.split(",")) {
            var separator = pair.indexOf('=');
            if (separator <= 0) continue;
            var key = pair.substring(0, separator).trim();
            var value = pair.substring(separator + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) labels.put(key, value);
        }
        return labels.isEmpty() ? null : labels;
    }

    public final String priorityClassName;
    public final Map<String, String> nodeSelector;

    public SandboxPodPlacement(String priorityClassName, Map<String, String> nodeSelector) {
        this.priorityClassName = priorityClassName;
        this.nodeSelector = nodeSelector == null || nodeSelector.isEmpty() ? null : new LinkedHashMap<>(nodeSelector);
    }
}
