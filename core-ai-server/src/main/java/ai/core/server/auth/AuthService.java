package ai.core.server.auth;

import ai.core.api.server.auth.ListUsersResponse;
import ai.core.api.server.auth.LoginResponse;
import ai.core.api.server.auth.RegisterResponse;
import ai.core.api.server.apiuser.response.OutboundCallerHeaderView;
import ai.core.api.server.apiuser.response.ResourcePermissionView;
import ai.core.api.server.user.GenerateApiKeyResponse;
import ai.core.server.apiuser.ApiUserService;
import ai.core.server.domain.OutboundCallerHeaderConfig;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
            user.apiKeyCreatedAt = ZonedDateTime.now();
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
        user.apiKeyCreatedAt = ZonedDateTime.now();
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

        if ("api".equals(user.userType)) {
            throw new UnauthorizedException("api user cannot login");
        }

        if (user.passwordHash == null || !verifyPassword(password, user.passwordHash)) {
            throw new UnauthorizedException("invalid email or password");
        }

        if (!STATUS_ACTIVE.equals(user.status)) {
            throw new UnauthorizedException("account pending approval, please wait for admin to approve");
        }

        if (user.apiKey == null) {
            user.apiKey = generateApiKey();
            user.apiKeyCreatedAt = ZonedDateTime.now();
        }
        user.lastLoginAt = ZonedDateTime.now();
        userCollection.replace(user);

        var response = new LoginResponse();
        response.apiKey = user.apiKey;
        response.userId = user.id;
        response.name = user.name;
        response.role = user.role;
        return response;
    }

    public void updateUserStatus(String adminUserId, String targetEmail, String newStatus) {
        requireAdmin(adminUserId);

        var normalizedEmail = targetEmail.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new BadRequestException("user not found: " + targetEmail));

        if (!STATUS_ACTIVE.equals(newStatus) && !STATUS_PENDING.equals(newStatus)) {
            throw new BadRequestException("invalid status, must be 'active' or 'pending'");
        }

        user.status = newStatus;
        userCollection.replace(user);
        LOGGER.info("user status updated, email={}, newStatus={}", normalizedEmail, newStatus);
    }

    public void deleteUser(String adminUserId, String targetEmail) {
        requireAdmin(adminUserId);

        var normalizedEmail = targetEmail.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new BadRequestException("user not found: " + targetEmail));

        if ("admin".equals(user.role)) {
            throw new BadRequestException("cannot delete admin user");
        }

        userCollection.delete(normalizedEmail);
        LOGGER.info("user deleted, email={}", normalizedEmail);
    }

    public GenerateApiKeyResponse generateApiKeyForUser(String adminUserId, String targetEmail) {
        requireAdmin(adminUserId);

        var normalizedEmail = targetEmail.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new BadRequestException("user not found: " + targetEmail));

        user.apiKey = generateApiKey();
        user.apiKeyCreatedAt = ZonedDateTime.now();
        userCollection.replace(user);
        LOGGER.info("api key generated for user, email={}", normalizedEmail);

        var response = new GenerateApiKeyResponse();
        response.apiKey = user.apiKey;
        return response;
    }

    public void revokeApiKey(String adminUserId, String targetEmail) {
        requireAdmin(adminUserId);

        var normalizedEmail = targetEmail.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new BadRequestException("user not found: " + targetEmail));

        if (user.apiKey == null) {
            throw new BadRequestException("user does not have an api key");
        }

        user.apiKey = null;
        user.apiKeyCreatedAt = null;
        userCollection.replace(user);
        LOGGER.info("api key revoked for user, email={}", normalizedEmail);
    }

    public void updateUserRole(String adminUserId, String targetEmail, String newRole) {
        requireAdmin(adminUserId);

        if (adminUserId.equals(targetEmail.toLowerCase(Locale.getDefault()))) {
            throw new BadRequestException("cannot change your own role");
        }

        if (!"user".equals(newRole) && !"admin".equals(newRole)) {
            throw new BadRequestException("invalid role, must be 'user' or 'admin'");
        }

        var normalizedEmail = targetEmail.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new BadRequestException("user not found: " + targetEmail));

        user.role = newRole;
        userCollection.replace(user);
        LOGGER.info("user role updated, email={}, newRole={}", normalizedEmail, newRole);
    }

    public void resetUserPassword(String adminUserId, String targetEmail, String newPassword) {
        requireAdmin(adminUserId);

        if (newPassword == null || newPassword.length() < 6) {
            throw new BadRequestException("password must be at least 6 characters");
        }

        var normalizedEmail = targetEmail.toLowerCase(Locale.getDefault());
        var user = userCollection.get(normalizedEmail)
            .orElseThrow(() -> new BadRequestException("user not found: " + targetEmail));

        user.passwordHash = hashPassword(newPassword);
        userCollection.replace(user);
        LOGGER.info("password reset for user, email={}", normalizedEmail);
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

        var query = new Query();
        query.filter = Filters.in("user_type", "internal", ApiUserService.USER_TYPE_API);
        query.sort = Sorts.ascending("created_at");
        var users = userCollection.find(query);
        var ownerIds = users.stream().map(user -> user.ownerId).filter(Objects::nonNull).distinct().toList();
        var ownerNames = new HashMap<String, String>();
        for (var ownerId : ownerIds) {
            ownerNames.put(ownerId, userCollection.get(ownerId).map(owner -> owner.name).orElse(ownerId));
        }
        var response = new ListUsersResponse();
        response.users = new ArrayList<>(users.size());
        for (var user : users) {
            var view = new ListUsersResponse.UserStatusView();
            view.email = user.email;
            view.userId = user.id;
            view.userType = user.userType;
            view.externalId = user.externalId;
            view.name = user.name;
            view.role = user.role;
            view.status = user.status;
            view.createdAt = user.createdAt;
            view.hasApiKey = user.apiKey != null;
            view.apiKeyCreatedAt = user.apiKeyCreatedAt;
            view.apiKey = user.apiKey;
            view.ownerId = user.ownerId;
            if (user.ownerId != null) view.ownerName = ownerNames.get(user.ownerId);
            view.createdBy = user.createdBy;
            if (user.permissions != null && !user.permissions.isEmpty()) {
                view.permissions = user.permissions.stream().map(p -> {
                    var pv = new ResourcePermissionView();
                    pv.resourceType = p.resourceType;
                    pv.resourceId = p.resourceId;
                    return pv;
                }).toList();
            }
            view.inputTokenQuota = user.quotaInputTokens;
            view.outputTokenQuota = user.quotaOutputTokens;
            view.quotaConsumedInputTokens = user.quotaConsumedInputTokens;
            view.quotaConsumedOutputTokens = user.quotaConsumedOutputTokens;
            view.outboundCallerHeaders = toCallerHeaderViews(user.outboundCallerHeaders);
            response.users.add(view);
        }
        return response;
    }

    private List<OutboundCallerHeaderView> toCallerHeaderViews(List<OutboundCallerHeaderConfig> configs) {
        if (configs == null || configs.isEmpty()) return null;
        return configs.stream().map(c -> {
            var cv = new OutboundCallerHeaderView();
            cv.headerName = c.headerName;
            cv.valueSource = c.valueSource;
            return cv;
        }).toList();
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
        return "coreai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
