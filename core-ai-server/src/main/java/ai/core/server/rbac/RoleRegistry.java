package ai.core.server.rbac;

import ai.core.server.domain.SystemSettings;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Role definitions: built-in defaults overridable via {@code system_settings.rbac_roles}
 * (JSON map of role name -> permission codes). The {@code admin} role is an implicit
 * wildcard and never part of the map. Effective roles are cached for 5 seconds.
 *
 * @author stephen
 */
public class RoleRegistry {
    public static final String ROLE_ADMIN = "admin";
    public static final String ROLE_USER = "user";
    public static final String ROLE_MEMBER = "member";
    public static final String ALL_PERMISSIONS = "*";

    private static final String SETTINGS_ID = "default";
    private static final Duration CACHE_TTL = Duration.ofSeconds(5);

    private static final Map<String, List<String>> DEFAULT_ROLES = Map.of(
            ROLE_USER, List.of(
                    PermissionCodes.DASHBOARD_VIEW, PermissionCodes.CHAT_USE, PermissionCodes.TRACE_VIEW,
                    PermissionCodes.AGENT_VIEW, PermissionCodes.AGENT_MANAGE,
                    PermissionCodes.WORKFLOW_VIEW, PermissionCodes.WORKFLOW_MANAGE,
                    PermissionCodes.PROMPT_VIEW, PermissionCodes.PROMPT_MANAGE,
                    PermissionCodes.TRIGGER_VIEW, PermissionCodes.TRIGGER_MANAGE,
                    PermissionCodes.TASK_VIEW,
                    PermissionCodes.TOOL_VIEW,
                    PermissionCodes.APITOOL_VIEW, PermissionCodes.APITOOL_MANAGE,
                    PermissionCodes.SKILL_VIEW, PermissionCodes.SKILL_MANAGE,
                    PermissionCodes.DATASET_VIEW, PermissionCodes.DATASET_MANAGE,
                    // projects are shared business containers: regular members can view and drive them
                    PermissionCodes.PROJECT_VIEW, PermissionCodes.PROJECT_MANAGE,
                    PermissionCodes.EXPERIMENT_VIEW, PermissionCodes.EXPERIMENT_REPLAY, PermissionCodes.NOTIFICATION_VIEW),
            ROLE_MEMBER, List.of(
                    PermissionCodes.DASHBOARD_VIEW, PermissionCodes.CHAT_USE, PermissionCodes.NOTIFICATION_VIEW));

    @Inject
    MongoCollection<SystemSettings> systemSettingsCollection;

    private volatile Map<String, List<String>> cached;
    private volatile long cachedAt;

    public List<String> permissionsOf(String role) {
        if (ROLE_ADMIN.equals(role)) return List.of(ALL_PERMISSIONS);
        return effectiveRoles().getOrDefault(role, List.of());
    }

    public Map<String, List<String>> effectiveRoles() {
        var now = System.currentTimeMillis();
        var current = cached;
        if (current != null && now - cachedAt < CACHE_TTL.toMillis()) return current;
        synchronized (this) {
            current = cached;
            if (current != null && System.currentTimeMillis() - cachedAt < CACHE_TTL.toMillis()) return current;
            var merged = new HashMap<>(DEFAULT_ROLES);
            var entity = systemSettingsCollection.get(SETTINGS_ID).orElse(null);
            if (entity != null && entity.rbacRoles != null && !entity.rbacRoles.isBlank()) {
                for (var entry : JsonUtil.toMap(entity.rbacRoles).entrySet()) {
                    if (ROLE_ADMIN.equals(entry.getKey())) continue;   // admin is an implicit wildcard
                    merged.put(entry.getKey(), toPermissionList(entry.getValue()));
                }
            }
            cached = merged;
            cachedAt = System.currentTimeMillis();
            return merged;
        }
    }

    public void updateRoles(Map<String, List<String>> roles) {
        validate(roles);
        var entity = systemSettingsCollection.get(SETTINGS_ID).orElse(null);
        if (entity == null) {
            entity = new SystemSettings();
            entity.id = SETTINGS_ID;
            entity.createdAt = ZonedDateTime.now();
            entity.rbacRoles = JsonUtil.toJson(roles);
            systemSettingsCollection.insert(entity);
        } else {
            entity.rbacRoles = JsonUtil.toJson(roles);
            entity.updatedAt = ZonedDateTime.now();
            systemSettingsCollection.replace(entity);
        }
        synchronized (this) {
            cached = null;
        }
    }

    private List<String> toPermissionList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        var permissions = new ArrayList<String>(list.size());
        for (var item : list) {
            if (item instanceof String code && !code.isBlank()) permissions.add(code);
        }
        return permissions;
    }

    private void validate(Map<String, List<String>> roles) {
        if (roles == null) throw new BadRequestException("roles is required");
        for (var entry : roles.entrySet()) {
            var role = entry.getKey();
            if (role == null || role.isBlank()) throw new BadRequestException("role name must not be blank");
            if (ROLE_ADMIN.equals(role)) throw new BadRequestException("admin role is implicit and cannot be configured");
            if (entry.getValue() == null) continue;
            for (var permission : entry.getValue()) {
                if (!PermissionCodes.ALL.contains(permission)) {
                    throw new BadRequestException("unknown permission code: " + permission);
                }
            }
        }
    }
}
