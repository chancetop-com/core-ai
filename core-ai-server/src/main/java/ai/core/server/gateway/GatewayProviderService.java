package ai.core.server.gateway;

import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class GatewayProviderService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayProviderService.class);

    @Inject
    MongoCollection<GatewayProviderConfig> gatewayProviderCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    GatewaySecretProtector secretProtector;

    public ListGatewayProvidersResponse list(String userId) {
        requireAdmin(userId);
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = com.mongodb.client.model.Sorts.ascending("name");

        var response = new ListGatewayProvidersResponse();
        response.providers = gatewayProviderCollection.find(query).stream().map(this::toView).toList();
        return response;
    }

    public GatewayProviderView create(GatewayProviderRequest request, String userId) {
        requireAdmin(userId);
        validate(request, true);

        var now = ZonedDateTime.now();
        var entity = new GatewayProviderConfig();
        entity.id = UUID.randomUUID().toString();
        apply(entity, request, false);
        entity.createdBy = userId;
        entity.updatedBy = userId;
        entity.createdAt = now;
        entity.updatedAt = now;
        gatewayProviderCollection.insert(entity);
        return toView(entity);
    }

    public GatewayProviderView update(String id, GatewayProviderRequest request, String userId) {
        requireAdmin(userId);
        validate(request, false);

        var entity = getEntity(id);
        apply(entity, request, true);
        entity.updatedBy = userId;
        entity.updatedAt = ZonedDateTime.now();
        gatewayProviderCollection.replace(entity);
        return toView(entity);
    }

    public void delete(String id, String userId) {
        requireAdmin(userId);
        gatewayProviderCollection.delete(id);
    }

    public TestGatewayProviderResponse test(String id, String userId) {
        requireAdmin(userId);
        var entity = getEntity(id);
        var result = test(entity);

        entity.lastTestStatus = result.status;
        entity.lastTestMessage = result.message;
        entity.lastTestAt = ZonedDateTime.now();
        gatewayProviderCollection.replace(entity);
        return result;
    }

    private TestGatewayProviderResponse test(GatewayProviderConfig entity) {
        var started = System.nanoTime();
        var result = new TestGatewayProviderResponse();
        try {
            GatewayNetworkGuard.validateOutboundUrl(testUrl(entity), Boolean.TRUE.equals(entity.allowPrivateNetwork));
            var client = HTTPClient.builder()
                    .connectTimeout(Duration.ofSeconds(valueOrDefault(entity.connectTimeoutSeconds, 10)))
                    .timeout(Duration.ofSeconds(valueOrDefault(entity.timeoutSeconds, 30)))
                    .build();
            var request = new HTTPRequest(HTTPMethod.GET, testUrl(entity));
            request.headers.put("Content-Type", "application/json");
            var apiKey = secret(entity);
            if (!isBlank(apiKey)) {
                if ("azure".equals(entity.type)) {
                    request.headers.put("api-key", apiKey);
                } else {
                    request.headers.put("Authorization", "Bearer " + apiKey);
                }
            }

            var response = client.execute(request);
            result.ok = response.statusCode >= 200 && response.statusCode < 300;
            result.status = result.ok ? "ok" : "failed";
            result.message = result.ok ? "Connected" : truncate("HTTP " + response.statusCode + ": " + response.text(), 300);
        } catch (Exception e) {
            LOGGER.warn("gateway provider test failed, providerId={}, error={}", entity.id, e.getMessage());
            result.ok = false;
            result.status = "failed";
            result.message = truncate(e.getMessage(), 300);
        } finally {
            result.durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        }
        return result;
    }

    private String testUrl(GatewayProviderConfig entity) {
        var baseUrl = stripTrailingSlash(entity.baseUrl);
        if ("azure".equals(entity.type)) {
            var version = isBlank(entity.apiVersion) ? "2024-10-21" : entity.apiVersion;
            return baseUrl + "/openai/deployments?api-version=" + version;
        }
        return baseUrl + "/models";
    }

    private GatewayProviderConfig getEntity(String id) {
        return gatewayProviderCollection.get(id)
                .orElseThrow(() -> new NotFoundException("gateway provider not found: " + id));
    }

    private void apply(GatewayProviderConfig entity, GatewayProviderRequest request, boolean keepExistingSecret) {
        if (request.name != null) entity.name = request.name.trim();
        if (request.type != null) entity.type = normalizeType(request.type);
        if (request.baseUrl != null) entity.baseUrl = stripTrailingSlash(request.baseUrl.trim());
        if (request.apiKey != null && (!keepExistingSecret || !request.apiKey.isBlank())) {
            entity.apiKeyEncrypted = secretProtector.protect(request.apiKey.trim());
            entity.apiKey = null;
        }
        if (request.apiVersion != null) entity.apiVersion = trimToNull(request.apiVersion);
        if (request.enabled != null) entity.enabled = request.enabled;
        if (entity.enabled == null) entity.enabled = Boolean.TRUE;
        if (request.allowPrivateNetwork != null) entity.allowPrivateNetwork = request.allowPrivateNetwork;
        if (entity.allowPrivateNetwork == null) entity.allowPrivateNetwork = Boolean.FALSE;
        if (request.modelPrefix != null) entity.modelPrefix = trimToNull(request.modelPrefix);
        if (entity.modelPrefix == null) entity.modelPrefix = defaultModelPrefix(entity.type);
        if (request.defaultChatModel != null) entity.defaultChatModel = trimToNull(request.defaultChatModel);
        if (request.defaultResponsesModel != null) entity.defaultResponsesModel = trimToNull(request.defaultResponsesModel);
        if (request.requestExtraBody != null) entity.requestExtraBody = trimToNull(request.requestExtraBody);
        if (request.timeoutSeconds != null) entity.timeoutSeconds = request.timeoutSeconds;
        if (request.connectTimeoutSeconds != null) entity.connectTimeoutSeconds = request.connectTimeoutSeconds;
        if (entity.timeoutSeconds == null) entity.timeoutSeconds = 30L;
        if (entity.connectTimeoutSeconds == null) entity.connectTimeoutSeconds = 10L;
    }

    private void validate(GatewayProviderRequest request, boolean create) {
        if (create && isBlank(request.name)) throw new BadRequestException("name is required");
        if (create && isBlank(request.type)) throw new BadRequestException("type is required");
        if (create && isBlank(request.baseUrl)) throw new BadRequestException("baseUrl is required");
        if (request.name != null && request.name.isBlank()) throw new BadRequestException("name is required");
        if (request.baseUrl != null && request.baseUrl.isBlank()) throw new BadRequestException("baseUrl is required");
        if (request.baseUrl != null && !request.baseUrl.isBlank()) GatewayNetworkGuard.validateHttpUrlSyntax(request.baseUrl.trim());
        if (request.type != null) normalizeType(request.type);
        if (request.requestExtraBody != null) validateExtraBody(request.requestExtraBody);
        if (request.timeoutSeconds != null && request.timeoutSeconds <= 0) {
            throw new BadRequestException("timeoutSeconds must be positive");
        }
        if (request.connectTimeoutSeconds != null && request.connectTimeoutSeconds <= 0) {
            throw new BadRequestException("connectTimeoutSeconds must be positive");
        }
    }

    private void requireAdmin(String userId) {
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }

    private GatewayProviderView toView(GatewayProviderConfig entity) {
        var view = new GatewayProviderView();
        view.id = entity.id;
        view.name = entity.name;
        view.type = entity.type;
        view.baseUrl = entity.baseUrl;
        var apiKey = secret(entity);
        view.apiKeyMasked = mask(apiKey);
        view.hasApiKey = !isBlank(apiKey);
        view.apiVersion = entity.apiVersion;
        view.enabled = entity.enabled;
        view.allowPrivateNetwork = entity.allowPrivateNetwork;
        view.modelPrefix = entity.modelPrefix;
        view.defaultChatModel = entity.defaultChatModel;
        view.defaultResponsesModel = entity.defaultResponsesModel;
        view.requestExtraBody = entity.requestExtraBody;
        view.timeoutSeconds = entity.timeoutSeconds;
        view.connectTimeoutSeconds = entity.connectTimeoutSeconds;
        view.createdBy = entity.createdBy;
        view.updatedBy = entity.updatedBy;
        view.createdAt = entity.createdAt;
        view.updatedAt = entity.updatedAt;
        view.lastTestStatus = entity.lastTestStatus;
        view.lastTestMessage = entity.lastTestMessage;
        view.lastTestAt = entity.lastTestAt;
        return view;
    }

    private void validateExtraBody(String value) {
        var trimmed = trimToNull(value);
        if (trimmed == null) return;
        try {
            var parsed = com.fasterxml.jackson.databind.json.JsonMapper.builder().build().readValue(trimmed, Object.class);
            if (!(parsed instanceof java.util.Map<?, ?>)) throw new BadRequestException("requestExtraBody must be a JSON object");
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("requestExtraBody must be valid JSON: " + e.getMessage());
        }
    }

    private String secret(GatewayProviderConfig entity) {
        if (entity.apiKeyEncrypted != null) return secretProtector.unprotect(entity.apiKeyEncrypted);
        return secretProtector.unprotect(entity.apiKey);
    }

    private String normalizeType(String type) {
        var value = type.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "openai", "azure", "litellm", "deepseek", "qwen", "openrouter", "openai-compatible" -> value;
            default -> throw new BadRequestException("unsupported provider type: " + type);
        };
    }

    private String defaultModelPrefix(String type) {
        if (type == null) return null;
        return switch (type) {
            case "openai" -> "openai/";
            case "azure" -> "azure/";
            case "litellm" -> "litellm/";
            case "deepseek" -> "deepseek/";
            case "qwen" -> "qwen/";
            case "openrouter" -> "openrouter/";
            default -> null;
        };
    }

    private String mask(String secret) {
        if (isBlank(secret)) return null;
        if (secret.length() <= 8) return "********";
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
    }

    private String trimToNull(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String stripTrailingSlash(String value) {
        var result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long valueOrDefault(Long value, long defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) return value;
        return value.substring(0, maxLength);
    }
}
