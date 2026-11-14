package ai.core.server.gateway;

import ai.core.decision.DecisionOptions;
import ai.core.decision.DecisionProvider;
import ai.core.decision.DecisionRequest;
import ai.core.decision.DecisionResponse;
import ai.core.decision.DecisionRoute;
import ai.core.server.domain.GatewayProviderConfig;
import core.framework.web.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gateway-backed decision provider: resolves the model through gateway routing, keeps one adapter per
 * enabled upstream provider and rewrites the model to the provider's upstream name.
 *
 * @author stephen
 */
public class GatewayDecisionProvider implements DecisionProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayDecisionProvider.class);
    private static final int MAX_CACHED_UPSTREAM_PROVIDERS = 32;

    private final GatewayRoutingEngine routingEngine;
    private final GatewaySecretProtector secretProtector;
    private final DecisionProviderAdapterFactory adapterFactory;
    private final ConcurrentMap<String, DecisionProvider> upstreamProviders = new ConcurrentHashMap<>();

    public GatewayDecisionProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector) {
        this(routingEngine, secretProtector, new DecisionProviderAdapterFactory());
    }

    GatewayDecisionProvider(GatewayRoutingEngine routingEngine, GatewaySecretProtector secretProtector,
                            DecisionProviderAdapterFactory adapterFactory) {
        this.routingEngine = routingEngine;
        this.secretProtector = secretProtector;
        this.adapterFactory = adapterFactory;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        return decide(request, DecisionOptions.none());
    }

    @Override
    public DecisionResponse decide(DecisionRequest request, DecisionOptions options) {
        if (!routingEngine.hasEnabledProviders()) {
            throw new BadRequestException("no enabled gateway providers configured for decision");
        }
        var resolved = routingEngine.route(request.model(), GatewayEndpointType.DECISION);
        var upstream = upstreamProvider(resolved.provider());
        var response = upstream.decide(request.withModel(resolved.upstreamModel()), options);
        if (response.usage() != null) {
            LOGGER.debug("decision call, model={}, provider={}, upstreamModel={}, inputTokens={}, outputTokens={}",
                    request.model(), resolved.provider().name, resolved.upstreamModel(),
                    response.usage().inputTokens(), response.usage().outputTokens());
        } else {
            LOGGER.debug("decision call, model={}, provider={}, upstreamModel={}",
                    request.model(), resolved.provider().name, resolved.upstreamModel());
        }
        return response.withRoute(new DecisionRoute(resolved.provider().id,
            resolved.upstreamModel(), resolved.fingerprint(request.model())));
    }

    private DecisionProvider upstreamProvider(GatewayProviderConfig provider) {
        var key = provider.id + ":" + provider.updatedAt;
        var cached = upstreamProviders.get(key);
        if (cached != null) return cached;
        if (upstreamProviders.size() >= MAX_CACHED_UPSTREAM_PROVIDERS) upstreamProviders.clear();
        return upstreamProviders.computeIfAbsent(key, ignored -> createUpstreamProvider(provider));
    }

    private DecisionProvider createUpstreamProvider(GatewayProviderConfig provider) {
        var apiKey = secretProtector.unprotect(provider.apiKeyEncrypted != null ? provider.apiKeyEncrypted : provider.apiKey);
        return adapterFactory.create(provider, apiKey == null ? "" : apiKey);
    }
}
