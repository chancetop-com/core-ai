package ai.core.server.web.auth;

import ai.core.server.domain.ApiKey;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.exception.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Authenticates HTTP requests for both regular web routes and patched SSE routes.
 *
 * @author xander
 */
public class RequestAuthenticator {
    private static final Duration LAST_USED_UPDATE_THRESHOLD = Duration.ofMinutes(5);

    public static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new Error(e);
        }
    }

    @Inject
    MongoCollection<User> userCollection;
    @Inject
    MongoCollection<ApiKey> apiKeyCollection;

    public String authenticate(Request request) {
        var userId = authenticateFromAzureAD(request);
        if (userId == null) {
            userId = authenticateFromApiKey(request);
        }
        if (userId == null) {
            throw new UnauthorizedException("authentication required");
        }
        return userId;
    }

    /**
     * Authenticates using an API key record (ctk_ temp call key or cmk_ management key) and returns the api user id.
     * The caller (AuthInterceptor) decides whether the key scope is acceptable for the requested path.
     */
    public AuthResult authenticateFromApiKeyRecord(Request request) {
        var auth = request.header("Authorization");
        if (auth.isEmpty()) return null;
        var value = auth.get();
        if (!value.startsWith("Bearer ctk_") && !value.startsWith("Bearer cmk_")) return null;

        var key = value.substring(7);
        var apiKey = findByHash(key);
        if (apiKey.isEmpty()) throw new UnauthorizedException("invalid api key");

        var record = apiKey.get();
        if (!"active".equals(record.status)) throw new UnauthorizedException("api key is not active");
        if (record.expiresAt != null && record.expiresAt.isBefore(ZonedDateTime.now())) {
            updateKeyStatus(record, "expired");
            throw new UnauthorizedException("api key expired");
        }
        var user = userCollection.get(record.userId);
        if (user.isEmpty() || !"active".equals(user.get().status)) {
            throw new UnauthorizedException("api user disabled");
        }
        if (user.get().ownerId != null) {
            var owner = userCollection.get(user.get().ownerId);
            if (owner.isEmpty() || !"active".equals(owner.get().status)) {
                throw new UnauthorizedException("api user disabled");
            }
        }
        updateLastUsed(record);
        return new AuthResult(user.get().id, record.id, record.scope);
    }

    private String authenticateFromAzureAD(Request request) {
        var email = request.header("X-Auth-Request-Email");
        if (email.isEmpty() || email.get().isBlank()) return null;

        var userId = email.get().trim().toLowerCase(Locale.ROOT);
        ensureUser(userId, request.header("X-Auth-Request-User").orElse(userId));
        return userId;
    }

    private String authenticateFromApiKey(Request request) {
        var auth = request.header("Authorization");
        if (auth.isEmpty()) return null;

        var value = auth.get();
        if (!value.startsWith("Bearer coreai_") && !value.startsWith("Bearer cai_")) return null;

        var apiKey = value.substring(7);
        var user = userCollection.findOne(Filters.eq("api_key", apiKey));
        if (user.isEmpty()) throw new UnauthorizedException("invalid api key");

        if (!"active".equals(user.get().status)) {
            throw new UnauthorizedException("account is pending approval");
        }

        updateLastLogin(user.get());
        return user.get().id;
    }

    private void ensureUser(String userId, String name) {
        var existing = userCollection.get(userId);
        if (existing.isEmpty()) {
            var user = new User();
            user.id = userId;
            user.email = userId;
            user.name = name;
            user.status = "active";
            user.createdAt = ZonedDateTime.now();
            user.lastLoginAt = user.createdAt;
            userCollection.insert(user);
        } else {
            updateLastLogin(existing.get());
        }
    }

    private void updateLastLogin(User user) {
        user.lastLoginAt = ZonedDateTime.now();
        userCollection.replace(user);
    }

    private void updateLastUsed(ApiKey apiKey) {
        var now = ZonedDateTime.now();
        if (apiKey.lastUsedAt != null && apiKey.lastUsedAt.plus(LAST_USED_UPDATE_THRESHOLD).isAfter(now)) return;
        apiKeyCollection.update(Filters.eq("_id", apiKey.id), Updates.set("last_used_at", now));
    }

    private void updateKeyStatus(ApiKey apiKey, String status) {
        apiKeyCollection.update(Filters.eq("_id", apiKey.id),
            Updates.combine(Updates.set("status", status), Updates.set("revoked_at", ZonedDateTime.now())));
    }

    private java.util.Optional<ApiKey> findByHash(String key) {
        return apiKeyCollection.findOne(Filters.eq("key_hash", sha256(key)));
    }

    public record AuthResult(String userId, String keyId, String scope) {
    }
}
