package ai.core.server.user;

import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.api.server.user.UserView;
import ai.core.api.server.UserWebService;
import ai.core.server.auth.AuthContext;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * @author stephen
 */
public class UserWebServiceImpl implements UserWebService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int API_KEY_BYTES = 32;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    WebContext webContext;

    @Override
    public UserView me() {
        var userId = AuthContext.userId(webContext);
        var user = userCollection.get(userId)
            .orElseThrow(() -> new RuntimeException("user not found, id=" + userId));
        return toView(user);
    }

    @Override
    public GenerateApiKeyResponse generateApiKey() {
        var userId = AuthContext.userId(webContext);
        var user = userCollection.get(userId)
            .orElseThrow(() -> new RuntimeException("user not found, id=" + userId));

        var bytes = new byte[API_KEY_BYTES];
        RANDOM.nextBytes(bytes);
        var apiKey = "cai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        user.apiKey = apiKey;
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
