package ai.core.server.apiuser;

import ai.core.api.server.apiuser.request.ResourcePermissionRequest;
import ai.core.api.server.apiuser.request.UpdateApiUserConfigRequest;
import ai.core.api.server.apiuser.response.ApiUserQuotaView;
import ai.core.api.server.apiuser.response.ApiUserView;
import ai.core.api.server.apiuser.response.ResourcePermissionView;
import ai.core.server.domain.ResourcePermission;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * API user lifecycle: create (idempotent by ownerId+externalId), query, permissions & quota config.
 *
 * @author stephen
 */
public class ApiUserService {
    public static final String USER_TYPE_API = "api";

    @Inject
    MongoCollection<User> userCollection;

    /**
     * Creates an API user owned by the given manager user (business system).
     * Idempotent: same externalId under the same owner returns the existing user
     * (business systems may retry creation; duplicates are guarded by the partial unique index
     * on (owner_id, external_id) plus a re-fetch after insert conflict).
     */
    public User createApiUser(String ownerId, String externalId, String name) {
        if (ownerId == null || ownerId.isBlank()) throw new BadRequestException("owner required");
        if (externalId == null || externalId.isBlank()) throw new BadRequestException("external_id required");
        var normalizedExternalId = externalId.trim();
        var normalizedName = name != null ? name.trim() : null;

        var existing = findByOwnerAndExternalId(ownerId, normalizedExternalId);
        if (existing != null) return existing;

        var user = new User();
        user.id = "api:" + UUID.randomUUID();
        user.userType = USER_TYPE_API;
        user.ownerId = ownerId;
        user.externalId = normalizedExternalId;
        user.name = normalizedName;
        user.role = "user";
        user.status = "active";
        user.createdAt = ZonedDateTime.now();
        try {
            userCollection.insert(user);
        } catch (RuntimeException e) {
            // concurrent create with same (owner_id, external_id): return the winning record
            var winner = findByOwnerAndExternalId(ownerId, normalizedExternalId);
            if (winner != null) return winner;
            throw e;
        }
        return user;
    }

    /**
     * Creates a manager-type API user (business system subject). Admin-only.
     */
    public User createManager(String name, String createdBy) {
        var user = new User();
        user.id = "api:" + UUID.randomUUID();
        user.userType = USER_TYPE_API;
        user.name = name;
        user.role = "user";
        user.status = "active";
        user.createdBy = createdBy;
        user.createdAt = ZonedDateTime.now();
        userCollection.insert(user);
        return user;
    }

    public User getApiUser(String managerUserId, String userId) {
        var user = requireApiUser(userId);
        requireOwnership(managerUserId, user);
        return user;
    }

    public User updateConfig(String managerUserId, String userId, UpdateApiUserConfigRequest request) {
        var user = requireApiUser(userId);
        requireOwnership(managerUserId, user);
        if (user.ownerId == null) throw new ForbiddenException("cannot configure manager user");
        applyConfig(user, request);
        userCollection.replace(user);
        return user;
    }

    /**
     * Admin surface for configuring any user's permissions & quota (manager users are not configurable).
     */
    public User updateConfigByAdmin(String adminUserId, String userId, UpdateApiUserConfigRequest request) {
        var admin = userCollection.get(adminUserId).orElse(null);
        if (admin == null || !"admin".equals(admin.role)) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElse(null);
        if (user == null) throw new NotFoundException("user not found, userId=" + userId);
        if ("api".equals(user.userType) && user.ownerId == null) throw new ForbiddenException("cannot configure manager user");
        applyConfig(user, request);
        userCollection.replace(user);
        return user;
    }

    private void applyConfig(User user, UpdateApiUserConfigRequest request) {
        if (request.permissions != null) {
            user.permissions = toEntities(request.permissions);
        }
        if (request.inputTokenQuota != null) {
            if (request.inputTokenQuota < 0) throw new BadRequestException("input_token_quota must be >= 0");
            user.quotaInputTokens = request.inputTokenQuota;
            if (request.inputTokenQuota == 0) {
                user.quotaConsumedInputTokens = 0L;
            }
        }
        if (request.outputTokenQuota != null) {
            if (request.outputTokenQuota < 0) throw new BadRequestException("output_token_quota must be >= 0");
            user.quotaOutputTokens = request.outputTokenQuota;
            if (request.outputTokenQuota == 0) {
                user.quotaConsumedOutputTokens = 0L;
            }
        }
    }

    public User updateStatus(String adminUserId, String userId, String status) {
        var user = requireApiUser(userId);
        if (!"active".equals(status) && !"disabled".equals(status)) {
            throw new BadRequestException("invalid status, must be active or disabled");
        }
        user.status = status;
        userCollection.replace(user);
        return user;
    }

    public List<User> listApiUsers(String adminUserId) {
        return userCollection.find(Filters.eq("user_type", USER_TYPE_API));
    }

    public ApiUserView toView(User user) {
        var view = new ApiUserView();
        view.userId = user.id;
        view.externalId = user.externalId;
        view.name = user.name;
        view.status = user.status;
        if (user.permissions != null && !user.permissions.isEmpty()) {
            view.permissions = user.permissions.stream().map(p -> {
                var pv = new ResourcePermissionView();
                pv.resourceType = p.resourceType;
                pv.resourceId = p.resourceId;
                return pv;
            }).toList();
        }
        var quota = new ApiUserQuotaView();
        quota.inputTokenQuota = user.quotaInputTokens;
        quota.outputTokenQuota = user.quotaOutputTokens;
        quota.consumedInputTokens = user.quotaConsumedInputTokens;
        quota.consumedOutputTokens = user.quotaConsumedOutputTokens;
        view.quota = quota;
        return view;
    }

    private User requireApiUser(String userId) {
        var user = userCollection.get(userId).orElse(null);
        if (user == null || !USER_TYPE_API.equals(user.userType)) {
            throw new NotFoundException("api user not found, userId=" + userId);
        }
        return user;
    }

    private void requireOwnership(String managerUserId, User user) {
        if (user.ownerId != null && !user.ownerId.equals(managerUserId)) {
            throw new ForbiddenException("api user does not belong to current manager");
        }
    }

    private User findByOwnerAndExternalId(String ownerId, String externalId) {
        // explicit $type predicates are required for the partial unique index to be usable
        // (MongoDB cannot derive $type from $eq; without them notablescan rejects the query)
        var users = userCollection.find(Filters.and(
                Filters.eq("owner_id", ownerId),
                Filters.eq("external_id", externalId),
                Filters.type("owner_id", "string"),
                Filters.type("external_id", "string")));
        return users.isEmpty() ? null : users.getFirst();
    }

    private List<ResourcePermission> toEntities(List<ResourcePermissionRequest> requests) {
        var permissions = new ArrayList<ResourcePermission>(requests.size());
        for (var request : requests) {
            if (request.resourceType == null || request.resourceType.isBlank()
                    || request.resourceId == null || request.resourceId.isBlank()) {
                throw new BadRequestException("resource_type and resource_id are required");
            }
            var permission = new ResourcePermission();
            permission.resourceType = request.resourceType;
            permission.resourceId = request.resourceId;
            permissions.add(permission);
        }
        return permissions;
    }
}
