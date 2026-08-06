package ai.core.server.apiuser;

import ai.core.api.server.apiuser.request.CreateKeyRequest;
import ai.core.api.server.apiuser.request.RenewKeyRequest;
import ai.core.api.server.apiuser.response.CreateKeyResponse;
import ai.core.api.server.apiuser.response.ListKeysView;
import ai.core.api.server.apiuser.response.RenewKeyResponse;
import ai.core.server.domain.ApiKey;
import ai.core.server.domain.User;
import ai.core.server.web.auth.RequestAuthenticator;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * API key lifecycle: issue (ctk_ temp call keys), renew, expire, list. Management keys (cmk_) are admin-issued only.
 *
 * @author stephen
 */
public class ApiUserKeyService {
    public static final String SCOPE_CALL = "call";
    public static final String SCOPE_MANAGE = "manage";
    public static final String PREFIX_TEMP_KEY = "ctk_";
    public static final String PREFIX_MANAGE_KEY = "cmk_";
    public static final int DEFAULT_TTL_SECONDS = 3600;
    public static final int MAX_TTL_SECONDS = 604800;

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    MongoCollection<ApiKey> apiKeyCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    ApiUserService apiUserService;

    public int defaultTtlSeconds = DEFAULT_TTL_SECONDS;
    public int maxTtlSeconds = MAX_TTL_SECONDS;

    public CreateKeyResponse issueKey(String managerUserId, String userId, CreateKeyRequest request) {
        var user = apiUserService.getApiUser(managerUserId, userId);
        if (user.ownerId == null) throw new ForbiddenException("cannot issue keys for manager user");

        int ttl = resolveTtl(request != null ? request.ttlSeconds : null);
        var rawKey = generateKey(PREFIX_TEMP_KEY);
        var apiKey = new ApiKey();
        apiKey.id = "k_" + UUID.randomUUID();
        apiKey.keyHash = RequestAuthenticator.sha256(rawKey);
        apiKey.keyPrefix = rawKey.substring(0, PREFIX_TEMP_KEY.length() + 8);
        apiKey.userId = user.id;
        apiKey.scope = SCOPE_CALL;
        apiKey.metadata = request != null && request.metadata != null ? request.metadata : null;
        apiKey.status = "active";
        apiKey.expiresAt = ZonedDateTime.now().plusSeconds(ttl);
        apiKey.createdAt = ZonedDateTime.now();
        apiKeyCollection.insert(apiKey);

        var response = new CreateKeyResponse();
        response.keyId = apiKey.id;
        response.key = rawKey;
        response.expiresAt = apiKey.expiresAt;
        response.metadata = apiKey.metadata;
        return response;
    }

    public RenewKeyResponse renewKey(String managerUserId, String keyId, RenewKeyRequest request) {
        var apiKey = requireOwnedKey(managerUserId, keyId);
        if (!SCOPE_CALL.equals(apiKey.scope)) throw new BadRequestException("only call keys can be renewed");
        if (!"active".equals(apiKey.status)) throw new BadRequestException("key is not active");
        if (apiKey.expiresAt == null || apiKey.expiresAt.isBefore(ZonedDateTime.now())) {
            throw new BadRequestException("key is expired, issue a new key");
        }
        int ttl = resolveTtl(request != null ? request.ttlSeconds : null);
        apiKey.expiresAt = ZonedDateTime.now().plusSeconds(ttl);
        apiKeyCollection.update(Filters.eq("_id", keyId), Updates.set("expires_at", apiKey.expiresAt));

        var response = new RenewKeyResponse();
        response.keyId = keyId;
        response.expiresAt = apiKey.expiresAt;
        return response;
    }

    public void expireKey(String managerUserId, String keyId) {
        requireOwnedKey(managerUserId, keyId);
        apiKeyCollection.update(Filters.eq("_id", keyId), Updates.combine(
                Updates.set("status", "revoked"),
                Updates.set("revoked_at", ZonedDateTime.now())));
    }

    public ListKeysView listKeys(String managerUserId, String userId) {
        apiUserService.getApiUser(managerUserId, userId);
        var query = new core.framework.mongo.Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("created_at");
        var keys = apiKeyCollection.find(query);

        var view = new ListKeysView();
        view.keys = keys.stream().map(k -> {
            var kv = new ListKeysView.KeyView();
            kv.keyId = k.id;
            kv.keyPrefix = k.keyPrefix;
            kv.scope = k.scope;
            kv.status = k.status;
            kv.expiresAt = k.expiresAt != null ? k.expiresAt.toString() : null;
            kv.lastUsedAt = k.lastUsedAt != null ? k.lastUsedAt.toString() : null;
            kv.createdAt = k.createdAt != null ? k.createdAt.toString() : null;
            return kv;
        }).toList();
        return view;
    }

    /**
     * Admin: issues a management key (cmk_) for a manager-type api user; used at create and rotate.
     */
    public CreateKeyResponse issueManagementKey(String managerUserId) {
        var rawKey = generateKey(PREFIX_MANAGE_KEY);
        var apiKey = new ApiKey();
        apiKey.id = "k_" + UUID.randomUUID();
        apiKey.keyHash = RequestAuthenticator.sha256(rawKey);
        apiKey.keyPrefix = rawKey.substring(0, PREFIX_MANAGE_KEY.length() + 8);
        apiKey.userId = managerUserId;
        apiKey.scope = SCOPE_MANAGE;
        apiKey.status = "active";
        apiKey.createdAt = ZonedDateTime.now();
        apiKeyCollection.insert(apiKey);

        var response = new CreateKeyResponse();
        response.keyId = apiKey.id;
        response.key = rawKey;
        return response;
    }

    public void revokeManageKeys(String managerUserId) {
        var query = new core.framework.mongo.Query();
        query.filter = Filters.and(
                Filters.eq("user_id", managerUserId),
                Filters.eq("scope", SCOPE_MANAGE),
                Filters.eq("status", "active"));
        var keys = apiKeyCollection.find(query);
        for (var key : keys) {
            apiKeyCollection.update(Filters.eq("_id", key.id), Updates.combine(
                    Updates.set("status", "revoked"),
                    Updates.set("revoked_at", ZonedDateTime.now())));
        }
    }

    private ApiKey requireOwnedKey(String managerUserId, String keyId) {
        var apiKey = apiKeyCollection.get(keyId).orElse(null);
        if (apiKey == null) throw new NotFoundException("key not found, keyId=" + keyId);
        var user = userCollection.get(apiKey.userId).orElse(null);
        if (user == null || user.ownerId == null || !user.ownerId.equals(managerUserId)) {
            throw new ForbiddenException("key does not belong to current manager");
        }
        return apiKey;
    }

    private int resolveTtl(Integer requested) {
        int ttl = requested != null ? requested : defaultTtlSeconds;
        if (ttl <= 0) throw new BadRequestException("ttl_seconds must be positive");
        if (ttl > maxTtlSeconds) throw new BadRequestException("ttl_seconds exceeds max " + maxTtlSeconds);
        return ttl;
    }

    private String generateKey(String prefix) {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
