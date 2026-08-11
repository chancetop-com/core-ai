package ai.core.server.settings;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.domain.SystemSettings;
import ai.core.server.domain.User;
import ai.core.server.gateway.GatewaySecretProtector;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemSettingsServiceTest {
    private SystemSettingsService service;
    private MongoCollection<SystemSettings> settings;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SystemSettingsService();
        settings = mock(MongoCollection.class);
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        var models = (MongoCollection<GatewayModelConfig>) mock(MongoCollection.class);

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
}
