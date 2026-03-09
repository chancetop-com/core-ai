package ai.core.server.web.auth;

import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;
import core.framework.web.exception.UnauthorizedException;

import java.time.ZonedDateTime;
import java.util.Locale;

/**
 * @author stephen
 */
public class AuthInterceptor implements Interceptor {
    @Inject
    MongoCollection<User> userCollection;

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();

        if (!path.startsWith("/api/")) {
            return invocation.proceed();
        }

        if (path.equals("/api/auth/register") || path.equals("/api/auth/login")
                || path.startsWith("/api/webhooks/")) {
            return invocation.proceed();
        }

        String userId = authenticateFromAzureAD(request);
        if (userId == null) {
            userId = authenticateFromApiKey(request);
        }
        if (userId == null) {
            throw new UnauthorizedException("authentication required");
        }

        invocation.context().put(AuthContext.USER_ID_KEY, userId);
        return invocation.proceed();
    }

    private String authenticateFromAzureAD(core.framework.web.Request request) {
        var email = request.header("X-Auth-Request-Email");
        if (email.isEmpty() || email.get().isBlank()) return null;

        var userId = email.get().trim().toLowerCase(Locale.ROOT);
        ensureUser(userId, request.header("X-Auth-Request-User").orElse(userId));
        return userId;
    }

    private String authenticateFromApiKey(core.framework.web.Request request) {
        var auth = request.header("Authorization");
        if (auth.isEmpty()) return null;

        var value = auth.get();
        if (!value.startsWith("Bearer cai_")) return null;

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
}