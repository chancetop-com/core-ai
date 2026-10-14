package ai.core.server.runtime;

import ai.core.api.server.runtime.RuntimeLimitsRequest;
import ai.core.api.server.runtime.RuntimeLimitsView;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.User;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;

import java.time.ZonedDateTime;

/**
 * Reads and writes the runtime governance limits on the shared system settings document. Kept out of
 * {@code SystemSettingsService} on purpose: that service owns the model/integration settings and is
 * already at its file-size budget, and these limits have their own validation and their own admin page.
 *
 * @author stephen
 */
public class RuntimeSettingsService {
    static final String SETTINGS_ID = "default";

    @Inject
    MongoCollection<SystemSettings> systemSettingsCollection;
    @Inject
    MongoCollection<User> userCollection;

    public RuntimeLimitsView get(String userId) {
        requireAdmin(userId);
        var entity = entity();
        var view = toView(RuntimeLimits.resolve(entity));
        view.updatedBy = entity == null ? null : entity.updatedBy;
        view.updatedAt = entity == null ? null : entity.updatedAt;
        return view;
    }

    public RuntimeLimitsView update(RuntimeLimitsRequest request, String userId) {
        requireAdmin(userId);
        if (request == null) throw new BadRequestException("request is required");
        validate(request);

        var entity = entity();
        boolean created = entity == null;
        if (created) {
            entity = new SystemSettings();
            entity.id = SETTINGS_ID;
            entity.createdBy = userId;
            entity.createdAt = ZonedDateTime.now();
        }
        apply(entity, request);
        entity.updatedBy = userId;
        entity.updatedAt = ZonedDateTime.now();
        if (created) {
            systemSettingsCollection.insert(entity);
        } else {
            systemSettingsCollection.replace(entity);
        }
        return get(userId);
    }

    private void apply(SystemSettings entity, RuntimeLimitsRequest request) {
        entity.runtimeAdmissionEnabled = request.admissionEnabled;
        entity.runtimeHeapWatermarkPercent = request.heapWatermarkPercent;
        entity.runtimeMaxInFlightTurnsPerPod = request.maxInFlightTurnsPerPod;
        entity.runtimeMaxInFlightTurnsGlobal = request.maxInFlightTurnsGlobal;
        entity.runtimeMaxSessionsPerPod = request.maxSessionsPerPod;
        entity.runtimeMaxSessionsPerUser = request.maxSessionsPerUser;
        entity.runtimeMaxConcurrentTurnsPerUser = request.maxConcurrentTurnsPerUser;
        entity.runtimeMaxQueuedMessagesPerSession = request.maxQueuedMessagesPerSession;
        entity.runtimeTurnTimeoutSeconds = request.turnTimeoutSeconds;
        entity.runtimeSseMaxQueueDepth = request.sseMaxQueueDepth;
        entity.runtimeSseMaxQueueBytes = request.sseMaxQueueBytes;
        entity.runtimeRunQueueCapacity = request.runQueueCapacity;
        entity.runtimeMaxSandboxesPerPod = request.maxSandboxesPerPod;
        entity.runtimeMaxSandboxesPerUser = request.maxSandboxesPerUser;
        entity.runtimeMaxSandboxMemoryMbPerUser = request.maxSandboxMemoryMbPerUser;
        entity.runtimeMaxSandboxesPerAgent = request.maxSandboxesPerAgent;
        entity.runtimeSpanIngestQueueCapacity = request.spanIngestQueueCapacity;
    }

    private void validate(RuntimeLimitsRequest request) {
        if (request.heapWatermarkPercent != null
                && (request.heapWatermarkPercent < 50 || request.heapWatermarkPercent > 99)) {
            throw new BadRequestException("heapWatermarkPercent must be between 50 and 99");
        }
        requirePositive(request.maxInFlightTurnsPerPod, "maxInFlightTurnsPerPod");
        requirePositive(request.maxSessionsPerPod, "maxSessionsPerPod");
        requirePositive(request.maxSessionsPerUser, "maxSessionsPerUser");
        requirePositive(request.maxConcurrentTurnsPerUser, "maxConcurrentTurnsPerUser");
        requirePositive(request.maxQueuedMessagesPerSession, "maxQueuedMessagesPerSession");
        requirePositive(request.sseMaxQueueDepth, "sseMaxQueueDepth");
        requirePositive(request.sseMaxQueueBytes, "sseMaxQueueBytes");
        requirePositive(request.runQueueCapacity, "runQueueCapacity");
        requirePositive(request.maxSandboxesPerPod, "maxSandboxesPerPod");
        requirePositive(request.maxSandboxesPerUser, "maxSandboxesPerUser");
        requirePositive(request.maxSandboxMemoryMbPerUser, "maxSandboxMemoryMbPerUser");
        requirePositive(request.spanIngestQueueCapacity, "spanIngestQueueCapacity");
        requireNonNegative(request.maxInFlightTurnsGlobal, "maxInFlightTurnsGlobal");
        requireNonNegative(request.turnTimeoutSeconds, "turnTimeoutSeconds");
        requireNonNegative(request.maxSandboxesPerAgent, "maxSandboxesPerAgent");
    }

    private void requirePositive(Number value, String field) {
        if (value == null) return;
        if (value.longValue() <= 0) throw new BadRequestException(field + " must be greater than 0");
    }

    private void requireNonNegative(Number value, String field) {
        if (value == null) return;
        if (value.longValue() < 0) throw new BadRequestException(field + " must not be negative");
    }

    private RuntimeLimitsView toView(RuntimeLimits limits) {
        var view = new RuntimeLimitsView();
        view.admissionEnabled = limits.admissionEnabled;
        view.heapWatermarkPercent = limits.heapWatermarkPercent;
        view.maxInFlightTurnsPerPod = limits.maxInFlightTurnsPerPod;
        view.maxInFlightTurnsGlobal = limits.maxInFlightTurnsGlobal;
        view.maxSessionsPerPod = limits.maxSessionsPerPod;
        view.maxSessionsPerUser = limits.maxSessionsPerUser;
        view.maxConcurrentTurnsPerUser = limits.maxConcurrentTurnsPerUser;
        view.maxQueuedMessagesPerSession = limits.maxQueuedMessagesPerSession;
        view.turnTimeoutSeconds = limits.turnTimeoutSeconds;
        view.sseMaxQueueDepth = limits.sseMaxQueueDepth;
        view.sseMaxQueueBytes = limits.sseMaxQueueBytes;
        view.runQueueCapacity = limits.runQueueCapacity;
        view.maxSandboxesPerPod = limits.maxSandboxesPerPod;
        view.maxSandboxesPerUser = limits.maxSandboxesPerUser;
        view.maxSandboxMemoryMbPerUser = limits.maxSandboxMemoryMbPerUser;
        view.maxSandboxesPerAgent = limits.maxSandboxesPerAgent;
        view.spanIngestQueueCapacity = limits.spanIngestQueueCapacity;
        return view;
    }

    SystemSettings entity() {
        return systemSettingsCollection.get(SETTINGS_ID).orElse(null);
    }

    /** Shared by the read-side services so every runtime endpoint keeps the same admin gate. */
    public void requireAdminUser(String userId) {
        requireAdmin(userId);
    }

    private void requireAdmin(String userId) {
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }
}
