package ai.core.cli.appserver;

import ai.core.api.server.plugin.PluginRegistrySearchResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Serializes a registry search response into the shape the desktop Hub panel consumes. Kept out of
 * {@link HubBridge} so the bridge stays under the file-length gate.
 *
 * @author stephen
 */
final class HubPluginPayload {
    static ObjectNode toNode(String serverUrl, PluginRegistrySearchResponse response) {
        var node = Params.object();
        node.put("connected", true);
        node.put("serverUrl", serverUrl);
        node.put("total", response.total == null ? 0L : response.total);
        var array = node.putArray("plugins");
        for (var plugin : nullSafe(response.plugins)) {
            var item = array.addObject();
            item.put("id", plugin.id);
            item.put("name", plugin.name);
            item.put("version", plugin.version);
            if (plugin.description != null) item.put("description", plugin.description);
            if (plugin.vendor != null) item.put("vendor", plugin.vendor);
            if (plugin.homepage != null) item.put("homepage", plugin.homepage);
            item.put("downloadUrl", plugin.downloadUrl);
            item.put("sha256", plugin.sha256);
            if (plugin.size != null) item.put("size", plugin.size);
            var required = item.putArray("requiredCapabilities");
            for (var capability : nullSafe(plugin.requiredCapabilities)) required.add(capability);
            var optional = item.putArray("optionalCapabilities");
            for (var capability : nullSafe(plugin.optionalCapabilities)) optional.add(capability);
            var components = item.putArray("components");
            for (var component : nullSafe(plugin.components)) components.add(component);
            if (plugin.updatedAt != null) item.put("updatedAt", plugin.updatedAt.toInstant().toString());
        }
        return node;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private HubPluginPayload() {
    }
}
