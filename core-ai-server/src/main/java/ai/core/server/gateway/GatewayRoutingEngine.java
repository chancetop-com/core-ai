package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GatewayRoutingEngine {
    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;

    public List<GatewayPublishedModel> models() {
        var data = new ArrayList<GatewayPublishedModel>();
        var providers = enabledProviders();
        var models = registeredModels(providers, null);
        if (!models.isEmpty()) {
            var seen = new HashSet<String>();
            for (var route : models) {
                if (seen.add(route.model.modelId)) {
                    data.add(new GatewayPublishedModel(route.model.modelId, route.provider.name));
                }
            }
        } else {
            var seen = new HashSet<String>();
            for (var provider : providers) {
                addLegacyModel(data, seen, provider, provider.defaultChatModel);
                addLegacyModel(data, seen, provider, provider.defaultResponsesModel);
            }
        }
        return data;
    }

    public GatewayRoute route(String requestedModel, GatewayEndpointType endpoint) {
        var providers = enabledProviders();
        if (providers.isEmpty()) throw new BadRequestException("no enabled gateway providers configured");
        var registeredModels = registeredModels(providers, null);
        var endpointModels = registeredModels.stream()
                .filter(route -> supportsEndpoint(route.model, endpoint))
                .toList();

        if (hasText(requestedModel)) {
            var modelRoute = endpointModels.stream()
                    .filter(route -> requestedModel.equals(route.model.modelId))
                    .findFirst();
            if (modelRoute.isPresent()) {
                var route = modelRoute.get();
                return new GatewayRoute(route.provider, route.model.upstreamModel);
            }
            if (!registeredModels.isEmpty()) {
                var modelExists = registeredModels.stream().anyMatch(route -> requestedModel.equals(route.model.modelId));
                if (modelExists) throw new BadRequestException("gateway model does not support endpoint: " + requestedModel);
                throw new BadRequestException("no enabled gateway model matches model: " + requestedModel);
            }
            return legacyRoute(requestedModel, endpoint, providers);
        }

        if (!endpointModels.isEmpty()) {
            var route = endpointModels.get(0);
            return new GatewayRoute(route.provider, route.model.upstreamModel);
        }
        if (!registeredModels.isEmpty()) throw new BadRequestException("no enabled gateway model configured for endpoint: " + endpoint.id);
        return legacyRoute(null, endpoint, providers);
    }

    private List<RegisteredGatewayModel> registeredModels(List<GatewayProviderConfig> providers, GatewayEndpointType endpoint) {
        var providersById = providersById(providers);
        return enabledModels().stream()
                .filter(model -> hasText(model.modelId))
                .filter(model -> hasText(model.upstreamModel))
                .filter(model -> endpoint == null || supportsEndpoint(model, endpoint))
                .map(model -> new RegisteredGatewayModel(model, providersById.get(model.providerId)))
                .filter(route -> route.provider != null)
                .sorted(Comparator
                        .comparingLong((RegisteredGatewayModel route) -> route.model.priority == null ? 100L : route.model.priority)
                        .thenComparing(route -> route.model.modelId)
                        .thenComparing(route -> route.provider.name == null ? "" : route.provider.name)
                        .thenComparing(route -> route.model.id == null ? "" : route.model.id))
                .toList();
    }

    private Map<String, GatewayProviderConfig> providersById(List<GatewayProviderConfig> providers) {
        var providersById = new LinkedHashMap<String, GatewayProviderConfig>();
        for (var provider : providers) {
            if (hasText(provider.id)) providersById.putIfAbsent(provider.id, provider);
        }
        return providersById;
    }

    private GatewayRoute legacyRoute(String requestedModel, GatewayEndpointType endpoint, List<GatewayProviderConfig> providers) {
        if (hasText(requestedModel)) {
            var byPrefix = providers.stream()
                    .filter(provider -> hasText(provider.modelPrefix) && requestedModel.startsWith(provider.modelPrefix))
                    .max(Comparator.comparingInt(provider -> provider.modelPrefix.length()));
            if (byPrefix.isPresent()) {
                var provider = byPrefix.get();
                var upstreamModel = requestedModel.substring(provider.modelPrefix.length());
                if (upstreamModel.isBlank()) upstreamModel = defaultModel(provider, endpoint);
                if (!hasText(upstreamModel)) throw new BadRequestException("model is required after gateway prefix: " + provider.modelPrefix);
                return new GatewayRoute(provider, upstreamModel);
            }
            var fallback = providers.stream().filter(provider -> !hasText(provider.modelPrefix)).findFirst();
            if (fallback.isPresent()) return new GatewayRoute(fallback.get(), requestedModel);
            throw new BadRequestException("no enabled gateway provider matches model: " + requestedModel);
        }

        for (var provider : providers) {
            var defaultModel = defaultModel(provider, endpoint);
            if (hasText(defaultModel)) return new GatewayRoute(provider, stripPrefix(defaultModel, provider.modelPrefix));
        }
        throw new BadRequestException("model is required");
    }

    private List<GatewayProviderConfig> enabledProviders() {
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = com.mongodb.client.model.Sorts.ascending("name");
        return gatewayProviderCollection.find(query).stream()
                .filter(provider -> !Boolean.FALSE.equals(provider.enabled))
                .toList();
    }

    private List<GatewayModelConfig> enabledModels() {
        if (gatewayModelCollection == null) return List.of();
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = com.mongodb.client.model.Sorts.ascending("model_id");
        return gatewayModelCollection.find(query).stream()
                .filter(model -> !Boolean.FALSE.equals(model.enabled))
                .toList();
    }

    private void addLegacyModel(List<GatewayPublishedModel> data, HashSet<String> seen, GatewayProviderConfig provider, String model) {
        if (!hasText(model)) return;
        var modelId = prefixModel(stripPrefix(model, provider.modelPrefix), provider.modelPrefix);
        if (seen.add(modelId)) data.add(new GatewayPublishedModel(modelId, provider.name));
    }

    private String defaultModel(GatewayProviderConfig provider, GatewayEndpointType endpoint) {
        return endpoint == GatewayEndpointType.CHAT_COMPLETIONS ? provider.defaultChatModel : provider.defaultResponsesModel;
    }

    private boolean supportsEndpoint(GatewayModelConfig model, GatewayEndpointType endpoint) {
        return model.endpointTypes == null || model.endpointTypes.isEmpty() || model.endpointTypes.contains(endpoint.id);
    }

    private String stripPrefix(String model, String prefix) {
        if (hasText(prefix) && hasText(model) && model.startsWith(prefix)) {
            return model.substring(prefix.length());
        }
        return model;
    }

    private String prefixModel(String model, String prefix) {
        if (!hasText(prefix) || !hasText(model) || model.startsWith(prefix)) return model;
        return prefix + model;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RegisteredGatewayModel(GatewayModelConfig model, GatewayProviderConfig provider) {
    }
}
