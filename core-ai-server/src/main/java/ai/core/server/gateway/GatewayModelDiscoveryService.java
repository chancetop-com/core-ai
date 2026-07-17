package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static ai.core.server.gateway.GatewaySupport.isBlank;
import static ai.core.server.gateway.GatewaySupport.stripTrailingSlash;
import static ai.core.server.gateway.GatewaySupport.truncate;
import static ai.core.server.gateway.GatewaySupport.urlEncode;
import static ai.core.server.gateway.GatewaySupport.valueOrDefault;

public class GatewayModelDiscoveryService {
    // shared client with a high ceiling; effective limits come from per-request timeouts
    private static final HTTPClient CLIENT = HTTPClient.builder()
            .connectTimeout(Duration.ofSeconds(10))
            .timeout(Duration.ofMinutes(2))
            .build();

    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    GatewaySecretProtector secretProtector;

    public ListGatewayDiscoveredModelsResponse discover(String providerId, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        var provider = provider(providerId);
        var discovered = discover(provider);
        var imported = importedUpstreamModels(providerId);

        var response = new ListGatewayDiscoveredModelsResponse();
        response.providerId = provider.id;
        response.providerName = provider.name;
        response.models = discovered.stream()
                .filter(model -> model.endpointTypes() != null && !model.endpointTypes().isEmpty())
                .map(model -> toView(model, imported.contains(model.id())))
                .toList();
        return response;
    }

    List<GatewayModelMetadata> discover(GatewayProviderConfig provider) {
        var models = new ArrayList<GatewayModelMetadata>();
        if ("litellm".equals(provider.type)) {
            models.addAll(fetch(provider, modelInfoUrl(provider)));
            if (models.isEmpty()) models.addAll(fetch(provider, modelsUrl(provider)));
        } else {
            models.addAll(fetch(provider, modelsUrl(provider)));
        }
        return models.stream()
                .map(GatewayModelCatalog::enrich)
                .filter(model -> model.id() != null && !model.id().isBlank())
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(GatewayModelMetadata::id, Function.identity(), (left, right) -> left, LinkedHashMap::new),
                        map -> map.values().stream()
                                .sorted(Comparator.comparing(GatewayModelMetadata::id))
                                .toList()
                ));
    }

    private List<GatewayModelMetadata> fetch(GatewayProviderConfig provider, String url) {
        try {
            GatewayNetworkGuard.validateOutboundUrl(url, Boolean.TRUE.equals(provider.allowPrivateNetwork));
            var request = new HTTPRequest(HTTPMethod.GET, url);
            request.headers.put("Content-Type", "application/json");
            request.connectTimeout = Duration.ofSeconds(valueOrDefault(provider.connectTimeoutSeconds, 10));
            request.timeout = Duration.ofSeconds(valueOrDefault(provider.timeoutSeconds, 30));
            GatewaySupport.applyAuth(provider, request, secret(provider));
            var response = execute(request, provider);
            if (response.statusCode < 200 || response.statusCode >= 300) {
                if ("litellm".equals(provider.type) && url.endsWith("/model/info")) return List.of();
                throw new BadRequestException("provider models endpoint failed: HTTP " + response.statusCode + ": " + truncate(response.text(), 300));
            }
            return parse(response.body == null ? new byte[0] : response.body);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            if ("litellm".equals(provider.type) && url.endsWith("/model/info")) return List.of();
            throw new BadRequestException("failed to discover gateway models: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private List<GatewayModelMetadata> parse(byte[] body) {
        try {
            var root = GatewayJson.MAPPER.readTree(body);
            var rows = rows(root);
            var models = new ArrayList<GatewayModelMetadata>();
            for (var row : rows) {
                var model = parseModel(row);
                if (model != null) models.add(model);
            }
            return models;
        } catch (Exception e) {
            throw new BadRequestException("invalid provider models response: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private List<JsonNode> rows(JsonNode root) {
        var rows = new ArrayList<JsonNode>();
        if (root == null || root.isNull()) return rows;
        var data = root.has("data") ? root.get("data") : root;
        if (data.isArray()) {
            data.forEach(rows::add);
            return rows;
        }
        if (data.isObject()) data.properties().forEach(entry -> rows.add(entry.getValue()));
        return rows;
    }

    private GatewayModelMetadata parseModel(JsonNode row) {
        if (row == null || row.isNull()) return null;
        if (row.isTextual()) return metadata(row.asText(), null, List.of(row));
        var modelInfo = object(row, "model_info");
        var litellmParams = object(row, "litellm_params");
        var pricing = object(row, "pricing");
        var id = firstText(row, modelInfo, litellmParams, "id", "model_id", "model", "model_name", "deployment", "deployment_name");
        if (id == null || id.isBlank()) return null;
        var displayName = firstText(row, modelInfo, litellmParams, "display_name", "name", "model_name");
        var source = merge(row, modelInfo, litellmParams, pricing);
        return metadata(id, displayName, source);
    }

    private GatewayModelMetadata metadata(String id, String displayName, List<JsonNode> source) {
        var endpointTypes = endpointTypes(source);
        var contextWindow = firstLong(source, "context_window", "contextWindow", "max_context_tokens", "max_input_tokens", "max_tokens");
        var inputPrice = price(source, "input_price_per_1m_tokens", "input_cost_per_1m_tokens", "input_cost_per_token", "prompt_cost_per_token");
        var outputPrice = price(source, "output_price_per_1m_tokens", "output_cost_per_1m_tokens", "output_cost_per_token", "completion_cost_per_token");
        return new GatewayModelMetadata(
                id,
                displayName,
                endpointTypes,
                contextWindow,
                firstBoolean(source, "supports_stream", "stream"),
                firstBoolean(source, "supports_tools", "supports_function_calling", "supports_parallel_function_calling"),
                firstBoolean(source, "supports_vision", "vision"),
                inputPrice,
                outputPrice
        );
    }

    private List<String> endpointTypes(List<JsonNode> source) {
        var mode = firstText(source, "mode", "endpoint", "endpoint_type", "model_type");
        if (mode == null) return null;
        var value = mode.toLowerCase(Locale.ROOT);
        if (containsAny(value, "embedding", "audio", "moderation")) return List.of();
        var endpoints = new LinkedHashSet<String>();
        if (value.contains("image")) endpoints.add(GatewayModelService.ENDPOINT_IMAGE_GENERATION);
        if (value.contains("video")) endpoints.add(GatewayModelService.ENDPOINT_VIDEO_GENERATION);
        if (value.contains("response")) endpoints.add(GatewayModelService.ENDPOINT_RESPONSES);
        if (value.contains("chat") || value.contains("completion") || endpoints.isEmpty()) {
            endpoints.add(GatewayModelService.ENDPOINT_CHAT_COMPLETIONS);
        }
        return List.copyOf(endpoints);
    }

    private GatewayDiscoveredModelView toView(GatewayModelMetadata model, boolean imported) {
        var view = new GatewayDiscoveredModelView();
        view.id = model.id();
        view.displayName = model.displayName();
        view.endpointTypes = model.endpointTypes();
        view.contextWindow = model.contextWindow();
        view.supportsStream = model.supportsStream();
        view.supportsTools = model.supportsTools();
        view.supportsVision = model.supportsVision();
        view.inputPricePer1MTokens = model.inputPricePer1MTokens();
        view.outputPricePer1MTokens = model.outputPricePer1MTokens();
        view.imported = imported;
        return view;
    }

    private Set<String> importedUpstreamModels(String providerId) {
        var query = new Query();
        query.filter = Filters.eq("provider_id", providerId);
        var imported = new HashSet<String>();
        gatewayModelCollection.find(query).forEach(model -> {
            if (model.upstreamModel != null) imported.add(model.upstreamModel);
        });
        return imported;
    }

    private GatewayProviderConfig provider(String id) {
        return gatewayProviderCollection.get(id)
                .orElseThrow(() -> new NotFoundException("gateway provider not found: " + id));
    }

    HTTPResponse execute(HTTPRequest request, GatewayProviderConfig provider) {
        return CLIENT.execute(request);
    }

    private String modelsUrl(GatewayProviderConfig provider) {
        var baseUrl = stripTrailingSlash(provider.baseUrl);
        if ("azure".equals(provider.type)) {
            var version = isBlank(provider.apiVersion) ? "2024-10-21" : provider.apiVersion;
            return baseUrl + "/openai/deployments?api-version=" + urlEncode(version);
        }
        return baseUrl + "/models";
    }

    private String modelInfoUrl(GatewayProviderConfig provider) {
        return stripTrailingSlash(provider.baseUrl) + "/model/info";
    }

    private String secret(GatewayProviderConfig provider) {
        if (provider.apiKeyEncrypted != null) return secretProtector.unprotect(provider.apiKeyEncrypted);
        return secretProtector.unprotect(provider.apiKey);
    }

    private List<JsonNode> merge(JsonNode... nodes) {
        var values = new ArrayList<JsonNode>();
        for (var node : nodes) {
            if (node != null && node.isObject()) values.add(node);
        }
        return values;
    }

    private JsonNode object(JsonNode node, String field) {
        var value = node == null ? null : node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private String firstText(JsonNode primary, JsonNode secondary, JsonNode tertiary, String... fields) {
        return firstText(merge(primary, secondary, tertiary), fields);
    }

    private String firstText(List<JsonNode> source, String... fields) {
        for (var node : source) {
            for (var field : fields) {
                var value = node.get(field);
                if (value != null && !value.isNull()) {
                    var text = value.asText(null);
                    if (text != null && !text.isBlank()) return text;
                }
            }
        }
        return null;
    }

    private Long firstLong(List<JsonNode> source, String... fields) {
        for (var node : source) {
            for (var field : fields) {
                var value = node.get(field);
                if (value != null && value.canConvertToLong()) return value.asLong();
            }
        }
        return null;
    }

    private Boolean firstBoolean(List<JsonNode> source, String... fields) {
        for (var node : source) {
            for (var field : fields) {
                var value = node.get(field);
                if (value != null && value.isBoolean()) return value.asBoolean();
            }
        }
        return null;
    }

    private Double price(List<JsonNode> source, String perMillionField1, String perMillionField2, String perTokenField1, String perTokenField2) {
        var perMillion = firstDouble(source, perMillionField1, perMillionField2);
        if (perMillion != null) return perMillion;
        var perToken = firstDouble(source, perTokenField1, perTokenField2);
        return perToken == null ? null : perToken * 1_000_000D;
    }

    private Double firstDouble(List<JsonNode> source, String... fields) {
        for (var node : source) {
            for (var field : fields) {
                var value = node.get(field);
                if (value != null && value.isNumber()) return value.asDouble();
            }
        }
        return null;
    }

    private boolean containsAny(String value, String... tokens) {
        for (var token : tokens) {
            if (value.contains(token)) return true;
        }
        return false;
    }
}
