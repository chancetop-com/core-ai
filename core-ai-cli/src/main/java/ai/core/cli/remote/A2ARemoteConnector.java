package ai.core.cli.remote;

import ai.core.a2a.HttpA2AClient;
import ai.core.api.a2a.A2ATransport;
import ai.core.api.a2a.AgentCard;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers the HTTP+JSON A2A endpoint for a remote agent.
 *
 * @author xander
 */
public class A2ARemoteConnector {
    public Connection connect(RemoteConfig config) {
        RuntimeException lastError = null;
        for (var baseUrl : a2aBaseUrlCandidates(config.serverUrl())) {
            var client = a2aClient(baseUrl, config, config.agentId());
            try {
                var card = client.getAgentCard();
                var endpoint = a2aEndpoint(baseUrl, card);
                var tenant = a2aTenant(card, config.agentId());
                if (!endpoint.equals(baseUrl) || !same(tenant, config.agentId())) {
                    client = a2aClient(endpoint, config, tenant);
                }
                return new Connection(client, endpoint, tenant, card.name);
            } catch (RuntimeException e) {
                lastError = e;
            }
        }
        throw new RuntimeException("failed to connect remote A2A agent: "
                + (lastError != null ? lastError.getMessage() : config.serverUrl()), lastError);
    }

    private HttpA2AClient a2aClient(String baseUrl, RemoteConfig config, String tenant) {
        return HttpA2AClient.builder()
                .baseUrl(baseUrl)
                .tenant(tenant)
                .bearerToken(config.apiKey())
                .timeout(Duration.ofMinutes(30))
                .build();
    }

    private List<String> a2aBaseUrlCandidates(String serverUrl) {
        var base = trimTrailingSlash(serverUrl);
        var candidates = new ArrayList<String>();
        candidates.add(base);
        if (!base.endsWith("/api/a2a")) {
            candidates.add(base + "/api/a2a");
        }
        return candidates;
    }

    private String a2aEndpoint(String discoveryBaseUrl, AgentCard card) {
        var agentInterface = httpJsonInterface(card);
        if (agentInterface == null || agentInterface.url == null || agentInterface.url.isBlank()) {
            return discoveryBaseUrl;
        }
        return trimTrailingSlash(java.net.URI.create(discoveryBaseUrl + "/").resolve(agentInterface.url).toString());
    }

    private String a2aTenant(AgentCard card, String configuredAgentId) {
        if (configuredAgentId != null && !configuredAgentId.isBlank()) return configuredAgentId;
        var agentInterface = httpJsonInterface(card);
        return agentInterface != null ? agentInterface.tenant : null;
    }

    private AgentCard.AgentInterface httpJsonInterface(AgentCard card) {
        if (card.supportedInterfaces == null || card.supportedInterfaces.isEmpty()) return null;
        for (var agentInterface : card.supportedInterfaces) {
            if (agentInterface == null) continue;
            if (agentInterface.protocolBinding == null || A2ATransport.HTTP_JSON.equals(agentInterface.protocolBinding)) {
                return agentInterface;
            }
        }
        return card.supportedInterfaces.getFirst();
    }

    private boolean same(String left, String right) {
        if (left == null || left.isBlank()) return right == null || right.isBlank();
        return left.equals(right);
    }

    private String trimTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public record Connection(HttpA2AClient client, String baseUrl, String agentId, String agentName) {
    }
}
