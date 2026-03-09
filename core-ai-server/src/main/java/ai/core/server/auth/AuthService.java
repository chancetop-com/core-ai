package ai.core.server.auth;

import ai.core.api.server.auth.ListUsersResponse;
import ai.core.api.server.auth.LoginResponse;
import ai.core.api.server.auth.RegisterResponse;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ConflictException;
import core.framework.web.exception.UnauthorizedException;
import org.bouncycastle.crypto.generators.OpenBSDBCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Locale;

/**
 * @author stephen
 */
public class AuthService {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BCRYPT_COST = 10;
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_PENDING = "pending";

    @Inject
    MongoCollection<User> userCollection;

    public String adminEmail;
    public String adminPassword;
    public String adminName;

    public void initialize() {
        var existing = userCollection.get(adminEmail.toLowerCase(Locale.getDefault()));
        if (existing.isPresent()) return;

        var admin = new User();
        admin.id = adminEmail.toLowerCase(Locale.getDefault());
        admin.email = adminEmail.toLowerCase(Locale.getDefault());
        admin.name = adminName;
        admin.passwordHash = hashPassword(adminPassword);
        admin.role = "admin";
        admin.status = STATUS_ACTIVE;
        admin.createdAt = ZonedDateTime.now();
        userCollection.insert(admin);
        LOGGER.info("admin user created, email={}", adminEmail);
    }

    public RegisterResponse register(String email, String password, String name) {
        var normalizedEmail = email.toLowerCase(Locale.getDefault());
        var existing = userCollection.get(normalizedEmail);

        if (existing.isPresent()) {
            var user = existing.get();
            if (user.passwordHash != null) {
                throw new ConflictException("user already exists");
            }
            // pre-approved user by admin invite, set password to complete registration
            user.passwordHash = hashPassword(password);
            if (name != null) user.name = name;
            user.apiKey = generateApiKey();
            userCollection.replace(user);

            var response = new RegisterResponse();
            response.apiKey = user.apiKey;
            response.userId = user.id;
            return response;
        }

        var user = new User();
        user.id = normalizedEmail;
        user.email = normalizedEmail;
        user.name = name != null ? name : email;
        user.passwordHash = hashPassword(password);
        user.status = STATUS_PENDING;
        user.apiKey = generateApiKey();
        user.createdAt = ZonedDateTime.now();
        userCollection.insert(user);

        var response = new RegisterResponse();
        response.apiKey = user.apiKey;
        response.userId = user.id;
        return response;
    }

    public LoginResponse login(String email, String password) {
        var normalizedEmail = email.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new UnauthorizedException("invalid email or password"));

        if (user.passwordHash == null || !verifyPassword(password, user.passwordHash)) {
            throw new UnauthorizedException("invalid email or password");
        }

        if (user.apiKey == null) {
            user.apiKey = generateApiKey();
        }
        user.lastLoginAt = ZonedDateTime.now();
        userCollection.replace(user);

        var response = new LoginResponse();
        response.apiKey = user.apiKey;
        response.userId = user.id;
        return response;
    }

    public void invite(String adminUserId, String email) {
        requireAdmin(adminUserId);

        var normalizedEmail = email.toLowerCase(Locale.getDefault());
        var existing = userCollection.get(normalizedEmail);
        if (existing.isPresent()) {
            var user = existing.get();
            if (STATUS_ACTIVE.equals(user.status)) {
                throw new BadRequestException("user is already active");
            }
            user.status = STATUS_ACTIVE;
            userCollection.replace(user);
            LOGGER.info("user approved, email={}", normalizedEmail);
        } else {
            var user = new User();
            user.id = normalizedEmail;
            user.email = normalizedEmail;
            user.name = normalizedEmail;
            user.status = STATUS_ACTIVE;
            user.createdAt = ZonedDateTime.now();
            userCollection.insert(user);
            LOGGER.info("user pre-approved, email={}", normalizedEmail);
        }
    }

    public ListUsersResponse listUsers(String adminUserId) {
        requireAdmin(adminUserId);

        var users = userCollection.find(Filters.exists("email"));
        var response = new ListUsersResponse();
        response.users = new ArrayList<>();
        for (var user : users) {
            var view = new ListUsersResponse.UserStatusView();
            view.email = user.email;
            view.name = user.name;
            view.role = user.role;
            view.status = user.status;
            view.createdAt = user.createdAt.toString();
            response.users.add(view);
        }
        return response;
    }

    private void requireAdmin(String userId) {
        var user = userCollection.get(userId)
            .orElseThrow(() -> new UnauthorizedException("user not found"));
        if (!"admin".equals(user.role)) {
            throw new UnauthorizedException("admin required");
        }
    }

    private String hashPassword(String password) {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return OpenBSDBCrypt.generate(password.toCharArray(), salt, BCRYPT_COST);
    }

    private boolean verifyPassword(String password, String hash) {
        return OpenBSDBCrypt.checkPassword(hash, password.toCharArray());
    }

    private String generateApiKey() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "cai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
