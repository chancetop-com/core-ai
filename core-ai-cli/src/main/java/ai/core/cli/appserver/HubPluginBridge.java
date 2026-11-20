package ai.core.cli.appserver;

import ai.core.cli.hub.plugin.PluginHubClient;
import ai.core.cli.http.RemoteApiException;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Plugin registry payload for the Hub panel and the {@code hub/plugins} RPC.
 *
 * @author stephen
 */
final class HubPluginBridge {
    static ObjectNode plugins(ObjectNode params, HubBridge.Credentials credentials) {
        var node = Params.object();
        if (credentials == null) {
            return HubBridge.notConnected(node, "not_logged_in");
        }
        try {
            var query = Params.optionalText(params, "query");
            int limit = Math.max(1, Math.min(Params.intParam(params, "limit", 50), 100));
            var response = new PluginHubClient(credentials.serverUrl(), credentials.apiKey(), credentials.insecure())
                    .search(query, Params.optionalInt(params, "offset"), limit);
            return HubPluginPayload.toNode(credentials.serverUrl(), response);
        } catch (RemoteApiException e) {
            return HubBridge.unreachable(node, e);
        } catch (RuntimeException e) {
            return HubBridge.failure(node, "hub plugin list", e);
        }
    }

    private HubPluginBridge() {
    }
}
