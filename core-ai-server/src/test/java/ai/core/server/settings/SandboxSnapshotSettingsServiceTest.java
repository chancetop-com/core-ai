package ai.core.server.settings;

import ai.core.api.server.settings.SandboxSnapshotSettingsRequest;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.SystemSettings;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.RoleRegistry;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import core.framework.internal.json.JSONClassValidator;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.impl.ZonedDateTimeCodec;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.bson.BsonDocument;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SandboxSnapshotSettingsServiceTest {
    private static final CodecRegistry CODECS = CodecRegistries.fromRegistries(
            CodecRegistries.fromCodecs(new ZonedDateTimeCodec()), MongoClientSettings.getDefaultCodecRegistry());

    private SandboxSnapshotSettingsService service;
    private MongoCollection<SystemSettings> settings;
    private final Map<String, SystemSettings> stored = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new SandboxSnapshotSettingsService();
        settings = mock(MongoCollection.class);
        service.systemSettingsCollection = settings;
        service.permissionService = mock(PermissionService.class);
        when(service.permissionService.permissionsOf("selected-user")).thenReturn(List.of(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE));
        when(service.permissionService.permissionsOf("admin")).thenReturn(List.of(RoleRegistry.ALL_PERMISSIONS));
        when(settings.get(anyString())).thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            SystemSettings entity = invocation.getArgument(0);
            stored.put(entity.id, entity);
            return null;
        }).when(settings).insert(any(SystemSettings.class));
        doAnswer(invocation -> {
            SystemSettings entity = invocation.getArgument(0);
            stored.put(entity.id, entity);
            return null;
        }).when(settings).replace(any(SystemSettings.class));
        when(settings.update(any(Bson.class), any(Bson.class)))
                .thenAnswer(invocation -> applyUpdate(invocation.getArgument(0), invocation.getArgument(1)));
    }

    @Test
    void missingSettingsAndLegacyFieldDefaultToFourteenDays() {
        assertEquals(14, service.retentionDays());
        assertEquals(14, service.get("selected-user").retentionDays);
        assertNull(service.get("selected-user").updatedAt);
        stored.put("sandbox-snapshot", new SystemSettings());

        assertEquals(14, service.retentionDays());
        assertEquals(14, service.get("selected-user").retentionDays);
    }

    @Test
    void authorizedNonAdminCreatesSettingsAndReadsPersistedValue() {
        var view = service.update(request(30), "selected-user");

        assertEquals(30, view.retentionDays);
        assertEquals("selected-user", view.updatedBy);
        assertNotNull(view.updatedAt);
        var saved = stored.get("sandbox-snapshot");
        assertEquals("sandbox-snapshot", saved.id);
        assertEquals("selected-user", saved.createdBy);
        assertNotNull(saved.createdAt);
        assertEquals(30, service.get("selected-user").retentionDays);
        assertEquals(30, service.retentionDays());
    }

    @Test
    void updateTouchesOnlyRetentionAndAuditFields() {
        var existing = new SystemSettings();
        existing.id = "sandbox-snapshot";
        existing.llmModel = "keep-model";
        existing.azureBlobAccountKey = "keep-encrypted-key";
        existing.rbacRoles = "{\"ops\":[\"sandbox.snapshot.manage\"]}";
        existing.sandboxSnapshotEnabled = Boolean.TRUE;
        stored.put(existing.id, existing);

        service.update(request(7), "selected-user");

        var filter = ArgumentCaptor.forClass(Bson.class);
        var update = ArgumentCaptor.forClass(Bson.class);
        verify(settings).update(filter.capture(), update.capture());
        assertEquals("sandbox-snapshot", filter.getValue().toBsonDocument().getString("_id").getValue());
        var fields = update.getValue().toBsonDocument(BsonDocument.class, CODECS).getDocument("$set");
        assertEquals(Set.of("sandbox_snapshot_retention_days", "updated_by", "updated_at"), fields.keySet());
        assertEquals(7, service.retentionDays());
        assertEquals("keep-model", existing.llmModel);
        assertEquals("keep-encrypted-key", existing.azureBlobAccountKey);
        assertEquals("{\"ops\":[\"sandbox.snapshot.manage\"]}", existing.rbacRoles);
        assertEquals(Boolean.TRUE, existing.sandboxSnapshotEnabled);
        verify(settings, never()).replace(any(SystemSettings.class));
    }

    @Test
    void staleDefaultSettingsReplacementCannotOverwriteSnapshotRetention() {
        var defaults = new SystemSettings();
        defaults.id = "default";
        defaults.llmModel = "original-model";
        stored.put(defaults.id, defaults);
        var staleDefaults = settings.get("default").orElseThrow();

        service.update(request(30), "selected-user");
        staleDefaults.llmModel = "new-model";
        staleDefaults.rbacRoles = "{\"reviewer\":[\"trace.view\"]}";
        staleDefaults.updatedBy = "system-admin";
        settings.replace(staleDefaults);

        assertEquals(30, service.retentionDays());
        assertEquals(30, service.get("selected-user").retentionDays);
        assertEquals("selected-user", service.get("selected-user").updatedBy);
        assertEquals("new-model", settings.get("default").orElseThrow().llmModel);
        assertEquals("{\"reviewer\":[\"trace.view\"]}", settings.get("default").orElseThrow().rbacRoles);
        assertNull(settings.get("default").orElseThrow().sandboxSnapshotRetentionDays);
    }

    @Test
    void adminCanSaveInclusiveBoundaries() {
        for (int days : new int[]{1, 3650}) {
            assertEquals(days, service.update(request(days), "admin").retentionDays);
        }
    }

    @Test
    void rejectsFractionalRetentionFromFrameworkJsonDecoder() {
        for (String days : List.of("1.5", "3650.5", "1.0000000000000000001")) {
            var request = JSON.fromJSON(SandboxSnapshotSettingsRequest.class, "{\"retention_days\":" + days + "}");

            assertThrows(BadRequestException.class, () -> service.update(request, "selected-user"));
        }
        verifyNoInteractions(settings);
    }

    @Test
    void acceptsIntegerRetentionFromFrameworkJsonDecoder() {
        new JSONClassValidator(SandboxSnapshotSettingsRequest.class).validate();
        var request = JSON.fromJSON(SandboxSnapshotSettingsRequest.class, "{\"retention_days\":30}");

        assertEquals(30, service.update(request, "selected-user").retentionDays);
        assertEquals(30, service.retentionDays());
    }

    @Test
    void rejectsMissingAndOutOfRangeRetention() {
        for (Integer days : new Integer[]{null, -1, 0, 3651}) {
            assertThrows(BadRequestException.class, () -> service.update(request(days), "selected-user"));
        }
        verifyNoInteractions(settings);
    }

    @Test
    void rejectsMissingRequest() {
        assertThrows(BadRequestException.class, () -> service.update(null, "selected-user"));
        verifyNoInteractions(settings);
    }

    @Test
    void rejectsUnauthorizedReadAndWrite() {
        when(service.permissionService.permissionsOf("system-manager")).thenReturn(List.of(PermissionCodes.SYSTEM_MANAGE));

        for (String userId : new String[]{null, "", "ordinary-user", "missing-user", "system-manager"}) {
            assertThrows(ForbiddenException.class, () -> service.get(userId));
            assertThrows(ForbiddenException.class, () -> service.update(request(30), userId));
        }
        verifyNoInteractions(settings);
    }

    @Test
    void apiUserCannotUseActionCheckExemptionOrWildcard() {
        when(service.permissionService.isApiUser("api-user")).thenReturn(Boolean.TRUE);
        when(service.permissionService.has("api-user", PermissionCodes.SANDBOX_SNAPSHOT_MANAGE)).thenReturn(Boolean.TRUE);
        when(service.permissionService.permissionsOf("api-user")).thenReturn(List.of(RoleRegistry.ALL_PERMISSIONS));

        assertThrows(ForbiddenException.class, () -> service.get("api-user"));
        assertThrows(ForbiddenException.class, () -> service.update(request(30), "api-user"));
        verifyNoInteractions(settings);
    }

    @Test
    void concurrentInitialInsertFallsBackToFieldUpdate() {
        doAnswer(invocation -> {
            var concurrent = new SystemSettings();
            concurrent.id = "sandbox-snapshot";
            concurrent.llmModel = "concurrently-configured-model";
            stored.put(concurrent.id, concurrent);
            throw new MongoException(11000, "duplicate key");
        }).when(settings).insert(any(SystemSettings.class));

        assertEquals(45, service.update(request(45), "selected-user").retentionDays);
        assertEquals("concurrently-configured-model", stored.get("sandbox-snapshot").llmModel);
        verify(settings).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void insertFailurePropagatesWithoutPretendingToSave() {
        doThrow(new MongoException(91, "database unavailable")).when(settings).insert(any(SystemSettings.class));

        assertThrows(MongoException.class, () -> service.update(request(30), "selected-user"));
        verify(settings, never()).update(any(Bson.class), any(Bson.class));
    }

    private long applyUpdate(Bson filter, Bson update) {
        var id = filter.toBsonDocument().getString("_id").getValue();
        var entity = stored.get(id);
        if (entity == null) return 0;
        var values = update.toBsonDocument(BsonDocument.class, CODECS).getDocument("$set");
        entity.sandboxSnapshotRetentionDays = values.getInt32("sandbox_snapshot_retention_days").getValue();
        entity.updatedBy = values.getString("updated_by").getValue();
        entity.updatedAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(values.getDateTime("updated_at").getValue()), ZoneOffset.UTC);
        return 1;
    }

    private SandboxSnapshotSettingsRequest request(Integer days) {
        var request = new SandboxSnapshotSettingsRequest();
        request.retentionDays = days == null ? null : BigDecimal.valueOf(days);
        return request;
    }
}
