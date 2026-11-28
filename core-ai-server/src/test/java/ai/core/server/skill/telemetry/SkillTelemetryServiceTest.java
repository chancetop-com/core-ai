package ai.core.server.skill.telemetry;

import ai.core.api.server.SkillHubWebService;
import ai.core.api.server.SkillWebService;
import ai.core.api.server.skill.ReportSkillPapercutRequest;
import ai.core.api.server.skill.SkillPapercutCategoryView;
import ai.core.api.server.skill.SkillPapercutStatusView;
import ai.core.api.server.skill.UpdateSkillPapercutStatusRequest;
import ai.core.api.server.skillhub.SkillTelemetryBatchItemRequest;
import ai.core.api.server.skillhub.SkillTelemetryBatchRequest;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.User;
import ai.core.server.skill.SkillStore;
import ai.core.server.skillhub.SkillHubAccessPolicy;
import ai.core.server.skillhub.SkillHubWebServiceImpl;
import ai.core.server.user.UserStore;
import ai.core.server.web.SkillWebServiceImpl;
import core.framework.internal.bean.BeanClassValidator;
import core.framework.internal.web.service.WebServiceImplValidator;
import core.framework.internal.web.service.WebServiceInterfaceValidator;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SkillTelemetryService, SkillTelemetryBuffer, and core-ng WebService contracts.
 *
 * @author stephen
 */
class SkillTelemetryServiceTest {
    private SkillTelemetryStore telemetryStore;
    private SkillStore skillStore;
    private UserStore userStore;
    private SkillTelemetryService service;

    @BeforeEach
    void setUp() {
        telemetryStore = mock(SkillTelemetryStore.class);
        skillStore = mock(SkillStore.class);
        userStore = mock(UserStore.class);

        service = new SkillTelemetryService();
        service.telemetryStore = telemetryStore;
        service.skillStore = skillStore;
        service.accessPolicy = new SkillHubAccessPolicy();
        service.userStore = userStore;
    }

    @Test
    void webServicesPassCoreNgInterfaceAndImplValidation() {
        assertDoesNotThrow(() -> {
            var beanValidator = new BeanClassValidator();
            new WebServiceInterfaceValidator(SkillWebService.class, beanValidator).validate();
            new WebServiceInterfaceValidator(SkillHubWebService.class, beanValidator).validate();
            new WebServiceImplValidator<>(SkillWebService.class, new SkillWebServiceImpl()).validate();
            new WebServiceImplValidator<>(SkillHubWebService.class, new SkillHubWebServiceImpl()).validate();
        });
    }

    @Test
    void recordBatchValidatesLimitsDropsUnknownSkillsAndHonorsIdempotency() {
        var known = skill("skill-1", "team/demo", "owner-1", "sha-v1");
        when(skillStore.get("skill-1")).thenReturn(Optional.of(known));
        when(skillStore.get("missing")).thenReturn(Optional.empty());
        when(telemetryStore.isAvailable()).thenReturn(Boolean.TRUE);
        when(telemetryStore.recordBatch(eq("batch-uuid-0001"), any(), any())).thenReturn(Boolean.TRUE, Boolean.FALSE);

        var item1 = new SkillTelemetryBatchItemRequest();
        item1.skillId = "skill-1";
        item1.invokeDelta = 3L;
        item1.resourceReadDelta = 1L;
        item1.lastInvokedAt = ZonedDateTime.now();

        var unknown = new SkillTelemetryBatchItemRequest();
        unknown.skillId = "missing";
        unknown.invokeDelta = 2L;

        var request = new SkillTelemetryBatchRequest();
        request.batchId = "batch-uuid-0001";
        request.items = List.of(item1, unknown);

        var first = service.recordBatch(request, "user-1");
        assertTrue(first.accepted);
        assertFalse(first.duplicate);
        assertEquals(1, first.recordedItems);
        assertEquals(1, first.droppedUnknownItems);

        var replay = service.recordBatch(request, "user-1");
        assertTrue(replay.accepted);
        assertTrue(replay.duplicate);
        assertEquals(0, replay.recordedItems);
    }

    @Test
    void recordBatchRejectsOversizedBatchInvalidBatchIdAndAnomalousDelta() {
        var badId = new SkillTelemetryBatchRequest();
        badId.batchId = "bad";
        badId.items = List.of();
        assertThrows(BadRequestException.class, () -> service.recordBatch(badId, "user-1"));

        var oversized = new SkillTelemetryBatchRequest();
        oversized.batchId = "batch-uuid-oversized";
        oversized.items = new ArrayList<>(SkillTelemetryService.MAX_BATCH_ITEMS + 1);
        for (int i = 0; i <= SkillTelemetryService.MAX_BATCH_ITEMS; i++) {
            oversized.items.add(new SkillTelemetryBatchItemRequest());
        }
        assertThrows(BadRequestException.class, () -> service.recordBatch(oversized, "user-1"));

        var spikeItem = new SkillTelemetryBatchItemRequest();
        spikeItem.skillId = "skill-1";
        spikeItem.invokeDelta = SkillTelemetryService.MAX_DELTA_PER_ITEM + 1;
        var spike = new SkillTelemetryBatchRequest();
        spike.batchId = "batch-uuid-spike";
        spike.items = List.of(spikeItem);
        assertThrows(BadRequestException.class, () -> service.recordBatch(spike, "user-1"));
    }

    @Test
    void listPapercutsHidesSessionAndReporterFromNonOwnerNonAdmin() {
        var skill = skill("skill-1", "team/demo", "owner-1", "sha-v1");
        when(skillStore.get("skill-1")).thenReturn(Optional.of(skill));
        when(telemetryStore.isAvailable()).thenReturn(Boolean.TRUE);

        var record = new SkillPapercutRecord(
                "pc-1", "skill-1", "team/demo", "sha-v1", "dedup",
                SkillPapercutCategory.SCRIPT_ERROR, SkillPapercutStatus.OPEN,
                "Script fails on missing arg", "err", "fix", 2,
                "reporter-9", "session-secret-1", null, null, ZonedDateTime.now(), ZonedDateTime.now());
        when(telemetryStore.listPapercuts("skill-1", null)).thenReturn(List.of(record));

        var viewer = user("viewer-2", "user", "human");
        var humanAdmin = user("admin-1", "admin", "human");
        var apiAdmin = user("api-admin", "admin", "api");
        when(userStore.get("viewer-2")).thenReturn(Optional.of(viewer));
        when(userStore.get("admin-1")).thenReturn(Optional.of(humanAdmin));
        when(userStore.get("api-admin")).thenReturn(Optional.of(apiAdmin));

        var viewerResp = service.listPapercuts("skill-1", null, "viewer-2");
        assertNull(viewerResp.papercuts.getFirst().reporterUserId);
        assertNull(viewerResp.papercuts.getFirst().latestSessionId);

        var apiAdminResp = service.listPapercuts("skill-1", null, "api-admin");
        assertNull(apiAdminResp.papercuts.getFirst().reporterUserId);
        assertNull(apiAdminResp.papercuts.getFirst().latestSessionId);

        var ownerResp = service.listPapercuts("skill-1", null, "owner-1");
        assertEquals("reporter-9", ownerResp.papercuts.getFirst().reporterUserId);
        assertEquals("session-secret-1", ownerResp.papercuts.getFirst().latestSessionId);

        var adminResp = service.listPapercuts("skill-1", null, "admin-1");
        assertEquals("reporter-9", adminResp.papercuts.getFirst().reporterUserId);
        assertEquals("session-secret-1", adminResp.papercuts.getFirst().latestSessionId);
    }

    @Test
    void reportPapercutSanitizesSecretsBeforePersisting() {
        var skill = skill("skill-1", "team/demo", "owner-1", "sha-v1");
        when(skillStore.get("skill-1")).thenReturn(Optional.of(skill));
        when(telemetryStore.isAvailable()).thenReturn(Boolean.TRUE);

        var captor = ArgumentCaptor.forClass(SkillPapercutInput.class);
        when(telemetryStore.upsertPapercut(captor.capture())).thenAnswer(inv -> {
            var in = inv.getArgument(0, SkillPapercutInput.class);
            return new SkillPapercutRecord("pc-1", in.skillId(), in.qualifiedName(), in.packageDigest(), "dedup",
                    in.category(), SkillPapercutStatus.OPEN, in.summary(), in.observedError(), in.suggestedFix(),
                    1, in.reporterUserId(), in.sessionId(), null, null, ZonedDateTime.now(), ZonedDateTime.now());
        });

        var req = new ReportSkillPapercutRequest();
        req.category = SkillPapercutCategoryView.SCRIPT_ERROR;
        req.summary = "Curl call failed with auth error";
        req.observedError = "Authorization: Bearer secretToken12345678 and key sk-live-9876543210abc";
        req.suggestedFix = "Remove api_key=hardcoded_secret_999 from /Users/alice/config";

        var view = service.reportPapercut("skill-1", req, "owner-1");
        var stored = captor.getValue();

        assertFalse(stored.observedError().contains("secretToken12345678"));
        assertFalse(stored.observedError().contains("sk-live-9876543210abc"));
        assertFalse(stored.suggestedFix().contains("hardcoded_secret_999"));
        assertFalse(stored.suggestedFix().contains("/Users/alice"));
        assertTrue(view.latestObservedError.contains("***"));
    }

    @Test
    void updatePapercutStatusRestrictsToOwnerOrHumanAdmin() {
        var skill = skill("skill-1", "team/demo", "owner-1", "sha-v1");
        when(skillStore.get("skill-1")).thenReturn(Optional.of(skill));
        when(telemetryStore.isAvailable()).thenReturn(Boolean.TRUE);

        var open = new SkillPapercutRecord("pc-1", "skill-1", "team/demo", "sha-v1", "dedup",
                SkillPapercutCategory.OUTDATED_DOC, SkillPapercutStatus.OPEN,
                "Command flags changed", null, null, 1, "u1", "s1", null, null, ZonedDateTime.now(), ZonedDateTime.now());
        var resolved = new SkillPapercutRecord("pc-1", "skill-1", "team/demo", "sha-v1", "dedup",
                SkillPapercutCategory.OUTDATED_DOC, SkillPapercutStatus.RESOLVED,
                "Command flags changed", null, null, 1, "u1", "s1", "owner-1", ZonedDateTime.now(), ZonedDateTime.now(), ZonedDateTime.now());
        when(telemetryStore.getPapercut("skill-1", "pc-1")).thenReturn(Optional.of(open));
        when(telemetryStore.updatePapercutStatus("skill-1", "pc-1", SkillPapercutStatus.RESOLVED, "owner-1"))
                .thenReturn(Optional.of(resolved));

        when(userStore.get("other-user")).thenReturn(Optional.of(user("other-user", "user", "human")));
        when(userStore.get("api-admin")).thenReturn(Optional.of(user("api-admin", "admin", "api")));

        var update = new UpdateSkillPapercutStatusRequest();
        update.status = SkillPapercutStatusView.RESOLVED;

        assertThrows(ForbiddenException.class, () -> service.updatePapercutStatus("skill-1", "pc-1", update, "other-user"));
        assertThrows(ForbiddenException.class, () -> service.updatePapercutStatus("skill-1", "pc-1", update, "api-admin"));

        var updated = service.updatePapercutStatus("skill-1", "pc-1", update, "owner-1");
        assertEquals(SkillPapercutStatusView.RESOLVED, updated.status);
        assertEquals("owner-1", updated.resolvedBy);
    }

    @Test
    void telemetryBufferAggregatesAndSwallowsFlushErrors() {
        var buffer = new SkillTelemetryBuffer();
        buffer.telemetryStore = telemetryStore;
        when(telemetryStore.isAvailable()).thenReturn(Boolean.TRUE);

        buffer.recordInvoke("skill-1", "sha-1");
        buffer.recordInvoke("skill-1", "sha-1");
        buffer.recordResourceRead("skill-1", "sha-1");
        buffer.recordAttributedScriptCall("skill-1", "sha-1", true);
        buffer.recordUnattributedScriptError();

        assertEquals(1, buffer.unattributedScriptErrorCount());
        int flushed = buffer.flush();
        assertEquals(1, flushed);
        verify(telemetryStore).recordDeltas(any());

        buffer.recordInvoke("skill-1", "sha-1");
        doThrow(new RuntimeException("pg timeout")).when(telemetryStore).recordDeltas(any());
        int failed = assertDoesNotThrow(buffer::flush);
        assertEquals(0, failed);
        assertNotNull(buffer.drain());
    }

    private SkillDefinition skill(String id, String qualifiedName, String userId, String digest) {
        var def = new SkillDefinition();
        def.id = id;
        def.qualifiedName = qualifiedName;
        def.userId = userId;
        def.digest = digest;
        return def;
    }

    private User user(String id, String role, String userType) {
        var u = new User();
        u.id = id;
        u.role = role;
        u.userType = userType;
        return u;
    }
}
