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
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class GatewayModelService {
    static final String ENDPOINT_CHAT_COMPLETIONS = "chat.completions";
    static final String ENDPOINT_RESPONSES = "responses";

    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;
    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;
    @Inject
    MongoCollection<User> userCollection;

    public ListGatewayModelsResponse list(String userId) {
        requireAdmin(userId);
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

    public GatewayModelView create(GatewayModelRequest request, String userId) {
        requireAdmin(userId);
        validate(request, true);
        var provider = provider(request.providerId);

        var now = ZonedDateTime.now();
        var entity = new GatewayModelConfig();
        entity.id = UUID.randomUUID().toString();
        apply(entity, request, true);
        entity.createdBy = userId;
        entity.updatedBy = userId;
        entity.createdAt = now;
        entity.updatedAt = now;
        gatewayModelCollection.insert(entity);
        return toView(entity, provider);
    }

    public GatewayModelView update(String id, GatewayModelRequest request, String userId) {
        requireAdmin(userId);
        validate(request, false);

        var entity = getEntity(id);
        var providerId = request.providerId != null ? request.providerId : entity.providerId;
        var provider = provider(providerId);
        apply(entity, request, false);
        entity.updatedBy = userId;
        entity.updatedAt = ZonedDateTime.now();
        gatewayModelCollection.replace(entity);
        return toView(entity, provider);
    }

    public void delete(String id, String userId) {
        requireAdmin(userId);
        gatewayModelCollection.delete(id);
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
        if (specified(request, "priority", create)) entity.priority = request.priority;
        if (entity.priority == null) entity.priority = 100L;
        if (specified(request, "contextWindow", create)) entity.contextWindow = request.contextWindow;
        if (specified(request, "supportsStream", create)) entity.supportsStream = request.supportsStream;
        if (specified(request, "supportsTools", create)) entity.supportsTools = request.supportsTools;
        if (specified(request, "supportsVision", create)) entity.supportsVision = request.supportsVision;
        if (specified(request, "inputPricePer1MTokens", create)) entity.inputPricePer1MTokens = request.inputPricePer1MTokens;
        if (specified(request, "outputPricePer1MTokens", create)) entity.outputPricePer1MTokens = request.outputPricePer1MTokens;
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

    static List<String> normalizeEndpointTypes(List<String> endpointTypes) {
        var values = endpointTypes.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> normalizeEndpointType(value.trim()))
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

    private void requireAdmin(String userId) {
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
