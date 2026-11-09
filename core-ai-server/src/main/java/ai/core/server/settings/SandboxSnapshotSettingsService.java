package ai.core.server.settings;

import ai.core.api.server.settings.SandboxSnapshotSettingsRequest;
import ai.core.api.server.settings.SandboxSnapshotSettingsView;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.SystemSettings;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.RoleRegistry;
import com.mongodb.MongoException;
import core.framework.db.UncheckedSQLException;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;

import java.math.BigDecimal;
import java.time.ZonedDateTime;

/**
 * @author xander
 */
public class SandboxSnapshotSettingsService {
    private static final String SETTINGS_ID = "sandbox-snapshot";
    private static final int DEFAULT_RETENTION_DAYS = 14;
    private static final int MAX_RETENTION_DAYS = 3650;

    @Inject
    SystemSettingsStore systemSettingsStore;
    @Inject
    PermissionService permissionService;

    public SandboxSnapshotSettingsView get(String userId) {
        requirePermission(userId);
        return toView(entity());
    }

    public SandboxSnapshotSettingsView update(SandboxSnapshotSettingsRequest request, String userId) {
        requirePermission(userId);
        int days = validateRetentionDays(request);
        var now = ZonedDateTime.now();
        var settings = entity();
        if (settings == null) {
            settings = new SystemSettings();
            settings.id = SETTINGS_ID;
            settings.sandboxSnapshotRetentionDays = days;
            settings.createdBy = userId;
            settings.createdAt = now;
            settings.updatedBy = userId;
            settings.updatedAt = now;
            try {
                systemSettingsStore.insert(settings);
            } catch (RuntimeException e) {
                if (!isDuplicateKey(e)) throw e;
                settings = systemSettingsStore.get(SETTINGS_ID)
                        .orElseThrow(() -> new IllegalStateException("sandbox snapshot settings were not saved"));
                settings.sandboxSnapshotRetentionDays = days;
                settings.updatedBy = userId;
                settings.updatedAt = now;
                systemSettingsStore.replace(settings);
            }
        } else {
            settings.sandboxSnapshotRetentionDays = days;
            settings.updatedBy = userId;
            settings.updatedAt = now;
            systemSettingsStore.replace(settings);
        }
        return toView(settings);
    }

    public int retentionDays() {
        return configuredRetentionDays(entity());
    }

    private int validateRetentionDays(SandboxSnapshotSettingsRequest request) {
        if (request == null || request.retentionDays == null) {
            throw new BadRequestException("retention_days is required");
        }
        if (request.retentionDays.compareTo(BigDecimal.ONE) < 0
                || request.retentionDays.compareTo(BigDecimal.valueOf(MAX_RETENTION_DAYS)) > 0) {
            throw new BadRequestException("retention_days must be between 1 and " + MAX_RETENTION_DAYS);
        }
        try {
            return request.retentionDays.intValueExact();
        } catch (ArithmeticException e) {
            throw new BadRequestException("retention_days must be an integer", "INVALID_RETENTION_DAYS", e);
        }
    }

    private void requirePermission(String userId) {
        if (userId == null || userId.isBlank() || permissionService.isApiUser(userId)) {
            throw new ForbiddenException("permission required: " + PermissionCodes.SANDBOX_SNAPSHOT_MANAGE);
        }
        var permissions = permissionService.permissionsOf(userId);
        if (!permissions.contains(RoleRegistry.ALL_PERMISSIONS) && !permissions.contains(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE)) {
            throw new ForbiddenException("permission required: " + PermissionCodes.SANDBOX_SNAPSHOT_MANAGE);
        }
    }

    private int configuredRetentionDays(SystemSettings settings) {
        return settings == null || settings.sandboxSnapshotRetentionDays == null
                ? DEFAULT_RETENTION_DAYS : settings.sandboxSnapshotRetentionDays;
    }

    private SandboxSnapshotSettingsView toView(SystemSettings settings) {
        var view = new SandboxSnapshotSettingsView();
        view.retentionDays = configuredRetentionDays(settings);
        view.updatedBy = settings == null ? null : settings.updatedBy;
        view.updatedAt = settings == null ? null : settings.updatedAt;
        return view;
    }

    private boolean isDuplicateKey(RuntimeException e) {
        if (e instanceof MongoException mongo) return mongo.getCode() == 11000;
        return e instanceof UncheckedSQLException sql && sql.errorType == UncheckedSQLException.ErrorType.INTEGRITY_CONSTRAINT_VIOLATION;
    }

    private SystemSettings entity() {
        return systemSettingsStore.get(SETTINGS_ID).orElse(null);
    }
}
