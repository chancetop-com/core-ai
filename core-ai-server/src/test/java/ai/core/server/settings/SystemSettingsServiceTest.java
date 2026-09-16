package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.User;
import ai.core.server.gateway.GatewaySecretProtector;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemSettingsServiceTest {
    private SystemSettingsService service;
    private MongoCollection<SystemSettings> settings;
    private MongoCollection<GatewayModelConfig> models;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SystemSettingsService();
        settings = mock(MongoCollection.class);
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        models = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);

        var admin = new User();
        admin.role = "admin";
        when(users.get("admin")).thenReturn(Optional.of(admin));

        service.systemSettingsCollection = settings;
        service.userCollection = users;
        service.gatewayModelCollection = models;
        service.secretProtector = mock(GatewaySecretProtector.class);
    }

    @Test
    void missingSettingDefaultsSnapshotRequestToFalse() {
        when(settings.get("default")).thenReturn(Optional.empty());

        assertFalse(service.sandboxSnapshotEnabled());
    }

    @Test
    void legacyNullSnapshotRequestDefaultsAccessorAndViewToFalse() {
        var legacy = new SystemSettings();
        legacy.id = "default";
        when(settings.get("default")).thenReturn(Optional.of(legacy));

        assertFalse(service.sandboxSnapshotEnabled());
        assertFalse(service.get("admin").sandboxSnapshotEnabled);
    }

    @Test
    void updatePersistsAndReturnsSnapshotRequest() {
        when(settings.get("default")).thenReturn(Optional.empty());
        var request = new SystemSettingsRequest();
        request.sandboxSnapshotEnabled = Boolean.TRUE;

        var view = service.update(request, "admin");

        var entity = ArgumentCaptor.forClass(SystemSettings.class);
        verify(settings).insert(entity.capture());
        assertEquals(Boolean.TRUE, entity.getValue().sandboxSnapshotEnabled);
        assertEquals(Boolean.TRUE, view.sandboxSnapshotEnabled);
    }

    @Test
    void omittedSnapshotRequestPreservesExistingValue() {
        var existing = new SystemSettings();
        existing.id = "default";
        existing.sandboxSnapshotEnabled = Boolean.TRUE;
        when(settings.get("default")).thenReturn(Optional.of(existing));

        service.update(new SystemSettingsRequest(), "admin");

        assertEquals(Boolean.TRUE, existing.sandboxSnapshotEnabled);
        verify(settings).replace(existing);
    }

    @Test
    void enabledGatewayModelsReachTheAgents() {
        var existing = new SystemSettings();
        existing.id = "default";
        existing.llmModel = "deepseek-v4-pro";
        existing.imageGenerationModel = "gemini-3.1-flash-image";
        existing.videoGenerationModel = "bytedance-seedance-2-5";
        when(settings.get("default")).thenReturn(Optional.of(existing));
        when(models.find(any(Query.class))).thenReturn(List.of(mediaModel("gemini-3.1-flash-image")));

        assertEquals("deepseek-v4-pro", service.configuredLlmModel());
        assertEquals("gemini-3.1-flash-image", service.imageGenerationModel());
        assertEquals("bytedance-seedance-2-5", service.videoGenerationModel());
    }

    @Test
    void disabledGatewayModelReadsAsUnsetSoAgentsNeverSeeIt() {
        var existing = new SystemSettings();
        existing.id = "default";
        existing.llmModel = "deepseek-v4-pro";
        existing.imageGenerationModel = "gemini-3.1-flash-image";
        when(settings.get("default")).thenReturn(Optional.of(existing));
        when(models.find(any(Query.class))).thenReturn(List.of());

        assertNull(service.configuredLlmModel());
        assertNull(service.imageGenerationModel());
    }

    @Test
    void mediaModelGuardAcceptsLegacyRowsWithoutTheEnabledField() {
        when(settings.get("default")).thenReturn(Optional.empty());
        when(models.find(any(Query.class))).thenReturn(List.of(mediaModel("legacy-image")));
        var request = new SystemSettingsRequest();
        request.imageGenerationModel = "legacy-image";

        service.update(request, "admin");

        var query = ArgumentCaptor.forClass(Query.class);
        verify(models).find(query.capture());
        var filter = query.getValue().filter.toBsonDocument().toJson().replaceAll("\\s+", "");
        assertTrue(filter.contains("\"$ne\":false"), "a model without the enabled field still routes, so the settings guard must not require enabled=true: " + filter);
    }

    private GatewayModelConfig mediaModel(String modelId) {
        var config = new GatewayModelConfig();
        config.modelId = modelId;
        return config;
    }
}
