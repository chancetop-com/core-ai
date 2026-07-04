package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.api.server.settings.SystemSettingsView;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.User;
import ai.core.server.memory.AgentMemoryConsolidationJob;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class SystemSettingsService {
    private static final String SETTINGS_ID = "default";

    public String defaultMemoryExtractionModel = AgentMemoryConsolidationJob.DEFAULT_EXTRACTION_MODEL;

    @Inject
    MongoCollection<SystemSettings> systemSettingsCollection;
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;
    @Inject
    MongoCollection<User> userCollection;

    public SystemSettingsView get(String userId) {
        requireAdmin(userId);
        return toView(entity());
    }

    public SystemSettingsView update(SystemSettingsRequest request, String userId) {
        requireAdmin(userId);
        if (request == null) throw new BadRequestException("request is required");
        var memoryExtractionModel = normalizeModel(request.memoryExtractionModel);
        validateMemoryExtractionModel(memoryExtractionModel);

        var now = ZonedDateTime.now();
        var entity = entity();
        if (entity == null) {
            entity = new SystemSettings();
            entity.id = SETTINGS_ID;
            entity.createdBy = userId;
            entity.createdAt = now;
            entity.memoryExtractionModel = memoryExtractionModel;
            entity.updatedBy = userId;
            entity.updatedAt = now;
            systemSettingsCollection.insert(entity);
        } else {
            entity.memoryExtractionModel = memoryExtractionModel;
            entity.updatedBy = userId;
            entity.updatedAt = now;
            systemSettingsCollection.replace(entity);
        }
        return toView(entity);
    }

    public String memoryExtractionModel() {
        var entity = entity();
        var configured = entity == null ? null : normalizeModel(entity.memoryExtractionModel);
        return configured == null ? defaultMemoryExtractionModel : configured;
    }

    private SystemSettings entity() {
        return systemSettingsCollection.get(SETTINGS_ID).orElse(null);
    }

    private void validateMemoryExtractionModel(String model) {
        if (model == null) return;
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("model_id", model),
                Filters.eq("enabled", true),
                Filters.eq("endpoint_types", "chat.completions")
        );
        query.limit = 1;
        if (gatewayModelCollection.find(query).isEmpty()) {
            throw new BadRequestException("memoryExtractionModel must be an enabled chat gateway model: " + model);
        }
    }

    private SystemSettingsView toView(SystemSettings entity) {
        var view = new SystemSettingsView();
        view.memoryExtractionModel = entity == null ? null : normalizeModel(entity.memoryExtractionModel);
        view.defaultMemoryExtractionModel = defaultMemoryExtractionModel;
        view.createdBy = entity == null ? null : entity.createdBy;
        view.updatedBy = entity == null ? null : entity.updatedBy;
        view.createdAt = entity == null ? null : entity.createdAt;
        view.updatedAt = entity == null ? null : entity.updatedAt;
        return view;
    }

    private void requireAdmin(String userId) {
        if (userId == null) throw new ForbiddenException("admin required");
        var user = userCollection.get(userId).orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) throw new ForbiddenException("admin required");
    }

    private String normalizeModel(String value) {
        if (value == null) return null;
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
