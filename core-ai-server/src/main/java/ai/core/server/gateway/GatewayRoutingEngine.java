package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.core.server.gateway.GatewaySupport.hasText;

public class GatewayRoutingEngine {
    // model/provider configs change rarely; short TTL keeps the proxy hot path off Mongo
    private static final long CACHE_TTL_NANOS = Duration.ofSeconds(5).toNanos();

    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;

    private volatile Snapshot cache;

    public List<GatewayPublishedModel> models() {
        var data = new ArrayList<GatewayPublishedModel>();
        var snapshot = snapshot();
        var models = registeredModels(snapshot, null);
        if (!models.isEmpty()) {
            var seen = new HashSet<String>();
            // duplicate modelIds across providers are failover routes; publish the highest priority one
            for (var route : models) {
                if (seen.add(route.model.modelId)) {
                    data.add(new GatewayPublishedModel(route.model.modelId, route.provider.name));
                }
            }
        } else {
            var seen = new HashSet<String>();
            for (var provider : snapshot.providers) {
                addLegacyModel(data, seen, provider, provider.defaultChatModel);
                addLegacyModel(data, seen, provider, provider.defaultResponsesModel);
            }
        }
        return data;
    }

    public List<GatewayAvailableModelView> availableModels() {
        var data = new ArrayList<GatewayAvailableModelView>();
        var snapshot = snapshot();
        var models = registeredModels(snapshot, null);
        if (!models.isEmpty()) {
            var seen = new HashSet<String>();
            for (var route : models) {
                if (seen.add(route.model.modelId)) data.add(availableModel(route));
            }
            return data;
        }
        var seen = new HashSet<String>();
        for (var provider : snapshot.providers) {
            addLegacyAvailableModel(data, seen, provider, provider.defaultChatModel, GatewayModelService.ENDPOINT_CHAT_COMPLETIONS);
            addLegacyAvailableModel(data, seen, provider, provider.defaultResponsesModel, GatewayModelService.ENDPOINT_RESPONSES);
        }
        return data;
    }

    public GatewayRoute route(String requestedModel, GatewayEndpointType endpoint) {
        var snapshot = snapshot();
        if (snapshot.providers.isEmpty()) throw new BadRequestException("no enabled gateway providers configured");
        var registeredModels = registeredModels(snapshot, null);
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
            var modelExists = !registeredModels.isEmpty()
                    && registeredModels.stream().anyMatch(route -> requestedModel.equals(route.model.modelId));
            if (modelExists) throw new BadRequestException("gateway model does not support endpoint: " + requestedModel);
            if (!registeredModels.isEmpty()) throw new BadRequestException("no enabled gateway model matches model: " + requestedModel);
            return legacyRoute(requestedModel, endpoint, snapshot.providers);
        }

        if (!endpointModels.isEmpty()) {
            var route = endpointModels.get(0);
            return new GatewayRoute(route.provider, route.model.upstreamModel);
        }
        if (!registeredModels.isEmpty()) throw new BadRequestException("no enabled gateway model configured for endpoint: " + endpoint.id);
        return legacyRoute(null, endpoint, snapshot.providers);
    }

    public void invalidate() {
        cache = null;
    }

    public boolean hasEnabledProviders() {
        return !snapshot().providers.isEmpty();
    }

    public GatewayProviderConfig provider(String providerId) {
        if (!hasText(providerId)) throw new BadRequestException("gateway provider ID is required");
        return snapshot().providers.stream()
                .filter(provider -> providerId.equals(provider.id))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("gateway provider is unavailable for video task: " + providerId));
    }

    public GatewayProviderConfig jobProvider(String providerId) {
        if (!hasText(providerId)) throw new BadRequestException("gateway provider ID is required");
        return gatewayProviderCollection.get(providerId)
                .orElseThrow(() -> new BadRequestException("gateway provider is unavailable for video task: " + providerId));
    }

    /**
     * Whether any gateway model (enabled or not) is registered under this modelId —
     * lets callers distinguish "never registered" from "registered but disabled".
     */
    public boolean knowsModel(String modelId) {
        if (!hasText(modelId)) return false;
        return snapshot().models.stream().anyMatch(model -> modelId.equals(model.modelId));
    }

    /**
     * Whether this modelId currently resolves to an enabled model on an enabled provider.
     */
    public boolean isRoutable(String modelId) {
        if (!hasText(modelId)) return false;
        return registeredModels(snapshot(), null).stream().anyMatch(route -> modelId.equals(route.model.modelId));
    }

    private Snapshot snapshot() {
        var current = cache;
        if (current != null && System.nanoTime() - current.createdAt < CACHE_TTL_NANOS) return current;
        var refreshed = new Snapshot(enabledProviders(), allModels(), System.nanoTime());
        cache = refreshed;
        return refreshed;
    }

    private List<RegisteredGatewayModel> registeredModels(Snapshot snapshot, GatewayEndpointType endpoint) {
        var providersById = providersById(snapshot.providers);
        return snapshot.models.stream()
                .filter(model -> !Boolean.FALSE.equals(model.enabled) && hasText(model.modelId) && hasText(model.upstreamModel)
                        && (endpoint == null || supportsEndpoint(model, endpoint)))
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
                var upstreamModel = resolveUpstreamModel(requestedModel, provider, endpoint);
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
        // no provider default model configured — pick the model marked default or the first available model
        var models = registeredModels(snapshot(), endpoint);
        for (var model : models) {
            if (Boolean.TRUE.equals(model.model.isDefault)) {
                return new GatewayRoute(model.provider, model.model.upstreamModel);
            }
        }
        if (!models.isEmpty()) {
            var first = models.get(0);
            return new GatewayRoute(first.provider, first.model.upstreamModel);
        }
        throw new BadRequestException("model is required");
    }

    private List<GatewayProviderConfig> enabledProviders() {
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = Sorts.ascending("name");
        return gatewayProviderCollection.find(query).stream()
                .filter(provider -> !Boolean.FALSE.equals(provider.enabled))
                .toList();
    }

    private List<GatewayModelConfig> allModels() {
        if (gatewayModelCollection == null) return List.of();
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = Sorts.ascending("model_id");
        return gatewayModelCollection.find(query);
    }

    private void addLegacyModel(List<GatewayPublishedModel> data, Set<String> seen, GatewayProviderConfig provider, String model) {
        if (!hasText(model)) return;
        var modelId = prefixModel(stripPrefix(model, provider.modelPrefix), provider.modelPrefix);
        if (seen.add(modelId)) data.add(new GatewayPublishedModel(modelId, provider.name));
    }

    private void addLegacyAvailableModel(List<GatewayAvailableModelView> data, Set<String> seen, GatewayProviderConfig provider, String model, String endpointType) {
        if (!hasText(model)) return;
        var view = new GatewayAvailableModelView();
        view.modelId = prefixModel(stripPrefix(model, provider.modelPrefix), provider.modelPrefix);
        view.providerName = provider.name;
        view.endpointTypes = List.of(endpointType);
        if (seen.add(view.modelId)) data.add(view);
    }

    private GatewayAvailableModelView availableModel(RegisteredGatewayModel route) {
        var view = new GatewayAvailableModelView();
        view.modelId = route.model.modelId;
        view.displayName = route.model.displayName;
        view.providerName = route.provider.name;
        view.endpointTypes = route.model.endpointTypes;
        view.supportsVision = route.model.supportsVision;
        return view;
    }

    private String defaultModel(GatewayProviderConfig provider, GatewayEndpointType endpoint) {
        return switch (endpoint) {
            case CHAT_COMPLETIONS -> provider.defaultChatModel;
            case RESPONSES -> provider.defaultResponsesModel;
            case IMAGE_GENERATION, IMAGE_EDIT -> provider.defaultImageModel;
            case VIDEO_GENERATION -> provider.defaultVideoModel;
        };
    }

    private boolean supportsEndpoint(GatewayModelConfig model, GatewayEndpointType endpoint) {
        return model.endpointTypes == null || model.endpointTypes.isEmpty() || model.endpointTypes.contains(endpoint.id);
    }

    private String resolveUpstreamModel(String requestedModel, GatewayProviderConfig provider, GatewayEndpointType endpoint) {
        var upstreamModel = requestedModel.substring(provider.modelPrefix.length());
        if (!upstreamModel.isBlank()) return upstreamModel;
        var model = defaultModel(provider, endpoint);
        if (!hasText(model)) throw new BadRequestException("model is required after gateway prefix: " + provider.modelPrefix);
        return model;
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

    private record RegisteredGatewayModel(GatewayModelConfig model, GatewayProviderConfig provider) {
    }

    private record Snapshot(List<GatewayProviderConfig> providers, List<GatewayModelConfig> models, long createdAt) {
    }
}
