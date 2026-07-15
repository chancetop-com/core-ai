package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static ai.core.server.gateway.GatewaySupport.isBlank;
import static ai.core.server.gateway.GatewaySupport.trimToNull;

public class GatewayModelService {
    static final String ENDPOINT_CHAT_COMPLETIONS = "chat.completions";
    static final String ENDPOINT_RESPONSES = "responses";

    static List<String> normalizeEndpointTypes(List<String> endpointTypes) {
        var values = endpointTypes.stream()
                .filter(endpointType -> endpointType != null && !endpointType.isBlank())
                .map(endpointType -> normalizeEndpointType(endpointType.trim()))
                .distinct()
                .toList();
        if (values.isEmpty()) throw new BadRequestException("endpointTypes must include at least one endpoint");
        return values;
    }

    private static String normalizeEndpointType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "chat", "chat.completion", "chat.completions" -> ENDPOINT_CHAT_COMPLETIONS;
            case "response", "responses" -> ENDPOINT_RESPONSES;
            default -> throw new BadRequestException("unsupported endpoint type: " + value);
        };
    }

    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;
    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    GatewayModelDiscoveryService gatewayModelDiscoveryService;
    @Inject
    GatewayRoutingEngine gatewayRoutingEngine;

    public ListGatewayModelsResponse importModels(String providerId, ImportGatewayModelsRequest request, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        var provider = provider(providerId);
        if (request.models == null || request.models.isEmpty()) throw new BadRequestException("models are required");

        var now = ZonedDateTime.now();
        var providerModels = providerModels(providerId);
        var existingByUpstream = new LinkedHashMap<String, GatewayModelConfig>();
        providerModels.forEach(model -> {
            if (model.upstreamModel != null) existingByUpstream.putIfAbsent(model.upstreamModel, model);
        });
        var discoveredModels = discoveredModels(provider);

        var staged = stage(request, provider, existingByUpstream, discoveredModels, userId, now);
        var imported = new ListGatewayModelsResponse();
        imported.models = staged.stream().map(change -> {
            if (change.create) {
                gatewayModelCollection.insert(change.entity);
            } else {
                gatewayModelCollection.replace(change.entity);
            }
            return toView(change.entity, provider);
        }).toList();
        invalidateRouting();
        return imported;
    }

    public ListGatewayModelsResponse list(String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = Sorts.ascending("model_id");

        var providers = providersById();
        var response = new ListGatewayModelsResponse();
        response.models = gatewayModelCollection.find(query).stream()
                .map(model -> toView(model, providers.get(model.providerId)))
                .toList();
        return response;
    }

    public ListGatewayAvailableModelsResponse listAvailable() {
        var response = new ListGatewayAvailableModelsResponse();
        response.models = gatewayRoutingEngine.availableModels();
        return response;
    }

    public GatewayModelView create(GatewayModelRequest request, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        validate(request, true);
        var provider = provider(request.providerId);

        var now = ZonedDateTime.now();
        var entity = new GatewayModelConfig();
        entity.id = UUID.randomUUID().toString();
        apply(entity, request, true);
        if (Boolean.TRUE.equals(entity.isDefault)) clearDefaults(entity.providerId);
        rejectDuplicateModelId(entity);
        entity.createdBy = userId;
        entity.updatedBy = userId;
        entity.createdAt = now;
        entity.updatedAt = now;
        gatewayModelCollection.insert(entity);
        invalidateRouting();
        return toView(entity, provider);
    }

    public GatewayModelView update(String id, GatewayModelRequest request, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        validate(request, false);

        var entity = getEntity(id);
        var providerId = request.providerId != null ? request.providerId : entity.providerId;
        var provider = provider(providerId);
        apply(entity, request, false);
        if (Boolean.TRUE.equals(entity.isDefault)) clearDefaults(entity.providerId);
        rejectDuplicateModelId(entity);
        entity.updatedBy = userId;
        entity.updatedAt = ZonedDateTime.now();
        gatewayModelCollection.replace(entity);
        invalidateRouting();
        return toView(entity, provider);
    }

    public GatewayModelView markDefault(String id, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        var entity = getEntity(id);
        clearDefaults(entity.providerId);
        entity.isDefault = Boolean.TRUE;
        entity.updatedBy = userId;
        entity.updatedAt = ZonedDateTime.now();
        gatewayModelCollection.replace(entity);
        invalidateRouting();
        return toView(entity, provider(entity.providerId));
    }

    public void delete(String id, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        gatewayModelCollection.delete(id);
        invalidateRouting();
    }

    private List<StagedImport> stage(ImportGatewayModelsRequest request, GatewayProviderConfig provider,
                                      Map<String, GatewayModelConfig> existingByUpstream,
                                      Map<String, GatewayModelMetadata> discoveredModels,
                                      String userId, ZonedDateTime now) {
        var staged = new ArrayList<StagedImport>();
        var seenUpstream = new HashSet<String>();
        for (var item : request.models) {
            if (isBlank(item.upstreamModel)) throw new BadRequestException("upstreamModel is required");
            var upstreamModel = item.upstreamModel.trim();
            if (!seenUpstream.add(upstreamModel)) throw new BadRequestException("duplicate upstreamModel in import request: " + upstreamModel);
            var metadata = discoveredModels.get(upstreamModel);
            if (metadata == null && !discoveredModels.isEmpty()) {
                throw new BadRequestException("gateway model is not available from provider discovery: " + upstreamModel);
            }
            if (metadata == null) {
                metadata = GatewayModelCatalog.enrich(new GatewayModelMetadata(upstreamModel, null, null, null, null, null, null, null, null));
            }
            if (metadata.endpointTypes() == null || metadata.endpointTypes().isEmpty()) {
                throw new BadRequestException("gateway does not support model endpoint type: " + upstreamModel);
            }

            var entity = existingByUpstream.get(upstreamModel);
            var create = entity == null;
            if (create) {
                entity = new GatewayModelConfig();
                entity.id = UUID.randomUUID().toString();
                entity.modelId = trimToNull(item.alias) == null ? upstreamModel : item.alias.trim();
                entity.providerId = provider.id;
                entity.upstreamModel = upstreamModel;
                entity.createdBy = userId;
                entity.createdAt = now;
            } else if (trimToNull(item.alias) != null) {
                entity.modelId = item.alias.trim();
            }

            applyMetadata(entity, metadata);
            if (item.enabled != null) entity.enabled = item.enabled;
            if (entity.enabled == null) entity.enabled = Boolean.TRUE;
            if (item.priority != null) entity.priority = item.priority;
            if (entity.priority == null) entity.priority = 100L;
            entity.updatedBy = userId;
            entity.updatedAt = now;
            staged.add(new StagedImport(entity, create));
        }
        return staged;
    }

    private void apply(GatewayModelConfig entity, GatewayModelRequest request, boolean create) {
        if (specified(request, "modelId", create)) entity.modelId = request.modelId.trim();
        if (specified(request, "displayName", create)) entity.displayName = trimToNull(request.displayName);
        if (specified(request, "providerId", create)) entity.providerId = request.providerId.trim();
        if (specified(request, "upstreamModel", create)) entity.upstreamModel = request.upstreamModel.trim();
        if (specified(request, "endpointTypes", create)) {
            entity.endpointTypes = request.endpointTypes == null ? null : normalizeEndpointTypes(request.endpointTypes);
        }
        if (entity.endpointTypes == null || entity.endpointTypes.isEmpty()) entity.endpointTypes = List.of(ENDPOINT_CHAT_COMPLETIONS);
        if (specified(request, "enabled", create)) entity.enabled = request.enabled;
        if (entity.enabled == null) entity.enabled = Boolean.TRUE;
        if (specified(request, "isDefault", create)) entity.isDefault = request.isDefault;
        if (entity.isDefault == null) entity.isDefault = Boolean.FALSE;
        if (specified(request, "priority", create)) entity.priority = request.priority;
        if (entity.priority == null) entity.priority = 100L;
        if (specified(request, "contextWindow", create)) entity.contextWindow = request.contextWindow;
        if (specified(request, "supportsStream", create)) entity.supportsStream = request.supportsStream;
        if (specified(request, "supportsTools", create)) entity.supportsTools = request.supportsTools;
        if (specified(request, "supportsVision", create)) entity.supportsVision = request.supportsVision;
        if (specified(request, "inputPricePer1MTokens", create)) entity.inputPricePer1MTokens = request.inputPricePer1MTokens;
        if (specified(request, "outputPricePer1MTokens", create)) entity.outputPricePer1MTokens = request.outputPricePer1MTokens;
    }

    private void applyMetadata(GatewayModelConfig entity, GatewayModelMetadata metadata) {
        if (entity.displayName == null && metadata.displayName() != null) entity.displayName = metadata.displayName();
        if (metadata.endpointTypes() != null && !metadata.endpointTypes().isEmpty()) entity.endpointTypes = normalizeEndpointTypes(metadata.endpointTypes());
        if (metadata.contextWindow() != null) entity.contextWindow = metadata.contextWindow();
        if (metadata.supportsStream() != null) entity.supportsStream = metadata.supportsStream();
        if (metadata.supportsTools() != null) entity.supportsTools = metadata.supportsTools();
        if (metadata.supportsVision() != null) entity.supportsVision = metadata.supportsVision();
        if (metadata.inputPricePer1MTokens() != null) entity.inputPricePer1MTokens = metadata.inputPricePer1MTokens();
        if (metadata.outputPricePer1MTokens() != null) entity.outputPricePer1MTokens = metadata.outputPricePer1MTokens();
    }

    private void rejectDuplicateModelId(GatewayModelConfig entity) {
        var duplicate = providerModels(entity.providerId).stream()
                .anyMatch(model -> !model.id.equals(entity.id) && entity.modelId.equals(model.modelId));
        if (duplicate) throw new BadRequestException("gateway model already exists for provider: " + entity.modelId);
    }

    private void validate(GatewayModelRequest request, boolean create) {
        if (create && isBlank(request.modelId)) throw new BadRequestException("modelId is required");
        if (create && isBlank(request.providerId)) throw new BadRequestException("providerId is required");
        if (create && isBlank(request.upstreamModel)) throw new BadRequestException("upstreamModel is required");
        if (request.hasField("modelId") && isBlank(request.modelId)) throw new BadRequestException("modelId is required");
        if (request.hasField("providerId") && isBlank(request.providerId)) throw new BadRequestException("providerId is required");
        if (request.hasField("upstreamModel") && isBlank(request.upstreamModel)) throw new BadRequestException("upstreamModel is required");
        if (request.endpointTypes != null) normalizeEndpointTypes(request.endpointTypes);
        if (request.priority != null && request.priority < 0) throw new BadRequestException("priority must not be negative");
        if (request.contextWindow != null && request.contextWindow <= 0) throw new BadRequestException("contextWindow must be positive");
        if (request.inputPricePer1MTokens != null && request.inputPricePer1MTokens < 0) {
            throw new BadRequestException("inputPricePer1MTokens must not be negative");
        }
        if (request.outputPricePer1MTokens != null && request.outputPricePer1MTokens < 0) {
            throw new BadRequestException("outputPricePer1MTokens must not be negative");
        }
    }

    private GatewayModelConfig getEntity(String id) {
        return gatewayModelCollection.get(id)
                .orElseThrow(() -> new NotFoundException("gateway model not found: " + id));
    }

    private GatewayProviderConfig provider(String id) {
        return gatewayProviderCollection.get(id)
                .orElseThrow(() -> new BadRequestException("providerId is invalid: " + id));
    }

    private Map<String, GatewayProviderConfig> providersById() {
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = Sorts.ascending("name");
        var providers = new LinkedHashMap<String, GatewayProviderConfig>();
        gatewayProviderCollection.find(query).forEach(provider -> providers.put(provider.id, provider));
        return providers;
    }

    private List<GatewayModelConfig> providerModels(String providerId) {
        var query = new Query();
        query.filter = Filters.eq("provider_id", providerId);
        return gatewayModelCollection.find(query);
    }

    private Map<String, GatewayModelMetadata> discoveredModels(GatewayProviderConfig provider) {
        if (gatewayModelDiscoveryService == null) return Map.of();
        var models = new LinkedHashMap<String, GatewayModelMetadata>();
        gatewayModelDiscoveryService.discover(provider).forEach(model -> models.putIfAbsent(model.id(), model));
        return models;
    }

    private void invalidateRouting() {
        if (gatewayRoutingEngine != null) gatewayRoutingEngine.invalidate();
    }

    private GatewayModelView toView(GatewayModelConfig entity, GatewayProviderConfig provider) {
        var view = new GatewayModelView();
        view.id = entity.id;
        view.modelId = entity.modelId;
        view.displayName = entity.displayName;
        view.providerId = entity.providerId;
        view.providerName = provider == null ? null : provider.name;
        view.upstreamModel = entity.upstreamModel;
        view.endpointTypes = entity.endpointTypes;
        view.enabled = entity.enabled;
        view.isDefault = entity.isDefault;
        view.priority = entity.priority;
        view.contextWindow = entity.contextWindow;
        view.supportsStream = entity.supportsStream;
        view.supportsTools = entity.supportsTools;
        view.supportsVision = entity.supportsVision;
        view.inputPricePer1MTokens = entity.inputPricePer1MTokens;
        view.outputPricePer1MTokens = entity.outputPricePer1MTokens;
        view.createdBy = entity.createdBy;
        view.updatedBy = entity.updatedBy;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        return view;
    }

    private boolean specified(GatewayModelRequest request, String field, boolean create) {
        return create || request.hasField(field);
    }

    private void clearDefaults(String providerId) {
        var filter = Filters.and(
                Filters.eq("provider_id", providerId),
                Filters.eq("is_default", Boolean.TRUE)
        );
        gatewayModelCollection.update(filter, com.mongodb.client.model.Updates.set("is_default", Boolean.FALSE));
    }

    private record StagedImport(GatewayModelConfig entity, boolean create) {
    }
}
