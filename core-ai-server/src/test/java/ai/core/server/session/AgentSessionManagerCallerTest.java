package ai.core.server.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.SessionConfig;
import ai.core.server.agent.SubAgentAssembler;
import ai.core.server.artifact.ChatArtifactSetup;
import ai.core.server.artifact.PublicUrlConfiguration;
import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.dataset.DatasetService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentSandboxConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.User;
import core.framework.mongo.MongoCollection;
import ai.core.server.file.FileService;
import ai.core.server.memory.experiment.AgentMemoryExperimentService;
import ai.core.server.memory.experiment.MemoryInjectionResult;
import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillProvider;
import ai.core.tool.registry.ToolRegistryFactory;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
class AgentSessionManagerCallerTest {
    @Test
    void sessionMutationRejectsCallerWhoDoesNotOwnSession() {
        var manager = new AgentSessionManager();
        manager.sessionRegistry = mock(SessionRegistry.class);
        when(manager.sessionRegistry.requireUserId("victim-session")).thenReturn("victim-user");

        assertThrows(ForbiddenException.class,
            () -> manager.requireSessionCaller("victim-session", "attacker-user"));
    }

    @Test
    void sessionMutationFailsClosedWhenSessionOwnerIsUnknown() {
        var manager = new AgentSessionManager();
        manager.sessionRegistry = mock(SessionRegistry.class);
        when(manager.sessionRegistry.requireUserId("unknown-session"))
                .thenThrow(new NotFoundException("session not found"));

        assertThrows(NotFoundException.class,
                () -> manager.requireSessionCaller("unknown-session", "caller-user"));
    }

    @Test
    void dynamicSkillMutationRejectsCallerWhoDoesNotOwnSession() {
        var manager = new AgentSessionManager();
        manager.sessionRegistry = mock(SessionRegistry.class);
        when(manager.sessionRegistry.requireUserId("victim-session")).thenReturn("victim-user");

        assertThrows(ForbiddenException.class,
                () -> manager.loadSkills("victim-session", List.of("skill-1"), "attacker-user"));
        assertThrows(ForbiddenException.class,
                () -> manager.unloadSkills("victim-session", List.of("skill-1"), "attacker-user"));
    }

    @Test
    void cachedSessionRejectsExplicitCallerWhoDoesNotOwnSession() {
        var harness = harness();
        var result = harness.manager.createSessionFromAgent(
                definition(AgentStatus.DRAFT), null, "owner-user", "chat");
        when(harness.manager.sessionRegistry.requireUserId(result.sessionId()))
                .thenReturn("owner-user");

        try {
            assertThrows(ForbiddenException.class,
                    () -> harness.manager.getSession(result.sessionId(), null, "attacker-user"));
        } finally {
            harness.manager.getSession(result.sessionId()).close();
        }
    }

    @Test
    void rebuiltSessionRevalidatesExplicitCallerAfterAtomicCacheInsertion() {
        var harness = harness();
        when(harness.manager.sessionRegistry.requireUserId("rebuilt-session"))
                .thenReturn("owner-user");
        var state = new SessionState();
        state.userId = "owner-user";
        state.config = new SessionConfig();

        var rebuilt = harness.manager.getSession("rebuilt-session", state, "owner-user");

        verify(harness.manager.sessionRegistry).requireUserId("rebuilt-session");
        rebuilt.close();
    }

    @Test
    void rebuiltSessionAcceptsOwnershipAlreadyClaimedByUnownedConsumer() {
        var harness = harness();
        var ownershipRegistry = mock(SessionOwnershipRegistry.class);
        harness.manager.ownershipRegistry = ownershipRegistry;
        var sessionAgentHelper = new SessionAgentHelper();
        sessionAgentHelper.ownershipRegistry = ownershipRegistry;
        harness.manager.sessionAgentHelper = sessionAgentHelper;
        when(ownershipRegistry.getOwner("rebuilt-session")).thenReturn("this-pod");
        when(ownershipRegistry.isOwner("rebuilt-session")).thenReturn(true);
        when(harness.manager.sessionRegistry.requireUserId("rebuilt-session"))
                .thenReturn("owner-user");
        var state = new SessionState();
        state.userId = "owner-user";
        state.config = new SessionConfig();

        var rebuilt = harness.manager.getSession("rebuilt-session", state, "owner-user");

        verify(ownershipRegistry, never()).claim("rebuilt-session");
        rebuilt.close();
    }

    @Test
    void a2aContextReuseRejectsMismatchedOwnerOrAgent() {
        var manager = new AgentSessionManager();
        manager.sessionRegistry = mock(SessionRegistry.class);
        when(manager.sessionRegistry.requireUserId("victim-session")).thenReturn("victim-user");
        when(manager.sessionRegistry.requireAgentId("victim-session")).thenReturn("agent-1");

        assertThrows(ForbiddenException.class,
            () -> manager.getSessionForAgentCaller("victim-session", "agent-1", "attacker-user"));
        assertThrows(ForbiddenException.class,
            () -> manager.getSessionForAgentCaller("victim-session", "agent-2", "victim-user"));
    }

    @Test
    void sessionCreationRejectsAnotherUsersDraftAgentAtTopLevelBoundary() {
        var harness = harness();
        var definition = definition(AgentStatus.DRAFT);

        var error = assertThrows(IllegalArgumentException.class,
            () -> harness.manager.createSessionFromAgent(definition, null, "other-user", "a2a"));

        assertEquals("agent is unavailable", error.getMessage());
    }

    @Test
    void nonOwnerPublicSessionUsesOnlyDetachedPublishedDefinition() {
        var harness = harness();
        var definition = definition(AgentStatus.PUBLISHED);
        definition.model = "edited-private-model";
        definition.skillIds = List.of("edited-private-skill");
        definition.subAgentIds = List.of("edited-private-sub-agent");
        definition.sandboxConfig = sandbox("edited-private-image");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.model = "published-model";
        definition.publishedConfig.sandboxConfig = sandbox("published-image");

        var result = harness.manager.createSessionFromAgent(definition, null, "other-user", "chat");

        var definitionCaptor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(harness.assembler).toSessionConfig(definitionCaptor.capture());
        var executable = definitionCaptor.getValue();
        assertNotSame(definition, executable);
        assertNotSame(definition.publishedConfig, executable.publishedConfig);
        assertEquals("other-user", executable.userId);
        assertEquals("published-model", executable.publishedConfig.model);
        assertNull(executable.model);
        assertEquals("published-image", executable.sandboxConfig.image);
        assertEquals(List.of(), result.loadedSkillIds());
        assertEquals(List.of(), result.loadedSubAgentIds());
        harness.manager.getSession(result.sessionId()).close();
    }

    @Test
    void ownerDraftTopLevelChatSessionCanCreateWithoutTools() {
        var harness = harness();
        var definition = definition(AgentStatus.DRAFT);

        var result = harness.manager.createSessionFromAgent(definition, null, "owner-user", "chat");

        assertEquals(List.of(), result.loadedSkillIds());
        assertEquals(List.of(), result.loadedSubAgentIds());
        var registration = ArgumentCaptor.forClass(SessionRegistry.SessionRegistration.class);
        verify(harness.manager.sessionRegistry).create(registration.capture());
        assertEquals(result.sessionId(), registration.getValue().sessionId());
        assertEquals("owner-user", registration.getValue().userId());
        assertEquals("agent-1", registration.getValue().agentId());
        assertEquals("chat", registration.getValue().source());
        harness.manager.getSession(result.sessionId()).close();
    }

    @Test
    void genericSessionRegistersDurableIdentityBeforeReturning() {
        var harness = harness();

        var sessionId = harness.manager.createSession(new SessionConfig(), "owner-user", "chat", "key-1");

        var registration = ArgumentCaptor.forClass(SessionRegistry.SessionRegistration.class);
        verify(harness.manager.sessionRegistry).create(registration.capture());
        assertEquals(sessionId, registration.getValue().sessionId());
        assertEquals("owner-user", registration.getValue().userId());
        assertNull(registration.getValue().agentId());
        assertEquals("key-1", registration.getValue().apiKeyId());
        harness.manager.getSession(sessionId).close();
    }

    @Test
    void definitionDependenciesPersistOnlyAfterRegistryCreation() {
        var harness = harness();
        var definition = definition(AgentStatus.DRAFT);
        definition.skillIds = List.of("owner-skill");
        when(harness.manager.skillService.resolveAccessibleSkills(List.of("owner-skill"), "owner-user"))
                .thenReturn(List.of(skill("owner-skill")));

        harness.manager.createSessionFromAgent(definition, null, "owner-user", "chat");

        var ordered = inOrder(harness.manager.sessionRegistry, harness.manager.chatMessageService);
        ordered.verify(harness.manager.sessionRegistry).create(any(SessionRegistry.SessionRegistration.class));
        ordered.verify(harness.manager.chatMessageService).addLoadedSkillIds(anyString(), anyList());
    }

    @Test
    void failedOwnershipClaimDoesNotRegisterOrExposeSession() {
        var harness = harness();
        when(harness.manager.sessionAgentHelper.claimOwnership(anyString())).thenReturn(false);

        assertThrows(IllegalStateException.class,
                () -> harness.manager.createSessionFromAgent(
                        definition(AgentStatus.DRAFT), null, "owner-user", "chat"));

        verify(harness.manager.sessionRegistry, never()).create(any());
    }

    @Test
    void registryFailureReleasesPartiallyCreatedRuntime() {
        var harness = harness();
        when(harness.manager.sessionRegistry.create(any()))
                .thenThrow(new IllegalStateException("mongo unavailable"));

        assertThrows(IllegalStateException.class,
                () -> harness.manager.createSessionFromAgent(
                        definition(AgentStatus.DRAFT), null, "owner-user", "chat"));

        verify(harness.manager.sandboxService).releaseSandbox(anyString());
        verify(harness.manager.sessionAgentHelper).releaseOwnership(anyString());
    }

    @Test
    void ownerDraftSessionRejectsMissingDefinitionSkillBeforeRegistration() {
        var harness = harness();
        var definition = definition(AgentStatus.DRAFT);
        definition.skillIds = List.of("missing-skill");
        when(harness.manager.skillService.resolveAccessibleSkills(List.of("missing-skill"), "owner-user"))
                .thenThrow(new ForbiddenException("skill is unavailable"));

        var error = assertThrows(ForbiddenException.class,
                () -> harness.manager.createSessionFromAgent(definition, null, "owner-user", "chat"));

        assertEquals("skill is unavailable", error.getMessage());
        verify(harness.manager.sessionRegistry, never()).create(any());
    }

    @Test
    void ownerDraftSessionLoadsCallerOwnedDefinitionSkill() {
        var harness = harness();
        var definition = definition(AgentStatus.DRAFT);
        definition.skillIds = List.of("owner-skill");
        when(harness.manager.skillService.resolveAccessibleSkills(List.of("owner-skill"), "owner-user"))
                .thenReturn(List.of(skill("owner-skill")));

        var result = harness.manager.createSessionFromAgent(definition, null, "owner-user", "chat");

        assertEquals(List.of("owner-skill"), result.loadedSkillIds());
        verify(harness.manager.skillService)
                .resolveAccessibleSkills(List.of("owner-skill"), "owner-user");
        harness.manager.getSession(result.sessionId()).close();
    }

    @Test
    void nonOwnerPublishedSessionLoadsOnlyValidatedFrozenSkills() {
        var harness = harness();
        var definition = definition(AgentStatus.PUBLISHED);
        definition.skillIds = List.of("editable-private-skill");
        definition.publishedConfig = new AgentPublishedConfig();
        definition.publishedConfig.skillIds = List.of("frozen-published-skill");
        definition.publishedConfig.skillValidationVersion = 1;
        when(harness.manager.skillService.resolveSkills(List.of("frozen-published-skill")))
                .thenReturn(List.of(skill("frozen-published-skill")));

        var result = harness.manager.createSessionFromAgent(definition, null, "viewer-user", "chat");

        assertEquals(List.of("frozen-published-skill"), result.loadedSkillIds());
        verify(harness.manager.skillService).resolveSkills(List.of("frozen-published-skill"));
        verify(harness.manager.skillService, never())
                .resolveAccessibleSkills(List.of("frozen-published-skill"), "viewer-user");
        harness.manager.getSession(result.sessionId()).close();
    }

    @Test
    void ownerDraftTopLevelA2aSessionCanCreateWithLegalTool() {
        var harness = harness();
        var definition = definition(AgentStatus.DRAFT);
        definition.tools = List.of(ToolRef.of("builtin-service", ToolSourceType.BUILTIN));

        var result = harness.manager.createSessionFromAgent(definition, null, "owner-user", "a2a");

        assertEquals(List.of(), result.loadedSkillIds());
        assertEquals(List.of(), result.loadedSubAgentIds());
        harness.manager.getSession(result.sessionId()).close();
    }

    private Harness harness() {
        var manager = new AgentSessionManager();
        var assembler = mock(SubAgentAssembler.class);
        when(assembler.toSessionConfig(any(AgentDefinition.class))).thenReturn(new SessionConfig());
        when(assembler.resolveTopLevelToolsToRegistry(any(AgentDefinition.class), anyString(), anyString()))
            .thenReturn(ToolRegistryFactory.createEmpty());
        when(assembler.assemble(anyList(), anyString(), anyString())).thenReturn(List.of());
        when(assembler.buildAgent(any(SubAgentAssembler.BuildAgentConfig.class))).thenAnswer(invocation -> {
            var config = invocation.getArgument(0, SubAgentAssembler.BuildAgentConfig.class);
            var agent = mock(Agent.class);
            when(agent.getExecutionContext()).thenReturn(config.context());
            when(agent.getToolCalls()).thenReturn(List.of());
            return agent;
        });
        manager.subAgentAssembler = assembler;
        manager.chatMessageService = mock(ChatMessageService.class);
        when(manager.chatMessageService.listener(anyString())).thenReturn(mock(AgentEventListener.class));
        manager.sandboxService = mock(SandboxService.class);
        manager.skillService = mock(SkillService.class);
        manager.mongoSkillProvider = mock(MongoSkillProvider.class);
        when(manager.mongoSkillProvider.scoped(anySet())).thenReturn(mock(SkillProvider.class));
        manager.skillArchiveBuilder = mock(SkillArchiveBuilder.class);
        manager.artifactSetup = mock(ChatArtifactSetup.class);
        manager.datasetService = mock(DatasetService.class);
        manager.datasetRecordService = mock(DatasetRecordService.class);
        manager.fileService = mock(FileService.class);
        manager.publicUrlConfiguration = mock(PublicUrlConfiguration.class);
        manager.systemSettingsService = mock(SystemSettingsService.class);
        manager.memoryExperimentService = mock(AgentMemoryExperimentService.class);
        when(manager.memoryExperimentService.prepareInjection(anyString())).thenReturn(MemoryInjectionResult.skipped());
        manager.eventPublisher = mock(EventPublisher.class);
        manager.sessionChannelService = mock(SessionChannelService.class);
        manager.sessionRegistry = mock(SessionRegistry.class);
        manager.sessionAgentHelper = mock(SessionAgentHelper.class);
        when(manager.sessionAgentHelper.resolveDatasetConfig(any(AgentDefinition.class), any(), any())).thenReturn(null);
        when(manager.sessionAgentHelper.claimOwnership(anyString())).thenReturn(true);
        when(manager.sessionAgentHelper.claimOrConfirmOwnership(anyString())).thenReturn(true);
        @SuppressWarnings("unchecked")
        MongoCollection<User> users = (MongoCollection<User>) mock(MongoCollection.class);
        when(users.get(anyString())).thenReturn(java.util.Optional.empty());
        manager.userCollection = users;
        return new Harness(manager, assembler);
    }

    private SkillMetadata skill(String name) {
        return SkillMetadata.builder(name, name, "owner/" + name).build();
    }

    private AgentDefinition definition(AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = "agent-1";
        definition.userId = "owner-user";
        definition.name = "Agent One";
        definition.type = DefinitionType.AGENT;
        definition.status = status;
        return definition;
    }

    private AgentSandboxConfig sandbox(String image) {
        var config = new AgentSandboxConfig();
        config.image = image;
        return config;
    }

    private record Harness(AgentSessionManager manager, SubAgentAssembler assembler) {
    }
}
