package ai.core.server.session;

import ai.core.api.server.session.SessionConfig;
import ai.core.media.MediaProvider;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.User;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.file.FileService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.server.tool.ToolRegistryService;
import ai.core.session.InProcessAgentSession;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionRebuildManagerTest {
    @SuppressWarnings("unchecked")
    private final MongoCollection<User> users = (MongoCollection<User>) mock(MongoCollection.class);

    @Test
    void agentSnapshotPersistsCaptionRoutingPreferenceAcrossPodRebuilds() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = new ChatSession();
        meta.id = "session-1";
        meta.userId = "user-1";
        meta.agentId = "agent-1";
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "user-1";
        definition.name = "caption-agent";
        definition.type = DefinitionType.AGENT;
        definition.status = AgentStatus.DRAFT;
        definition.preferCaptionPath = Boolean.TRUE;
        definition.skillIds = java.util.List.of("definition-skill");
        meta.loadedSkillIds = java.util.List.of("definition-skill", "dynamic-skill");
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var skillManager = mock(SessionSkillManager.class);
        var manager = new SessionRebuildManager(new SessionRebuildManager.Deps(
                chatMessageService, agents, skillManager, null, null, null, null, null,
                null, null, null, null, null, null, null, users, null, mock(ApiUserQuotaService.class), null));

        var state = manager.buildStateFromDb("session-1");

        var restored = SessionState.fromJson(state.toJson());
        assertEquals(SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION,
                restored.agentSnapshotSecurityVersion);
        assertNull(restored.sandboxBindingSecurityVersion);
        assertEquals(Boolean.TRUE, restored.agentConfig.preferCaptionPath);
        assertEquals(java.util.List.of("definition-skill"), restored.agentConfig.skillIds);
        assertEquals(java.util.List.of("dynamic-skill"), restored.skillIds);
        verify(skillManager).resolveAccessibleDefinitionSkills(argThat(value ->
                java.util.List.of("definition-skill").equals(value.publishedConfig.skillIds)), eq("user-1"));
    }

    @Test
    void publishedAgentSnapshotNeverFallsBackToEditableFields() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = new ChatSession();
        meta.id = "session-1";
        meta.userId = "viewer";
        meta.agentId = "agent-1";
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "owner";
        definition.type = DefinitionType.AGENT;
        definition.status = AgentStatus.PUBLISHED;
        definition.model = "editable-secret-model";
        definition.systemPromptId = "editable-secret-prompt";
        definition.preferCaptionPath = Boolean.TRUE;
        definition.tools = java.util.List.of(ToolRef.of("private-mcp", ToolSourceType.MCP));
        definition.subAgentIds = java.util.List.of("editable-secret-sub-agent");
        definition.publishedConfig = new AgentPublishedConfig();
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var manager = new SessionRebuildManager(new SessionRebuildManager.Deps(
            chatMessageService, agents, null, null, null, null, null, null,
            null, null, null, null, null, null, null, users, null, mock(ApiUserQuotaService.class), null));

        var state = manager.buildStateFromDb("session-1");
        var restored = SessionState.fromJson(state.toJson());

        assertNull(restored.agentConfig.model);
        assertNull(restored.agentConfig.systemPromptId);
        assertNull(restored.agentConfig.preferCaptionPath);
        assertNull(restored.agentConfig.tools);
        assertNull(restored.agentConfig.skillIds);
        assertEquals(java.util.List.of(), state.subAgentIds);
    }

    @Test
    void agentSnapshotPreservesPublishedSandboxConfiguration() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = sessionMeta("viewer");
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = definition("owner", AgentStatus.PUBLISHED);
        definition.sandboxConfig = sandbox("editable-image", true);
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.sandboxConfig = sandbox("hardened-image", false);
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var manager = rebuildManager(chatMessageService, agents);

        var restored = SessionState.fromJson(manager.buildStateFromDb("session-1").toJson());

        assertEquals("hardened-image", restored.agentConfig.sandboxConfig.image);
        assertEquals(Boolean.FALSE, restored.agentConfig.sandboxConfig.networkEnabled);
    }

    @Test
    void rebuildUsesSnapshottedSandboxConfiguration() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenAnswer(invocation -> {
            var params = invocation.getArgument(0, SessionSubAgentManager.BuildAgentParams.class);
            var agent = mock(ai.core.agent.Agent.class);
            when(agent.getExecutionContext()).thenReturn(params.context());
            return agent;
        });
        var sandboxService = mock(SandboxService.class);
        when(sandboxService.getSandboxId("session-1")).thenReturn("sandbox-1");
        var artifactSetup = mock(ChatArtifactSetup.class);
        var publicUrlConfiguration = mock(PublicUrlConfiguration.class);
        var systemSettingsService = mock(SystemSettingsService.class);
        var mediaProvider = mock(MediaProvider.class);
        var manager = new SessionRebuildManager(new SessionRebuildManager.Deps(
            chatMessageService, agents, mock(SessionSkillManager.class), subAgentManager,
            sandboxService, artifactSetup, mock(ToolRegistryService.class), mock(SystemPromptService.class),
            mock(DatasetService.class), mock(DatasetRecordService.class), mock(FileService.class),
            publicUrlConfiguration, null, null, systemSettingsService, users, mediaProvider,
            mock(ApiUserQuotaService.class), null));
        var state = new SessionState();
        state.agentSnapshotSecurityVersion = SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION;
        state.sandboxBindingSecurityVersion = SessionState.CURRENT_SANDBOX_BINDING_SECURITY_VERSION;
        state.fromAgent = true;
        state.userId = "user-1";
        state.agentConfig = new SessionState.AgentConfigSnapshot();
        state.agentConfig.agentId = "agent-1";
        state.agentConfig.agentName = "Agent One";
        state.agentConfig.sandboxConfig = sandbox("hardened-image", false);

        var rebuilt = manager.rebuildSession("session-1", state, "user-1");

        assertNotNull(rebuilt);
        var build = ArgumentCaptor.forClass(SessionSubAgentManager.BuildAgentParams.class);
        verify(subAgentManager).buildAgent(build.capture());
        assertSame(mediaProvider, build.getValue().context().getImageMediaProvider());
        assertSame(mediaProvider, build.getValue().context().getVideoMediaProvider());
        verify(sandboxService).isSandboxEnabled(argThat(config ->
                config != null && "hardened-image".equals(config.image) && Boolean.FALSE.equals(config.networkEnabled)));
        verify(sandboxService).createSessionSandbox(argThat(config ->
                        config != null && "hardened-image".equals(config.image) && Boolean.FALSE.equals(config.networkEnabled)),
                eq("session-1"), eq("user-1"), any());
        verify(sandboxService).reattachOrCreateSandbox(eq("sandbox-1"),
                argThat(config -> config != null
                        && "hardened-image".equals(config.image)
                        && Boolean.FALSE.equals(config.networkEnabled)),
                eq("session-1"), eq("user-1"), any());
        rebuilt.close();
    }

    @Test
    void trustedSnapshotWithoutBindingProvenanceInvalidatesAndCreatesFreshSandbox() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenAnswer(invocation -> {
            var params = invocation.getArgument(0, SessionSubAgentManager.BuildAgentParams.class);
            var agent = mock(ai.core.agent.Agent.class);
            when(agent.getExecutionContext()).thenReturn(params.context());
            return agent;
        });
        var sandboxService = mock(SandboxService.class);
        when(sandboxService.getSandboxId("session-1")).thenReturn("unproven-sandbox");
        var manager = runtimeRebuildManager(chatMessageService, agents, mock(SessionSkillManager.class),
                subAgentManager, sandboxService, mock(ToolRegistryService.class));
        var state = new SessionState();
        state.agentSnapshotSecurityVersion = SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION;
        state.fromAgent = true;
        state.userId = "user-1";
        state.agentConfig = new SessionState.AgentConfigSnapshot();
        state.agentConfig.agentId = "agent-1";
        state.agentConfig.sandboxConfig = sandbox("safe-image", false);

        var rebuilt = manager.rebuildSession("session-1", state);

        assertNotNull(rebuilt);
        verify(sandboxService).invalidateSandboxBinding("session-1");
        verify(sandboxService, never()).reattachOrCreateSandbox(any(), any(), any(), any(), any());
        verify(sandboxService).createSessionSandbox(any(), eq("session-1"), eq("user-1"), any());
        rebuilt.close();
    }

    @Test
    void legacyAgentSnapshotIsRederivedAndNeverReattachesItsSandboxOrRestoresRawDependencies() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("viewer"));
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var safeTool = ToolRef.of("builtin-safe", ToolSourceType.BUILTIN);
        when(agents.get("agent-1")).thenReturn(Optional.of(safePublishedDefinition(safeTool)));
        var safeSubAgent = definition("another-owner", AgentStatus.PUBLISHED);
        safeSubAgent.id = "safe-sub-agent";
        when(agents.get("safe-sub-agent")).thenReturn(Optional.of(safeSubAgent));

        var skillManager = mock(SessionSkillManager.class);
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenAnswer(invocation -> {
            var params = invocation.getArgument(0, SessionSubAgentManager.BuildAgentParams.class);
            var agent = mock(ai.core.agent.Agent.class);
            when(agent.getExecutionContext()).thenReturn(params.context());
            return agent;
        });
        var sandboxService = mock(SandboxService.class);
        when(sandboxService.getSandboxId("session-1")).thenReturn("legacy-sandbox");
        var toolRegistry = mock(ToolRegistryService.class);
        when(toolRegistry.resolveToolRefs(java.util.List.of(safeTool), "session-1", "viewer"))
                .thenReturn(java.util.List.of());
        var manager = runtimeRebuildManager(chatMessageService, agents, skillManager, subAgentManager,
                sandboxService, toolRegistry);

        var privateTool = ToolRef.of("builtin-private", ToolSourceType.BUILTIN);
        var state = legacyUnsafeState(privateTool);

        var rebuilt = manager.rebuildSession("session-1", state, "viewer");

        assertNotNull(rebuilt);
        var build = ArgumentCaptor.forClass(SessionSubAgentManager.BuildAgentParams.class);
        verify(subAgentManager).buildAgent(build.capture());
        assertEquals("safe-published-model", build.getValue().config().model);
        assertEquals("safe-dataset", build.getValue().config().datasetId);
        assertTrue(build.getValue().config().systemPrompt.startsWith("safe published prompt"));
        assertFalse(build.getValue().config().systemPrompt.contains("legacy private prompt"));
        verify(toolRegistry).resolveToolRefs(java.util.List.of(safeTool), "session-1", "viewer");
        verify(toolRegistry, never()).resolveToolRefs(eq(java.util.List.of(privateTool)), eq("session-1"), any());
        verify(skillManager).restoreDefinitionSkills(any(), eq(java.util.List.of("safe-skill")));
        verify(skillManager, never()).resolveAccessibleDefinitionSkills(any(), any());
        verify(agents, never()).get("legacy-private-sub-agent");
        verify(subAgentManager).applySubAgentsToSession(any(),
                argThat(values -> values.size() == 1 && "safe-sub-agent".equals(values.getFirst().id)),
                eq("viewer"));
        verify(sandboxService, never()).reattachOrCreateSandbox(any(), any(), any(), any(), any());
        verify(sandboxService).invalidateSandboxBinding("session-1");
        verify(sandboxService).createSessionSandbox(argThat(config -> config != null
                        && "safe-image".equals(config.image)
                        && Boolean.FALSE.equals(config.networkEnabled)),
                eq("session-1"), eq("viewer"), any());
        rebuilt.close();
    }

    @Test
    void trustedAgentSnapshotFailsClosedWhenExplicitCallerDoesNotOwnPersistedState() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenAnswer(invocation -> {
            var params = invocation.getArgument(0, SessionSubAgentManager.BuildAgentParams.class);
            var agent = mock(ai.core.agent.Agent.class);
            when(agent.getExecutionContext()).thenReturn(params.context());
            return agent;
        });
        var manager = runtimeRebuildManager(chatMessageService, agents, mock(SessionSkillManager.class),
                subAgentManager, mock(SandboxService.class), mock(ToolRegistryService.class));
        var state = new SessionState();
        state.agentSnapshotSecurityVersion = SessionState.CURRENT_AGENT_SNAPSHOT_SECURITY_VERSION;
        state.fromAgent = true;
        state.userId = "persisted-owner";
        state.agentConfig = new SessionState.AgentConfigSnapshot();
        state.agentConfig.agentId = "agent-1";

        var rebuilt = manager.rebuildSession("session-1", state, "different-caller");
        try {
            assertNull(rebuilt);
        } finally {
            if (rebuilt != null) rebuilt.close();
        }
        verify(subAgentManager, never()).buildAgent(any());
    }

    @Test
    void legacyBindingInvalidationFailureStopsBeforeDefinitionOrSandboxExecution() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("viewer"));
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var subAgentManager = mock(SessionSubAgentManager.class);
        var sandboxService = mock(SandboxService.class);
        doThrow(new IllegalStateException("redis down"))
                .when(sandboxService).invalidateSandboxBinding("session-1");
        var manager = runtimeRebuildManager(chatMessageService, agents, mock(SessionSkillManager.class),
                subAgentManager, sandboxService, mock(ToolRegistryService.class));
        var state = legacyUnsafeState(ToolRef.of("private", ToolSourceType.BUILTIN));

        assertNull(manager.rebuildSession("session-1", state, "viewer"));
        verify(agents, never()).get("agent-1");
        verify(subAgentManager, never()).buildAgent(any());
        verify(sandboxService, never()).createSessionSandbox(any(), any(), any(), any());
    }

    @Test
    void legacyCallerMismatchFailsBeforeBindingInvalidation() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("session-owner"));
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var sandboxService = mock(SandboxService.class);
        var manager = runtimeRebuildManager(chatMessageService, agents, mock(SessionSkillManager.class),
                mock(SessionSubAgentManager.class), sandboxService, mock(ToolRegistryService.class));
        var state = legacyUnsafeState(ToolRef.of("private", ToolSourceType.BUILTIN));

        assertNull(manager.rebuildSession("session-1", state, "attacker"));
        verify(sandboxService, never()).invalidateSandboxBinding(any());
        verify(agents, never()).get("agent-1");
    }

    @Test
    void legacyAgentSnapshotFailsClosedForNonOwnerWithoutPublishedConfig() {
        assertLegacySnapshotFailsClosed(definition("owner", AgentStatus.DRAFT), "viewer");
    }

    @Test
    void legacyAgentSnapshotFailsClosedForSystemAgentWithoutPublishedConfig() {
        var definition = definition("viewer", AgentStatus.DRAFT);
        definition.systemDefault = Boolean.TRUE;

        assertLegacySnapshotFailsClosed(definition, "viewer");
    }

    @Test
    void legacyOwnerDraftRebuildRejectsMissingDefinitionSkill() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("owner"));
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var definition = definition("owner", AgentStatus.DRAFT);
        definition.skillIds = java.util.List.of("missing-skill");
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var skillManager = mock(SessionSkillManager.class);
        when(skillManager.resolveAccessibleDefinitionSkills(any(), eq("owner")))
                .thenThrow(new ForbiddenException("skill is unavailable"));
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenAnswer(invocation -> {
            var params = invocation.getArgument(0, SessionSubAgentManager.BuildAgentParams.class);
            var agent = mock(ai.core.agent.Agent.class);
            when(agent.getExecutionContext()).thenReturn(params.context());
            return agent;
        });
        var sandboxService = mock(SandboxService.class);
        var manager = runtimeRebuildManager(chatMessageService, agents, skillManager, subAgentManager,
                sandboxService, mock(ToolRegistryService.class));
        var state = legacyUnsafeState(ToolRef.of("private", ToolSourceType.BUILTIN));

        var rebuilt = manager.rebuildSession("session-1", state, "owner");
        try {
            assertNull(rebuilt);
        } finally {
            if (rebuilt != null) rebuilt.close();
        }
        verify(sandboxService).invalidateSandboxBinding("session-1");
        verify(skillManager).resolveAccessibleDefinitionSkills(any(), eq("owner"));
        verify(subAgentManager, never()).buildAgent(any());
    }

    @Test
    void versionOneOwnerDraftSnapshotIsRederivedAndRejectsMissingDefinitionSkill() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("owner"));
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var definition = definition("owner", AgentStatus.DRAFT);
        definition.skillIds = java.util.List.of("missing-skill");
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var skillManager = mock(SessionSkillManager.class);
        when(skillManager.resolveAccessibleDefinitionSkills(any(), eq("owner")))
                .thenThrow(new ForbiddenException("skill is unavailable"));
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenAnswer(invocation -> {
            var params = invocation.getArgument(0, SessionSubAgentManager.BuildAgentParams.class);
            var agent = mock(ai.core.agent.Agent.class);
            when(agent.getExecutionContext()).thenReturn(params.context());
            return agent;
        });
        var sandboxService = mock(SandboxService.class);
        var manager = runtimeRebuildManager(chatMessageService, agents, skillManager, subAgentManager,
                sandboxService, mock(ToolRegistryService.class));
        var state = legacyUnsafeState(ToolRef.of("private", ToolSourceType.BUILTIN));
        state.agentSnapshotSecurityVersion = 1;
        state.userId = "owner";
        state.agentConfig.skillIds = java.util.List.of("missing-skill");

        var rebuilt = manager.rebuildSession("session-1", state, "owner");
        try {
            assertNull(rebuilt);
        } finally {
            if (rebuilt != null) rebuilt.close();
        }
        verify(sandboxService).invalidateSandboxBinding("session-1");
        verify(skillManager).resolveAccessibleDefinitionSkills(any(), eq("owner"));
        verify(skillManager, never()).restoreDefinitionSkills(any(), any());
        verify(subAgentManager, never()).buildAgent(any());
    }

    @Test
    void databaseRebuildRejectsMissingSkillInOwnersEditableDefinition() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("owner"));
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var definition = definition("owner", AgentStatus.DRAFT);
        definition.skillIds = java.util.List.of("missing-skill");
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var skillManager = mock(SessionSkillManager.class);
        when(skillManager.resolveAccessibleDefinitionSkills(any(), eq("owner")))
                .thenThrow(new ForbiddenException("skill is unavailable"));
        var manager = new SessionRebuildManager(new SessionRebuildManager.Deps(
                chatMessageService, agents, skillManager, null, null, null, null, null,
                null, null, null, null, null, null, null, users, null, mock(ApiUserQuotaService.class), null));

        var error = assertThrows(ForbiddenException.class,
                () -> manager.buildStateFromDb("session-1"));

        assertEquals("skill is unavailable", error.getMessage());
    }

    @Test
    void databaseRebuildRejectsAnotherUsersDraftAgent() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = sessionMeta("viewer");
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = definition("owner", AgentStatus.DRAFT);
        definition.systemPrompt = "editable secret";
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var manager = rebuildManager(chatMessageService, agents);

        var error = assertThrows(IllegalArgumentException.class,
            () -> manager.buildStateFromDb("session-1"));

        assertEquals("agent is unavailable", error.getMessage());
    }

    @Test
    void databaseRebuildFailsClosedWhenAgentDefinitionIsMissing() {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta("viewer"));
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        when(agents.get("agent-1")).thenReturn(Optional.empty());
        var manager = rebuildManager(chatMessageService, agents);

        var error = assertThrows(IllegalArgumentException.class,
                () -> manager.buildStateFromDb("session-1"));

        assertEquals("agent is unavailable", error.getMessage());
    }

    @Test
    void malformedAgentOriginStateWithoutSnapshotNeverFallsBackOrReattaches() {
        var chatMessageService = mock(ChatMessageService.class);
        var subAgentManager = mock(SessionSubAgentManager.class);
        var sandboxService = mock(SandboxService.class);
        when(sandboxService.getSandboxId("session-1")).thenReturn("legacy-sandbox");
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var manager = runtimeRebuildManager(chatMessageService, agents, mock(SessionSkillManager.class),
                subAgentManager, sandboxService, mock(ToolRegistryService.class));
        var state = new SessionState();
        state.fromAgent = true;
        state.userId = "viewer";
        state.config = new SessionConfig();

        assertNull(manager.rebuildSession("session-1", state, "viewer"));
        verify(subAgentManager, never()).buildAgent(any());
        verify(sandboxService, never()).reattachOrCreateSandbox(any(), any(), any(), any(), any());
    }

    @Test
    void databaseRebuildRejectsOwnersUnpublishedSystemAgent() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = sessionMeta("owner");
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = definition("owner", AgentStatus.DRAFT);
        definition.systemDefault = Boolean.TRUE;
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var manager = rebuildManager(chatMessageService, agents);

        var error = assertThrows(IllegalArgumentException.class,
            () -> manager.buildStateFromDb("session-1"));

        assertEquals("agent is unavailable", error.getMessage());
    }

    @Test
    void databaseRebuildRejectsLegacyMutablePublishedPromptForNonOwner() {
        var chatMessageService = mock(ChatMessageService.class);
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        var meta = sessionMeta("viewer");
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(meta);
        var definition = definition("owner", AgentStatus.PUBLISHED);
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.systemPromptId = "mutable-prompt";
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var manager = rebuildManager(chatMessageService, agents);

        var error = assertThrows(IllegalArgumentException.class,
            () -> manager.buildStateFromDb("session-1"));

        assertEquals("agent is unavailable", error.getMessage());
    }

    @Test
    void dynamicRestoreUsesAuthoritativeRebuildUserWhenSerializedStateUserIsMissing() {
        var toolRegistry = mock(ToolRegistryService.class);
        var skillManager = mock(SessionSkillManager.class);
        var manager = new SessionRebuildManager(new SessionRebuildManager.Deps(
            mock(ChatMessageService.class), mock(), skillManager, null, null, null, toolRegistry, null,
            null, null, null, null, null, null, null, users, null, mock(ApiUserQuotaService.class), null));
        var ref = ToolRef.fromLegacyToolId("llm-call:published-llm");
        var state = new SessionState();
        state.userId = null;
        state.tools = java.util.List.of(ref);
        state.skillIds = java.util.List.of("dynamic-skill");
        var session = mock(InProcessAgentSession.class);

        manager.restoreDynamicallyLoaded(state, "session-1", session, "caller-1");

        verify(toolRegistry).resolveToolRefs(java.util.List.of(ref), "session-1", "caller-1");
        verify(skillManager).applyCallerSkillsToSession(session,
                java.util.List.of("dynamic-skill"), "caller-1");
        verify(skillManager, never()).applySkillsToSession(session, java.util.List.of("dynamic-skill"));
    }

    private ChatSession sessionMeta(String userId) {
        var meta = new ChatSession();
        meta.id = "session-1";
        meta.userId = userId;
        meta.agentId = "agent-1";
        return meta;
    }

    private AgentDefinition definition(String userId, AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = userId;
        definition.name = "Agent One";
        definition.type = DefinitionType.AGENT;
        definition.status = status;
        return definition;
    }

    private AgentSandboxConfig sandbox(String image, boolean networkEnabled) {
        var config = new AgentSandboxConfig();
        config.image = image;
        config.networkEnabled = networkEnabled;
        return config;
    }

    private AgentDatasetConfig dataset(String id, boolean output) {
        var config = new AgentDatasetConfig();
        config.datasetId = id;
        config.permission = ai.core.server.domain.DatasetPermission.READ;
        config.isOutput = output;
        return config;
    }

    private AgentDefinition safePublishedDefinition(ToolRef safeTool) {
        var definition = definition("owner", AgentStatus.PUBLISHED);
        definition.systemPrompt = "editable secret";
        definition.model = "editable-secret-model";
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.systemPrompt = "safe published prompt";
        definition.publishedConfig.model = "safe-published-model";
        definition.publishedConfig.tools = java.util.List.of(safeTool);
        definition.publishedConfig.datasetConfig = java.util.List.of(dataset("safe-dataset", true));
        definition.publishedConfig.sandboxConfig = sandbox("safe-image", false);
        definition.publishedConfig.skillIds = java.util.List.of("safe-skill");
        definition.publishedConfig.skillValidationVersion = 1;
        definition.publishedConfig.subAgentIds = java.util.List.of("safe-sub-agent");
        return definition;
    }

    private SessionState legacyUnsafeState(ToolRef privateTool) {
        var state = new SessionState();
        state.fromAgent = true;
        state.userId = "forged-owner";
        state.agentConfig = new SessionState.AgentConfigSnapshot();
        state.agentConfig.agentId = "agent-1";
        state.agentConfig.agentName = "Forged Agent";
        state.agentConfig.systemPrompt = "legacy private prompt";
        state.agentConfig.model = "legacy-private-model";
        state.agentConfig.tools = java.util.List.of(privateTool);
        state.agentConfig.datasetConfig = java.util.List.of(dataset("legacy-private-dataset", true));
        state.agentConfig.sandboxConfig = sandbox("legacy-private-image", true);
        state.config = new SessionConfig();
        state.config.datasetId = "legacy-config-dataset";
        state.tools = java.util.List.of(privateTool);
        state.skillIds = java.util.List.of("legacy-private-skill");
        state.subAgentIds = java.util.List.of("legacy-private-sub-agent");
        return state;
    }

    private void assertLegacySnapshotFailsClosed(AgentDefinition definition, String callerUserId) {
        var chatMessageService = mock(ChatMessageService.class);
        when(chatMessageService.getSessionMeta("session-1")).thenReturn(sessionMeta(callerUserId));
        when(chatMessageService.history("session-1")).thenReturn(java.util.List.of());
        @SuppressWarnings("unchecked")
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        when(agents.get("agent-1")).thenReturn(Optional.of(definition));
        var subAgentManager = mock(SessionSubAgentManager.class);
        when(subAgentManager.buildAgent(any())).thenReturn(mock(ai.core.agent.Agent.class));
        var sandboxService = mock(SandboxService.class);
        var manager = runtimeRebuildManager(chatMessageService, agents, mock(SessionSkillManager.class),
                subAgentManager, sandboxService, mock(ToolRegistryService.class));
        var state = new SessionState();
        state.fromAgent = true;
        state.userId = callerUserId;
        state.agentConfig = new SessionState.AgentConfigSnapshot();
        state.agentConfig.agentId = "agent-1";
        state.agentConfig.systemPrompt = "legacy private prompt";
        state.agentConfig.model = "legacy-private-model";

        assertNull(manager.rebuildSession("session-1", state, callerUserId));
        verify(sandboxService).invalidateSandboxBinding("session-1");
        verify(subAgentManager, never()).buildAgent(any());
    }

    private SessionRebuildManager runtimeRebuildManager(ChatMessageService chatMessageService,
                                                         MongoCollection<AgentDefinition> agents,
                                                         SessionSkillManager skillManager,
                                                         SessionSubAgentManager subAgentManager,
                                                         SandboxService sandboxService,
                                                         ToolRegistryService toolRegistryService) {
        @SuppressWarnings("unchecked")
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        return new SessionRebuildManager(new SessionRebuildManager.Deps(
                chatMessageService, agents, skillManager, subAgentManager, sandboxService,
                mock(ChatArtifactSetup.class), toolRegistryService, mock(SystemPromptService.class),
                mock(DatasetService.class), mock(DatasetRecordService.class), mock(FileService.class),
                mock(PublicUrlConfiguration.class), null, null, mock(SystemSettingsService.class), users,
                mock(MediaProvider.class), mock(ApiUserQuotaService.class), null));
    }

    private SessionRebuildManager rebuildManager(ChatMessageService chatMessageService,
                                                 MongoCollection<AgentDefinition> agents) {
        @SuppressWarnings("unchecked")
        var users = (MongoCollection<User>) mock(MongoCollection.class);
        return new SessionRebuildManager(new SessionRebuildManager.Deps(
            chatMessageService, agents, null, null, null, null, null, null,
            null, null, null, null, null, null, null, users, null, mock(ApiUserQuotaService.class), null));
    }
}
