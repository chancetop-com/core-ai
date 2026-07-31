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
    public String defaultLlmModel;
    public String defaultLlmMultiModalModel;

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
        var models = normalizeAndValidate(request);

        var now = ZonedDateTime.now();
        var entity = entity();
        if (entity == null) {
            entity = new SystemSettings();
            entity.id = SETTINGS_ID;
            entity.createdBy = userId;
            entity.createdAt = now;
            applyModels(entity, models);
            entity.updatedBy = userId;
            entity.updatedAt = now;
            systemSettingsCollection.insert(entity);
        } else {
            applyModels(entity, models);
            entity.updatedBy = userId;
            entity.updatedAt = now;
            systemSettingsCollection.replace(entity);
        }
        return toView(entity);
    }

    private NormalizedSettings normalizeAndValidate(SystemSettingsRequest request) {
        var memoryExtractionModel = normalizeModel(request.memoryExtractionModel);
        validateMemoryExtractionModel(memoryExtractionModel);
        var llmModel = normalizeModel(request.llmModel);
        validateChatModel(llmModel);
        var llmMultiModalModel = normalizeModel(request.llmMultiModalModel);
        validateChatModel(llmMultiModalModel);
        var captionImageModel = normalizeModel(request.captionImageModel);
        validateChatModel(captionImageModel);
        var imageGenerationModel = normalizeModel(request.imageGenerationModel);
        validateModelExists(imageGenerationModel, "imageGenerationModel");
        var videoGenerationModel = normalizeModel(request.videoGenerationModel);
        validateModelExists(videoGenerationModel, "videoGenerationModel");
        var videoUnderstandingModel = normalizeModel(request.videoUnderstandingModel);
        validateModelExists(videoUnderstandingModel, "videoUnderstandingModel");
        return new NormalizedSettings(memoryExtractionModel, llmModel, llmMultiModalModel, captionImageModel,
                imageGenerationModel, videoGenerationModel, videoUnderstandingModel);
    }

    private void applyModels(SystemSettings entity, NormalizedSettings models) {
        entity.memoryExtractionModel = models.memoryExtractionModel();
        entity.llmModel = models.llmModel();
        entity.llmMultiModalModel = models.llmMultiModalModel();
        entity.captionImageModel = models.captionImageModel();
        entity.imageGenerationModel = models.imageGenerationModel();
        entity.videoGenerationModel = models.videoGenerationModel();
        entity.videoUnderstandingModel = models.videoUnderstandingModel();
    }

    public String memoryExtractionModel() {
        var entity = entity();
        var configured = entity == null ? null : normalizeModel(entity.memoryExtractionModel);
        return configured == null ? defaultMemoryExtractionModel : configured;
    }

    public String llmModel() {
        var entity = entity();
        var configured = entity == null ? null : normalizeModel(entity.llmModel);
        return configured == null ? defaultLlmModel : configured;
    }

    public String llmMultiModalModel() {
        var entity = entity();
        var configured = entity == null ? null : normalizeModel(entity.llmMultiModalModel);
        return configured == null ? defaultLlmMultiModalModel : configured;
    }

    public String captionImageModel() {
        var entity = entity();
        return entity == null ? null : normalizeModel(entity.captionImageModel);
    }

    public String imageGenerationModel() {
        var entity = entity();
        return entity == null ? null : normalizeModel(entity.imageGenerationModel);
    }

    public String videoGenerationModel() {
        var entity = entity();
        return entity == null ? null : normalizeModel(entity.videoGenerationModel);
    }

    public String videoUnderstandingModel() {
        var entity = entity();
        return entity == null ? null : normalizeModel(entity.videoUnderstandingModel);
    }

    private SystemSettings entity() {
        return systemSettingsCollection.get(SETTINGS_ID).orElse(null);
    }

    private void validateMemoryExtractionModel(String model) {
        if (model == null) return;
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("model_id", model),
                Filters.eq("enabled", Boolean.TRUE),
                Filters.eq("endpoint_types", "chat.completions")
        );
        query.limit = 1;
        if (gatewayModelCollection.find(query).isEmpty()) {
            throw new BadRequestException("memoryExtractionModel must be an enabled chat gateway model: " + model);
        }
    }

    private void validateChatModel(String model) {
        if (model == null) return;
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("model_id", model),
                Filters.eq("enabled", Boolean.TRUE),
                Filters.eq("endpoint_types", "chat.completions")
        );
        query.limit = 1;
        if (gatewayModelCollection.find(query).isEmpty()) {
            throw new BadRequestException("llm model must be an enabled gateway model with chat.completions: " + model);
        }
    }

    private void validateModelExists(String model, String field) {
        if (model == null) return;
        var query = new Query();
        query.filter = Filters.and(
                Filters.eq("model_id", model),
                Filters.eq("enabled", Boolean.TRUE)
        );
        query.limit = 1;
        if (gatewayModelCollection.find(query).isEmpty()) {
            throw new BadRequestException(field + " must be an enabled gateway model: " + model);
        }
    }

    private SystemSettingsView toView(SystemSettings entity) {
        var view = new SystemSettingsView();
        view.memoryExtractionModel = entity == null ? null : normalizeModel(entity.memoryExtractionModel);
        view.defaultMemoryExtractionModel = defaultMemoryExtractionModel;
        view.llmModel = entity == null ? null : normalizeModel(entity.llmModel);
        view.defaultLlmModel = defaultLlmModel;
        view.llmMultiModalModel = entity == null ? null : normalizeModel(entity.llmMultiModalModel);
        view.defaultLlmMultiModalModel = defaultLlmMultiModalModel;
        view.captionImageModel = entity == null ? null : normalizeModel(entity.captionImageModel);
        view.imageGenerationModel = entity == null ? null : normalizeModel(entity.imageGenerationModel);
        view.videoGenerationModel = entity == null ? null : normalizeModel(entity.videoGenerationModel);
        view.videoUnderstandingModel = entity == null ? null : normalizeModel(entity.videoUnderstandingModel);
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

    private record NormalizedSettings(String memoryExtractionModel, String llmModel, String llmMultiModalModel,
                                       String captionImageModel, String imageGenerationModel, String videoGenerationModel,
                                       String videoUnderstandingModel) {
    }
}
