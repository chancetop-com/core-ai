package ai.core.server.user;

import ai.core.api.server.user.ApiKeyView;
import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.api.server.user.UserView;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;

/**
 * @author stephen
 */
public class UserService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int API_KEY_BYTES = 32;

    @Inject
    MongoCollection<User> userCollection;

    public UserView me(String userId) {
        var user = userCollection.get(userId)
            .orElseThrow(() -> new RuntimeException("user not found, id=" + userId));
        return toView(user);
    }

    public ApiKeyView getApiKey(String userId) {
        var user = userCollection.get(userId)
            .orElseThrow(() -> new RuntimeException("user not found, id=" + userId));
        if (user.apiKey == null) return null;
        var view = new ApiKeyView();
        view.apiKey = user.apiKey;
        view.createdAt = user.apiKeyCreatedAt;
        return view;
    }

    public GenerateApiKeyResponse generateApiKey(String userId) {
        var user = userCollection.get(userId)
            .orElseThrow(() -> new RuntimeException("user not found, id=" + userId));

        var bytes = new byte[API_KEY_BYTES];
        RANDOM.nextBytes(bytes);
        var apiKey = "coreai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        user.apiKey = apiKey;
        user.apiKeyCreatedAt = ZonedDateTime.now();
        userCollection.replace(user);

        var response = new GenerateApiKeyResponse();
        response.apiKey = apiKey;
        return response;
    }

    private UserView toView(User entity) {
        var view = new UserView();
        view.id = entity.id;
        view.name = entity.name;
        view.hasApiKey = entity.apiKey != null;
        view.createdAt = entity.createdAt;
        view.lastLoginAt = entity.lastLoginAt;
        return view;
    }
}
