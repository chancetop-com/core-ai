package ai.core.server.user;

import ai.core.api.server.user.ApiKeyView;
import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.api.server.user.UserView;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.Base64;

/**
 * @author stephen
 */
public class UserService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int API_KEY_BYTES = 32;
    private static final int BCRYPT_COST = 10;

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

    public void changePassword(String userId, String currentPassword, String newPassword) {
        var user = userCollection.get(userId)
            .orElseThrow(() -> new RuntimeException("user not found, id=" + userId));

        if (user.passwordHash == null || !verifyPassword(currentPassword, user.passwordHash)) {
            throw new BadRequestException("current password is incorrect");
        }

        if (newPassword == null || newPassword.length() < 6) {
            throw new BadRequestException("new password must be at least 6 characters");
        }

        user.passwordHash = hashPassword(newPassword);
        userCollection.replace(user);
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, BCRYPT_COST);
    }

    private boolean verifyPassword(String password, String hash) {
        return OpenBSDBCrypt.checkPassword(hash, password.toCharArray());
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
