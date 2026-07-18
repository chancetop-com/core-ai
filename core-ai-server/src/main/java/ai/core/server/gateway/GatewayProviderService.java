package ai.core.server.gateway;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.GatewayProviderConfig;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import ai.core.media.GoogleAccessTokenProvider;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.UUID;

import static ai.core.server.gateway.GatewaySupport.isBlank;
import static ai.core.server.gateway.GatewaySupport.stripTrailingSlash;
import static ai.core.server.gateway.GatewaySupport.trimToNull;
import static ai.core.server.gateway.GatewaySupport.truncate;
import static ai.core.server.gateway.GatewaySupport.urlEncode;
import static ai.core.server.gateway.GatewaySupport.valueOrDefault;

public class GatewayProviderService {
    private static final Logger LOGGER = LoggerFactory.getLogger(GatewayProviderService.class);
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
    @Inject
    GatewayRoutingEngine gatewayRoutingEngine;

    public ListGatewayProvidersResponse list(String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        var query = new Query();
        query.filter = Filters.empty();
        query.sort = Sorts.ascending("name");

        var response = new ListGatewayProvidersResponse();
        response.providers = gatewayProviderCollection.find(query).stream().map(this::toView).toList();
        return response;
    }

    public GatewayProviderView create(GatewayProviderRequest request, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
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
        invalidateRouting();
        return toView(entity);
    }

    public GatewayProviderView update(String id, GatewayProviderRequest request, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        validate(request, false);

        var entity = getEntity(id);
        apply(entity, request, true);
        entity.updatedBy = userId;
        entity.updatedAt = ZonedDateTime.now();
        gatewayProviderCollection.replace(entity);
        invalidateRouting();
        return toView(entity);
    }

    public void delete(String id, String userId, boolean cascadeModels) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
        getEntity(id);
        if (hasModels(id)) {
            if (!cascadeModels) throw new BadRequestException("gateway provider has models configured: " + id);
            gatewayModelCollection.delete(Filters.eq("provider_id", id));
        }
        gatewayProviderCollection.delete(id);
        invalidateRouting();
    }

    public TestGatewayProviderResponse test(String id, String userId) {
        GatewayAdminGuard.requireAdmin(userCollection, userId);
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
        if (isVertexMediaProvider(entity) && vertexMediaProviderConfigurationInvalid(entity)) {
            result.ok = false;
            result.status = "failed";
            result.message = "Vertex provider requires GCP Project ID and GCP Location";
            result.durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            return result;
        }
        try {
            if (isVertexMediaProvider(entity)) {
                var credentials = secretProtector.unprotect(entity.googleCredentialsEncrypted);
                new GoogleAccessTokenProvider("GOOGLE_SERVICE_ACCOUNT_JSON".equals(entity.mediaAuthType) ? credentials : null).accessToken();
                result.ok = true;
                result.status = "ok";
                result.message = "Google credentials verified; video generation is not started by provider test";
            } else {
                GatewayNetworkGuard.validateOutboundUrl(testUrl(entity), Boolean.TRUE.equals(entity.allowPrivateNetwork));
                var request = new HTTPRequest(HTTPMethod.GET, testUrl(entity));
                request.headers.put("Content-Type", "application/json");
                request.connectTimeout = Duration.ofSeconds(valueOrDefault(entity.connectTimeoutSeconds, 10));
                request.timeout = Duration.ofSeconds(valueOrDefault(entity.timeoutSeconds, 30));
                GatewaySupport.applyAuth(entity, request, secret(entity));

                var response = CLIENT.execute(request);
                result.ok = response.statusCode >= 200 && response.statusCode < 300;
                result.status = result.ok ? "ok" : "failed";
                result.message = result.ok ? "Connected" : truncate("HTTP " + response.statusCode + ": " + response.text(), 300);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("gateway provider test failed, providerId={}, error={}", entity.id, e.getMessage());
            result.ok = false;
            result.status = "failed";
            result.message = truncate(e.getMessage(), 300);
        } finally {
            result.durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        }
        return result;
    }

    private boolean isVertexMediaProvider(GatewayProviderConfig provider) {
        return "VERTEX_GEMINI_GENERATE_CONTENT".equals(provider.mediaProtocol)
                || "VERTEX_GEMINI_INTERACTIONS".equals(provider.mediaProtocol);
    }

    private boolean vertexMediaProviderConfigurationInvalid(GatewayProviderConfig provider) {
        return isBlank(provider.vertexProjectId) || isBlank(provider.vertexLocation);
    }

    private String testUrl(GatewayProviderConfig entity) {
        var baseUrl = stripTrailingSlash(entity.baseUrl);
        if ("azure".equals(entity.type)) {
            var version = isBlank(entity.apiVersion) ? "2024-10-21" : entity.apiVersion;
            return baseUrl + "/openai/deployments?api-version=" + urlEncode(version);
        }
        if ("gemini".equals(entity.type)) return baseUrl + "/models";
        return baseUrl + "/models";
    }

    private GatewayProviderConfig getEntity(String id) {
        return gatewayProviderCollection.get(id)
                .orElseThrow(() -> new NotFoundException("gateway provider not found: " + id));
    }

    private boolean hasModels(String providerId) {
        if (gatewayModelCollection == null) return false;
        var query = new Query();
        query.filter = Filters.eq("provider_id", providerId);
        return !gatewayModelCollection.find(query).isEmpty();
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
        if (request.defaultImageModel != null) entity.defaultImageModel = trimToNull(request.defaultImageModel);
        if (request.defaultVideoModel != null) entity.defaultVideoModel = trimToNull(request.defaultVideoModel);
        if (request.mediaProtocol != null) entity.mediaProtocol = normalizeMediaProtocol(request.mediaProtocol);
        if (request.mediaAuthType != null) entity.mediaAuthType = normalizeMediaAuthType(request.mediaAuthType);
        if (request.googleCredentialsJson != null && (!keepExistingSecret || !request.googleCredentialsJson.isBlank())) {
            entity.googleCredentialsEncrypted = secretProtector.protect(request.googleCredentialsJson.trim());
        }
        if (request.vertexProjectId != null) entity.vertexProjectId = trimToNull(request.vertexProjectId);
        if (request.vertexLocation != null) entity.vertexLocation = trimToNull(request.vertexLocation);
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
        if (request.mediaProtocol != null) normalizeMediaProtocol(request.mediaProtocol);
        if (request.mediaAuthType != null) normalizeMediaAuthType(request.mediaAuthType);
        if (request.googleCredentialsJson != null && !request.googleCredentialsJson.isBlank()) validateGoogleCredentials(request.googleCredentialsJson);
        if (request.requestExtraBody != null) validateExtraBody(request.requestExtraBody);
        if (request.timeoutSeconds != null && request.timeoutSeconds <= 0) {
            throw new BadRequestException("timeoutSeconds must be positive");
        }
        if (request.connectTimeoutSeconds != null && request.connectTimeoutSeconds <= 0) {
            throw new BadRequestException("connectTimeoutSeconds must be positive");
        }
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
        view.defaultImageModel = entity.defaultImageModel;
        view.defaultVideoModel = entity.defaultVideoModel;
        view.mediaProtocol = entity.mediaProtocol;
        view.mediaAuthType = entity.mediaAuthType;
        view.hasGoogleCredentials = !isBlank(entity.googleCredentialsEncrypted);
        view.vertexProjectId = entity.vertexProjectId;
        view.vertexLocation = entity.vertexLocation;
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
            var parsed = GatewayJson.MAPPER.readValue(trimmed, Object.class);
            if (!(parsed instanceof java.util.Map<?, ?>)) throw new BadRequestException("requestExtraBody must be a JSON object");
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("requestExtraBody must be valid JSON: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private String secret(GatewayProviderConfig entity) {
        if (entity.apiKeyEncrypted != null) return secretProtector.unprotect(entity.apiKeyEncrypted);
        return secretProtector.unprotect(entity.apiKey);
    }

    private void invalidateRouting() {
        if (gatewayRoutingEngine != null) gatewayRoutingEngine.invalidate();
    }

    private String normalizeType(String type) {
        var value = type.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "openai", "azure", "litellm", "deepseek", "qwen", "openrouter", "openai-compatible", "gemini" -> value;
            default -> throw new BadRequestException("unsupported provider type: " + type);
        };
    }

    private String normalizeMediaProtocol(String mediaProtocol) {
        var value = mediaProtocol.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "OPENAI_IMAGES", "OPENAI_COMPATIBLE", "GEMINI_GENERATE_CONTENT", "VERTEX_GEMINI_GENERATE_CONTENT", "VERTEX_GEMINI_INTERACTIONS" -> value;
            default -> throw new BadRequestException("unsupported media protocol: " + mediaProtocol);
        };
    }

    private String normalizeMediaAuthType(String mediaAuthType) {
        var value = mediaAuthType.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "API_KEY", "GOOGLE_APPLICATION_DEFAULT_CREDENTIALS", "GOOGLE_SERVICE_ACCOUNT_JSON" -> value;
            default -> throw new BadRequestException("unsupported media auth type: " + mediaAuthType);
        };
    }

    @SuppressWarnings("unchecked")
    private void validateGoogleCredentials(String credentialsJson) {
        try {
            var credentials = GatewayJson.MAPPER.readValue(credentialsJson, java.util.Map.class);
            if (!"service_account".equals(credentials.get("type")) || isBlank((String) credentials.get("client_email")) || isBlank((String) credentials.get("private_key"))) {
                throw new BadRequestException("googleCredentialsJson must be a service account JSON key");
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("googleCredentialsJson must be valid JSON: " + e.getMessage(), "BAD_REQUEST", e);
        }
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
}
