package ai.core.server.agenthealth;

import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.apitoolhub.ApiToolCatalogService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentQualityReceipt;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.User;
import ai.core.server.notification.NotificationService;
import ai.core.server.quality.AgentQualityStore;
import ai.core.server.quality.QualityPolicyService;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityRunStatus;
import ai.core.server.skill.SkillStore;
import ai.core.server.user.UserStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentHealthServiceTest {
    private final AgentHealthService service = new AgentHealthService();
    private AgentReferenceTracker tracker;
    private AgentDefinitionStore agentStore;
    private SkillStore skillStore;
    private ApiToolCatalogService catalogService;
    private AgentQualityStore qualityStore;
    private AgentReferenceIssueStore issueStore;
    private NotificationService notificationService;

    @BeforeEach
    void setUp() {
        agentStore = mock(AgentDefinitionStore.class);
        skillStore = mock(SkillStore.class);
        catalogService = mock(ApiToolCatalogService.class);
        qualityStore = mock(AgentQualityStore.class);
        var policyService = mock(QualityPolicyService.class);
        var permissionService = mock(PermissionService.class);
        var userStore = mock(UserStore.class);
        issueStore = mock(AgentReferenceIssueStore.class);
        notificationService = mock(NotificationService.class);

        service.skillStore = skillStore;
        service.apiToolCatalogService = catalogService;
        service.qualityStore = qualityStore;
        service.qualityPolicyService = policyService;
        service.permissionService = permissionService;
        service.userStore = userStore;
        service.issueStore = issueStore;
        tracker = new AgentReferenceTracker();
        tracker.agentDefinitionStore = agentStore;
        tracker.healthService = service;
        tracker.apiToolCatalogService = catalogService;
        tracker.issueStore = issueStore;
        tracker.notificationService = notificationService;

        when(userStore.get(any())).thenAnswer(invocation -> {
            var user = new User();
            user.id = invocation.getArgument(0);
            user.role = "user";
            return Optional.of(user);
        });
        when(permissionService.has(any(), any())).thenReturn(false);
        when(skillStore.findNames(any())).thenReturn(Map.of());
        when(catalogService.successSnapshot()).thenReturn(emptyCatalog());
        when(qualityStore.findLatestCompletedRuns(any(), any())).thenReturn(Map.of());
        when(policyService.activeRevision()).thenReturn(1);
        when(issueStore.listActiveByAgentIds(any())).thenReturn(List.of());
        when(issueStore.state()).thenReturn(state(true));
    }

    @Test
    void missingSkillWithIssueNameIsBrokenInBothViews() {
        var entity = agent("agent-1", "owner", AgentStatus.PUBLISHED);
        entity.skillIds = List.of("missing-skill");
        entity.publishedConfig = published(List.of("missing-skill"), null);
        when(issueStore.listActiveByAgentIds(any())).thenReturn(List.of(issue("agent-1", "SKILL", "missing-skill", "Deleted Skill")));

        var health = service.snapshot(entity, "owner");

        assertEquals("ATTENTION", health.status);
        assertTrue(health.needsAttention);
        assertEquals(1, health.referenceIssues.size());
        var issue = health.referenceIssues.get(0);
        assertEquals("SKILL", issue.refType);
        assertEquals("BROKEN", issue.state);
        assertEquals("Deleted Skill", issue.refName);
        assertEquals(List.of("DRAFT", "PUBLISHED"), issue.sources);
    }

    @Test
    void draftOnlyIssueIsHiddenFromViewerWithoutEditPermission() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.skillIds = List.of("missing-skill");

        var viewer = service.snapshot(entity, "viewer");

        assertEquals("OK", viewer.status);
        assertFalse(viewer.needsAttention);
        assertTrue(viewer.referenceIssues.isEmpty());
        assertNull(viewer.quality);
    }

    @Test
    void publishedOnlyIssueIsVisibleToEveryViewer() {
        var entity = agent("agent-1", "owner", AgentStatus.PUBLISHED);
        entity.publishedConfig = published(List.of("missing-skill"), null);

        var viewer = service.snapshot(entity, "viewer");

        assertEquals("ATTENTION", viewer.status);
        assertEquals(1, viewer.referenceIssues.size());
        assertEquals(List.of("PUBLISHED"), viewer.referenceIssues.get(0).sources);
    }

    @Test
    void unverifiedCatalogProducesUnknownNotAttention() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.tools = List.of(ToolRef.of("api-operation:menu:item:search", ToolSourceType.API));
        when(catalogService.successSnapshot()).thenReturn(null);

        var health = service.snapshot(entity, "owner");

        assertEquals("UNKNOWN", health.status);
        assertFalse(health.needsAttention);
        assertEquals("UNVERIFIED", health.referenceIssues.get(0).state);
        assertEquals("API_TOOL_UNVERIFIED", health.referenceIssues.get(0).reason);
    }

    @Test
    void removedApiOperationIsBrokenWhenCatalogLoaded() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.tools = List.of(ToolRef.of("api-operation:menu:item:search", ToolSourceType.API));

        var health = service.snapshot(entity, "owner");

        assertEquals("ATTENTION", health.status);
        assertEquals(1, health.referenceIssues.size());
        assertEquals("API", health.referenceIssues.get(0).refType);
        assertEquals("API_TOOL_NOT_FOUND", health.referenceIssues.get(0).reason);
        assertEquals("menu > item > search", health.referenceIssues.get(0).refName);
    }

    @Test
    void failedQualityRunStaysUntilPassAndReportsContentChange() {
        var entity = agent("agent-1", "owner", AgentStatus.PUBLISHED);
        entity.definitionRevision = 3;
        var run = run("run-1", QualityGateMode.PRECHECK, QualityGateResult.REJECTED, 3);
        when(qualityStore.findLatestCompletedRuns(any(), any())).thenReturn(Map.of("agent-1", run));

        var health = service.snapshot(entity, "owner");

        assertEquals("ATTENTION", health.status);
        assertNotNull(health.quality);
        assertEquals("FAILED", health.quality.state);
        assertEquals(Boolean.FALSE, health.quality.contentChanged);

        entity.definitionRevision = 4;
        var changed = service.snapshot(entity, "owner");
        assertEquals("ATTENTION", changed.status);
        assertEquals(Boolean.TRUE, changed.quality.contentChanged);

        entity.qualityReceipt = receipt("run-1");
        var published = service.snapshot(entity, "owner");
        assertEquals(Boolean.FALSE, published.quality.contentChanged);
        assertEquals("FAILED", published.quality.state);
    }

    @Test
    void passedQualityRunClearsAttention() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.definitionRevision = 2;
        when(qualityStore.findLatestCompletedRuns(any(), any()))
            .thenReturn(Map.of("agent-1", run("run-1", QualityGateMode.PRECHECK, QualityGateResult.PASSED, 2)));

        var health = service.snapshot(entity, "owner");

        assertEquals("OK", health.status);
        assertEquals("PASSED", health.quality.state);
    }

    @Test
    void onlyAuthorModesAreRequested() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);

        service.snapshot(entity, "owner");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<QualityGateMode>> modes = ArgumentCaptor.forClass(Set.class);
        verify(qualityStore).findLatestCompletedRuns(any(), modes.capture());
        assertTrue(modes.getValue().contains(QualityGateMode.PRECHECK));
        assertTrue(modes.getValue().contains(QualityGateMode.PUBLISH));
        assertTrue(modes.getValue().contains(QualityGateMode.FORK));
        assertFalse(modes.getValue().contains(QualityGateMode.WORKFLOW));
    }

    @Test
    void skillProbeFailureIsUnknownInsteadOfBroken() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.skillIds = List.of("skill-1");
        when(skillStore.findNames(any())).thenThrow(new RuntimeException("db down"));

        var health = service.snapshot(entity, "owner");

        assertEquals("UNKNOWN", health.status);
        assertFalse(health.needsAttention);
        assertEquals("UNVERIFIED", health.referenceIssues.get(0).state);
    }

    @Test
    void skillDeletionNotifiesOnceAndMarksIssues() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.skillIds = List.of("skill-1");
        when(agentStore.findReferencingAgents("skill-1")).thenReturn(List.of(entity));
        when(issueStore.listActiveByAgentIds(any())).thenReturn(List.of());
        when(issueStore.listActiveByAgentIds(eq(List.of("agent-1"))))
            .thenReturn(List.of())
            .thenReturn(List.of(issue("agent-1", "SKILL", "skill-1", "Deleted Skill")));

        tracker.onSkillDeleted("skill-1", "Deleted Skill", "admin");

        verify(issueStore).upsertActive(any());
        verify(notificationService).createIfAbsent(eq("owner"), any(), any(), any(), any(), any());
        verify(issueStore).markNotified(any(), any());
    }

    @Test
    void savingAgentResolvesReferenceThatWasRemoved() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        when(issueStore.listActiveByAgentIds(eq(List.of("agent-1"))))
            .thenReturn(List.of(issue("agent-1", "SKILL", "skill-1", "Deleted Skill")))
            .thenReturn(List.of());

        tracker.onAgentSaved(entity, "owner");

        verify(issueStore).resolve(eq(List.of("issue-skill-1")), eq("REMOVED"), any());
        verify(notificationService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
    }

    @Test
    void backfillSuppressesNotificationsButRecordsIssues() {
        var entity = agent("agent-1", "owner", AgentStatus.DRAFT);
        entity.skillIds = List.of("skill-1");
        when(agentStore.findReferencingAgents("skill-1")).thenReturn(List.of(entity));
        when(issueStore.state()).thenReturn(state(false));
        when(issueStore.listActiveByAgentIds(any())).thenReturn(List.of());
        when(issueStore.listActiveByAgentIds(eq(List.of("agent-1"))))
            .thenReturn(List.of())
            .thenReturn(List.of(issue("agent-1", "SKILL", "skill-1", "Deleted Skill")));

        tracker.onSkillDeleted("skill-1", "Deleted Skill", "admin");

        verify(issueStore).upsertActive(any());
        verify(notificationService, never()).createIfAbsent(any(), any(), any(), any(), any(), any());
        verify(issueStore).markNotified(any(), any());
    }

    private AgentDefinition agent(String id, String owner, AgentStatus status) {
        var entity = new AgentDefinition();
        entity.id = id;
        entity.userId = owner;
        entity.name = id;
        entity.type = DefinitionType.AGENT;
        entity.status = status;
        entity.definitionRevision = 0;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    private AgentPublishedConfig published(List<String> skillIds, List<ToolRef> tools) {
        var config = new AgentPublishedConfig();
        config.skillIds = skillIds;
        config.tools = tools;
        return config;
    }

    private AgentQualityRun run(String id, QualityGateMode mode, QualityGateResult result, int revision) {
        var run = new AgentQualityRun();
        run.id = id;
        run.agentId = "agent-1";
        run.mode = mode;
        run.runStatus = QualityRunStatus.COMPLETED;
        run.gateResult = result;
        run.definitionRevision = revision;
        run.policyRevision = 1;
        run.createdAt = ZonedDateTime.now().minusMinutes(1);
        run.finishedAt = ZonedDateTime.now();
        if (result == QualityGateResult.REJECTED) run.blockFailCount = 2;
        return run;
    }

    private AgentQualityReceipt receipt(String runId) {
        var receipt = new AgentQualityReceipt();
        receipt.runId = runId;
        receipt.gateResult = "REJECTED";
        return receipt;
    }

    private AgentReferenceIssue issue(String agentId, String type, String refId, String name) {
        var issue = new AgentReferenceIssue();
        issue.id = "issue-" + refId;
        issue.agentId = agentId;
        issue.agentOwnerId = "owner";
        issue.refType = type;
        issue.refId = refId;
        issue.refName = name;
        return issue;
    }

    private AgentHealthState state(boolean backfillDone) {
        var state = new AgentHealthState();
        state.id = "reconciler";
        state.backfillDone = backfillDone;
        return state;
    }

    private ApiToolCatalogService.CatalogSnapshot emptyCatalog() {
        return new ApiToolCatalogService.CatalogSnapshot(List.of(), Map.of(), Map.of(), Map.of(),
            Map.of(), Map.of(), true, ZonedDateTime.now(), null);
    }
}
