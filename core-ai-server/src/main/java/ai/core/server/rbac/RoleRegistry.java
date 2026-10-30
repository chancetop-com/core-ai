package ai.core.server.rbac;

import ai.core.server.domain.SystemSettings;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Role definitions: built-in defaults overridable via {@code system_settings.rbac_roles}
 * (JSON map of role name -> permission codes). The {@code admin} role is an implicit
 * wildcard and never part of the map. Effective roles are cached for 5 seconds.
 * <p>
 * The same snapshot also carries {@code system_settings.rbac_trace_scopes}
 * (JSON map of role name -> trace data scope), so permissions and trace visibility
 * always resolve from one consistent read.
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
                    // mcp.call intentionally NOT granted to the default `user` role in P0:
                    // it executes MCP tools (side effects) with only admin-provided registration.
                    // Grant it via Roles UI when Hub rollout is confirmed. See PermissionCodes.MCP_CALL.
                    PermissionCodes.APITOOL_VIEW, PermissionCodes.APITOOL_MANAGE,
                    // apitool.call = reach any Service API operation through the hub / MCP endpoint.
                    // Already possible today via /api/api-tools/mcp and mounted api-app refs; the hub
                    // adds audit + per-app whitelisting (api users). mcp.call stays admin-granted.
                    PermissionCodes.APITOOL_CALL,
                    PermissionCodes.SKILL_VIEW, PermissionCodes.SKILL_MANAGE,
                    PermissionCodes.DATASET_VIEW, PermissionCodes.DATASET_MANAGE,
                    // projects are shared business containers: regular members can view and drive them
                    PermissionCodes.PROJECT_VIEW, PermissionCodes.PROJECT_MANAGE,
                    PermissionCodes.EXPERIMENT_VIEW, PermissionCodes.EXPERIMENT_REPLAY, PermissionCodes.NOTIFICATION_VIEW,
                    // read-only model catalog (no keys): agent authors need it to pick a model
                    PermissionCodes.GATEWAY_VIEW),
            ROLE_MEMBER, List.of(
                    PermissionCodes.DASHBOARD_VIEW, PermissionCodes.CHAT_USE, PermissionCodes.NOTIFICATION_VIEW));

    @Inject
    MongoCollection<SystemSettings> systemSettingsCollection;

    private volatile Snapshot cached;
    private volatile long cachedAt;

    public List<String> permissionsOf(String role) {
        if (ROLE_ADMIN.equals(role)) return List.of(ALL_PERMISSIONS);
        return effectiveRoles().getOrDefault(role, List.of());
    }

    public Map<String, List<String>> effectiveRoles() {
        return snapshot().roles();
    }

    /**
     * Effective trace data scope for a role: {@code admin} is always ALL, unconfigured roles are SELF.
     */
    public TraceScopeMode traceScope(String role) {
        if (ROLE_ADMIN.equals(role)) return TraceScopeMode.ALL;
        return TraceScopeMode.fromStringOrSelf(snapshot().traceScopes().get(role));
    }

    /**
     * Effective scope of every configured role, including unconfigured ones at {@code self}.
     */
    public Map<String, String> traceScopes() {
        var scopes = new LinkedHashMap<String, String>();
        for (var role : effectiveRoles().keySet()) {
            scopes.put(role, traceScope(role).value());
        }
        return scopes;
    }

    public void updateRoles(Map<String, List<String>> roles) {
        updateRoles(roles, null);
    }

    /**
     * Saves role permissions and (optionally) trace scopes in one read-modify-replace, so two
     * independent writes cannot overwrite each other. {@code traceScopes == null} keeps the current value.
     */
    public void updateRoles(Map<String, List<String>> roles, Map<String, String> traceScopes) {
        validate(roles);
        var encodedScopes = traceScopes != null ? JsonUtil.toJson(storedTraceScopes(traceScopes)) : null;
        var entity = systemSettingsCollection.get(SETTINGS_ID).orElse(null);
        if (entity == null) {
            entity = new SystemSettings();
            entity.id = SETTINGS_ID;
            entity.createdAt = ZonedDateTime.now();
            entity.rbacRoles = JsonUtil.toJson(roles);
            entity.rbacTraceScopes = encodedScopes;
            systemSettingsCollection.insert(entity);
        } else {
            entity.rbacRoles = JsonUtil.toJson(roles);
            if (encodedScopes != null) entity.rbacTraceScopes = encodedScopes;
            entity.updatedAt = ZonedDateTime.now();
            systemSettingsCollection.replace(entity);
        }
        synchronized (this) {
            cached = null;
        }
    }

    private Snapshot snapshot() {
        var now = System.currentTimeMillis();
        var current = cached;
        if (current != null && now - cachedAt < CACHE_TTL.toMillis()) return current;
        synchronized (this) {
            current = cached;
            if (current != null && System.currentTimeMillis() - cachedAt < CACHE_TTL.toMillis()) return current;
            current = load();
            cached = current;
            cachedAt = System.currentTimeMillis();
            return current;
        }
    }

    private Snapshot load() {
        var roles = new LinkedHashMap<>(DEFAULT_ROLES);
        var traceScopes = new HashMap<String, String>();
        var entity = systemSettingsCollection.get(SETTINGS_ID).orElse(null);
        if (entity != null) {
            mergeRoles(roles, entity.rbacRoles);
            mergeTraceScopes(traceScopes, entity.rbacTraceScopes);
        }
        return new Snapshot(Collections.unmodifiableMap(roles), Collections.unmodifiableMap(traceScopes));
    }

    private void mergeRoles(Map<String, List<String>> roles, String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        for (var entry : JsonUtil.toMap(encoded).entrySet()) {
            if (ROLE_ADMIN.equals(entry.getKey())) continue;   // admin is an implicit wildcard
            roles.put(entry.getKey(), toPermissionList(entry.getValue()));
        }
    }

    private void mergeTraceScopes(Map<String, String> traceScopes, String encoded) {
        if (encoded == null || encoded.isBlank()) return;
        for (var entry : JsonUtil.toMap(encoded).entrySet()) {
            if (ROLE_ADMIN.equals(entry.getKey())) continue;
            var value = entry.getValue() instanceof String string ? string : null;
            var mode = TraceScopeMode.fromStringOrSelf(value);
            // self is the default and never persisted; unknown values degrade to self instead of failing reads
            if (mode != TraceScopeMode.SELF) traceScopes.put(entry.getKey(), mode.value());
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

    /**
     * Drops {@code self} entries (the implicit default) and rejects invalid modes or an admin override.
     */
    private Map<String, String> storedTraceScopes(Map<String, String> traceScopes) {
        var stored = new LinkedHashMap<String, String>();
        for (var entry : traceScopes.entrySet()) {
            var role = entry.getKey();
            if (role == null || role.isBlank()) throw new BadRequestException("role name must not be blank");
            if (ROLE_ADMIN.equals(role)) throw new BadRequestException("admin trace scope is implicit and cannot be configured");
            var mode = TraceScopeMode.fromString(entry.getValue());
            if (mode != TraceScopeMode.SELF) stored.put(role, mode.value());
        }
        return stored;
    }

    private record Snapshot(Map<String, List<String>> roles, Map<String, String> traceScopes) {
    }
}
