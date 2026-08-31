package ai.core.server.agent;

import ai.core.api.server.agent.CreateAgentRequest;
import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.api.server.agent.UpdateAgentRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentDatasetConfig;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.domain.User;
import ai.core.server.skill.SkillService;
import ai.core.server.systemprompt.SystemPromptService;
import ai.core.skill.SkillMetadata;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AgentDefinitionServiceTest {
    @Test
    void createRejectsUnknownSkillBeforeInsert() {
        assertCreateSkillUnavailable("unknown-skill");
    }

    @Test
    void updateRejectsUnknownSkillBeforeChangingDefinition() {
        assertUpdateSkillUnavailable("unknown-skill");
    }

    @Test
    void publishRejectsUnknownSkillBeforeFreezingDefinition() {
        assertPublishSkillUnavailable("unknown-skill");
    }

    @Test
    void publishAcceptsCallerOwnedSkillAndFreezesExactIds() {
        var collection = agentCollection();
        var agent = definition("agent-under-publish", "publisher", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.skillIds = List.of("owner-skill");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        var service = service(collection);
        when(service.skillService.resolveAccessibleSkills(List.of("owner-skill"), "publisher"))
                .thenReturn(List.of(SkillMetadata.builder(
                        "owner-skill", "Owner skill", "owner/owner-skill").build()));

        service.publish(agent.id, "publisher");

        assertEquals(List.of("owner-skill"), agent.publishedConfig.skillIds);
        assertEquals(1, agent.publishedConfig.skillValidationVersion);
        verify(collection).replace(agent);
    }

    @Test
    void rejectsLegacyPublishedLlmCallWithUnvalidatedSkills() {
        var collection = agentCollection();
        var target = definition("legacy-llm", "other-user", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        target.publishedConfig = config("legacy published prompt");
        target.publishedConfig.skillIds = List.of("historical-skill");
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertRuntimeDependencyUnavailable(service(collection), target.id, "caller-1", "historical-skill");
    }

    @Test
    void resolvesValidatedPublishedLlmCallSkillsWithoutViewerReauthorization() {
        var collection = agentCollection();
        var target = definition("shared-llm", "other-user", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        target.publishedConfig = config("published prompt");
        target.publishedConfig.skillIds = List.of("frozen-skill");
        target.publishedConfig.skillValidationVersion = 1;
        when(collection.get(target.id)).thenReturn(Optional.of(target));
        var service = service(collection);

        AgentDefinition executable = service.resolveLlmCallToolDefinition(target.id, "viewer");

        assertEquals(List.of("frozen-skill"), executable.publishedConfig.skillIds);
        verifyNoMoreInteractions(service.skillService);
    }

    @Test
    void toViewIgnoresInvalidSkillIds() {
        var service = new AgentDefinitionService();
        service.skillService = mock(SkillService.class);

        var skill = new SkillDefinition();
        skill.id = "skill-1";
        skill.name = "Skill One";
        when(service.skillService.get("skill-1")).thenReturn(skill);

        var entity = new AgentDefinition();
        entity.id = "agent-1";
        entity.name = "Agent One";
        entity.type = DefinitionType.AGENT;
        entity.status = AgentStatus.DRAFT;
        entity.skillIds = Arrays.asList(null, "", " ", "skill-1", "skill-1");
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        var view = service.toView(entity);

        assertEquals(List.of("skill-1"), view.skillIds);
        assertEquals(1, view.skills.size());
        assertEquals("skill-1", view.skills.get(0).id);
        assertEquals("Skill One", view.skills.get(0).name);
        verify(service.skillService).get("skill-1");
        verifyNoMoreInteractions(service.skillService);
    }

    @Test
    void toViewMapsEnableMemory() {
        var service = new AgentDefinitionService();
        service.skillService = mock(SkillService.class);

        var entity = new AgentDefinition();
        entity.id = "agent-1";
        entity.name = "Agent One";
        entity.type = DefinitionType.AGENT;
        entity.status = AgentStatus.DRAFT;
        entity.enableMemory = Boolean.FALSE;
        entity.createdAt = ZonedDateTime.now();
        entity.updatedAt = entity.createdAt;

        var view = service.toView(entity);

        assertEquals(Boolean.FALSE, view.enableMemory);
    }

    @Test
    void prioritizeDefaultAssistantMovesAssistantToFirst() {
        var recent = agent("recent-agent");
        var assistant = agent("default-assistant");
        var old = agent("old-agent");
        var agents = new ArrayList<>(List.of(recent, assistant, old));

        AgentListHelper.prioritizeDefaultAssistant(agents);

        assertSame(assistant, agents.get(0));
        assertEquals(List.of(assistant, recent, old), agents);
    }

    @Test
    void prioritizeDefaultAssistantKeepsOrderWithoutAssistant() {
        var recent = agent("recent-agent");
        var old = agent("old-agent");
        var agents = new ArrayList<>(List.of(recent, old));

        AgentListHelper.prioritizeDefaultAssistant(agents);

        assertEquals(List.of(recent, old), agents);
    }

    @Test
    void listWithQueryMatchesNameAndDescriptionCaseInsensitively() {
        var collection = agentCollection();
        var nameMatch = definition("hr-agent", "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        nameMatch.name = "HR Policy Agent";
        var descriptionMatch = definition("onboarding-agent", "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        descriptionMatch.name = "Onboarding";
        descriptionMatch.description = "handles HR onboarding";
        var noMatch = definition("sales-agent", "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        noMatch.name = "Sales Bot";
        when(collection.find(any(Query.class))).thenReturn(List.of(nameMatch, descriptionMatch, noMatch));
        var service = service(collection);
        when(service.userCollection.find(any(org.bson.Document.class))).thenReturn(List.of());

        var request = new ListAgentsRequest();
        request.query = "hr";
        var response = service.list("user-1", request);

        assertEquals(2, response.total);
        assertEquals(List.of("HR Policy Agent", "Onboarding"), response.agents.stream().map(a -> a.name).toList());
    }

    @Test
    void listWithQueryPaginatesFilteredMatches() {
        var collection = agentCollection();
        var agents = new ArrayList<AgentDefinition>();
        for (int i = 1; i <= 5; i++) {
            var entity = definition("agent-" + i, "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
            entity.name = "HR Agent " + entity.id;
            agents.add(entity);
        }
        when(collection.find(any(Query.class))).thenReturn(agents);
        var service = service(collection);
        when(service.userCollection.find(any(org.bson.Document.class))).thenReturn(List.of());

        var request = new ListAgentsRequest();
        request.query = "hr";
        request.page = 2;
        request.limit = 2;
        var response = service.list("user-1", request);

        assertEquals(5, response.total);
        assertEquals(2, response.agents.size());
        assertEquals(List.of("HR Agent agent-3", "HR Agent agent-4"), response.agents.stream().map(a -> a.name).toList());
        assertEquals(2, response.page);
        assertEquals(2, response.limit);
    }

    @Test
    void favoriteInitializesNullFieldThenAddsAgentId() {
        var collection = agentCollection();
        var agent = definition("agent-1", "owner", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        var service = service(collection);

        service.favorite(agent.id, "user-1");

        var updates = ArgumentCaptor.forClass(Bson.class);
        verify(service.userCollection, times(2)).update(any(Bson.class), updates.capture());
        var values = updates.getAllValues();
        assertTrue(values.get(0).toBsonDocument().containsKey("$set"));
        assertTrue(values.get(1).toBsonDocument().containsKey("$addToSet"));
    }

    @Test
    void favoriteRejectsMissingAgent() {
        var collection = agentCollection();
        when(collection.get("missing")).thenReturn(Optional.empty());
        var service = service(collection);

        assertThrows(RuntimeException.class, () -> service.favorite("missing", "user-1"));

        verifyNoMoreInteractions(service.userCollection);
    }

    @Test
    void unfavoriteInitializesNullFieldThenPullsAgentId() {
        var service = service(agentCollection());

        service.unfavorite("agent-1", "user-1");

        var updates = ArgumentCaptor.forClass(Bson.class);
        verify(service.userCollection, times(2)).update(any(Bson.class), updates.capture());
        var values = updates.getAllValues();
        assertTrue(values.get(0).toBsonDocument().containsKey("$set"));
        assertTrue(values.get(1).toBsonDocument().containsKey("$pull"));
    }

    @Test
    void favoritesReturnsFavoritedAgentsNewestFirstWithFlag() {
        var collection = agentCollection();
        var first = definition("agent-first", "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        var second = definition("agent-second", "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        when(collection.find(any(Bson.class))).thenReturn(List.of(first, second));
        var service = service(collection);
        var user = new User();
        user.id = "user-1";
        user.favoriteAgentIds = List.of("agent-first", "agent-second");
        when(service.userCollection.get("user-1")).thenReturn(Optional.of(user));

        var response = service.favorites("user-1");

        assertEquals(2, response.total);
        assertEquals(List.of("agent-second", "agent-first"), response.agents.stream().map(a -> a.id).toList());
        assertTrue(response.agents.stream().allMatch(a -> Boolean.TRUE.equals(a.favorite)));
    }

    @Test
    void favoritesReturnsEmptyWhenUserHasNone() {
        var service = service(agentCollection());
        var user = new User();
        user.id = "user-1";
        when(service.userCollection.get("user-1")).thenReturn(Optional.of(user));

        var response = service.favorites("user-1");

        assertEquals(0, response.total);
        assertTrue(response.agents.isEmpty());
    }

    @Test
    void listMarksFavoritedAgents() {
        var collection = agentCollection();
        var agent = definition("agent-1", "user-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        when(collection.find(any(Query.class))).thenReturn(List.of(agent));
        var service = service(collection);
        when(service.userCollection.find(any(org.bson.Document.class))).thenReturn(List.of());
        var user = new User();
        user.id = "user-1";
        user.favoriteAgentIds = List.of("agent-1");
        when(service.userCollection.get("user-1")).thenReturn(Optional.of(user));

        var response = service.list("user-1", new ListAgentsRequest());

        assertEquals(Boolean.TRUE, response.agents.get(0).favorite);
    }

    @Test
    void resolvesCallerOwnedEditableLlmCallAsIsolatedExecutableConfig() {
        var collection = agentCollection();
        var target = definition("llm-own", "caller-1", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        target.systemPrompt = "current editable prompt";
        target.timeoutSeconds = 42;
        target.publishedConfig = config("stale published prompt");
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        AgentDefinition executable = service(collection).resolveLlmCallToolDefinition(target.id, "caller-1");

        assertEquals("current editable prompt", executable.publishedConfig.systemPrompt);
        assertEquals(42, executable.publishedConfig.timeoutSeconds);
        assertNull(executable.systemPrompt);
        assertNull(executable.timeoutSeconds);
    }

    @Test
    void resolvesOtherTenantPublishedLlmCallWithoutEditableFieldFallback() {
        var collection = agentCollection();
        var target = definition("llm-shared", "other-user", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        target.systemPrompt = "private editable prompt";
        target.timeoutSeconds = 999;
        target.publishedConfig = config("published prompt");
        target.publishedConfig.timeoutSeconds = 30;
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        AgentDefinition executable = service(collection).resolveLlmCallToolDefinition(target.id, "caller-1");

        assertEquals("published prompt", executable.publishedConfig.systemPrompt);
        assertEquals(30, executable.publishedConfig.timeoutSeconds);
        assertNull(executable.systemPrompt);
        assertNull(executable.timeoutSeconds);
        assertFalse(executable.publishedConfig.systemPrompt.contains("private"));
    }

    @Test
    void rejectsOtherTenantPublishedLlmCallWithLiveSystemPromptReference() {
        var collection = agentCollection();
        var target = definition("llm-shared", "other-user", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        target.publishedConfig = new AgentPublishedConfig();
        target.publishedConfig.systemPromptId = "mutable-prompt";
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertRuntimeDependencyUnavailable(service(collection), target.id, "caller-1", "mutable-prompt");
    }

    @Test
    void rejectsOtherTenantDraftLlmCallAtRuntime() {
        var collection = agentCollection();
        var target = definition("llm-private", "other-user", DefinitionType.LLM_CALL, AgentStatus.DRAFT);
        target.systemPrompt = "private target prompt";
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertRuntimeDependencyUnavailable(service(collection), target.id, "caller-1", "private target prompt");
    }

    @Test
    void rejectsMissingLlmCallAtRuntime() {
        var collection = agentCollection();
        when(collection.get("missing")).thenReturn(Optional.empty());

        assertRuntimeDependencyUnavailable(service(collection), "missing", "caller-1", "missing");
    }

    @Test
    void rejectsWrongTypeLlmCallTargetAtRuntime() {
        var collection = agentCollection();
        var target = definition("agent-target", "caller-1", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        target.publishedConfig = config("public agent config");
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertRuntimeDependencyUnavailable(service(collection), target.id, "caller-1", target.id);
    }

    @Test
    void rejectsSystemDraftLlmCallAtRuntimeEvenForRecordedOwner() {
        var collection = agentCollection();
        var target = definition("system-llm", "caller-1", DefinitionType.LLM_CALL, AgentStatus.DRAFT);
        target.systemDefault = Boolean.TRUE;
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertRuntimeDependencyUnavailable(service(collection), target.id, "caller-1", target.id);
    }

    @Test
    void rejectsLlmCallRuntimeLookupWithoutCaller() {
        var collection = agentCollection();
        var target = definition("llm-own", "caller-1", DefinitionType.LLM_CALL, AgentStatus.DRAFT);
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertRuntimeDependencyUnavailable(service(collection), target.id, null, target.id);
    }

    @Test
    void agentPublishRejectsOwnDraftLlmCallDependencyBeforeMutation() {
        var collection = agentCollection();
        var agent = publishingAgent(ToolRef.fromLegacyToolId("llm-call:own-draft"));
        var target = definition("own-draft", "publisher", DefinitionType.LLM_CALL, AgentStatus.DRAFT);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertPublishDependencyUnavailable(service(collection), collection, agent);
    }

    @Test
    void agentPublishRejectsOtherTenantDraftLlmCallDependencyBeforeMutation() {
        var collection = agentCollection();
        var agent = publishingAgent(ToolRef.fromLegacyToolId("llm-call:other-draft"));
        var target = definition("other-draft", "other-user", DefinitionType.LLM_CALL, AgentStatus.DRAFT);
        target.systemPrompt = "tenant secret";
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertPublishDependencyUnavailable(service(collection), collection, agent);
    }

    @Test
    void agentPublishRejectsMissingLlmCallDependencyBeforeMutation() {
        var collection = agentCollection();
        var agent = publishingAgent(ToolRef.fromLegacyToolId("llm-call:missing"));
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get("missing")).thenReturn(Optional.empty());

        assertPublishDependencyUnavailable(service(collection), collection, agent);
    }

    @Test
    void agentPublishRejectsMalformedLlmCallRefBeforeLookupOrMutation() {
        var collection = agentCollection();
        var agent = publishingAgent(ToolRef.of("forged-target", ToolSourceType.LLM_CALL));
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));

        assertPublishDependencyUnavailable(service(collection), collection, agent);
        verify(collection, never()).get("forged-target");
    }

    @Test
    void agentPublishRejectsWrongTypeLlmCallDependencyBeforeMutation() {
        var collection = agentCollection();
        var agent = publishingAgent(ToolRef.fromLegacyToolId("llm-call:not-an-llm"));
        var target = definition("not-an-llm", "other-user", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        target.publishedConfig = config("published agent config");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        assertPublishDependencyUnavailable(service(collection), collection, agent);
    }

    @Test
    void agentPublishAcceptsUsablePublishedLlmCallDependency() {
        var collection = agentCollection();
        var agent = publishingAgent(ToolRef.fromLegacyToolId("llm-call:shared-llm"));
        var target = definition("shared-llm", "other-user", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        target.publishedConfig = config("published llm config");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(target.id)).thenReturn(Optional.of(target));

        service(collection).publish(agent.id, "publisher");

        assertEquals(AgentStatus.PUBLISHED, agent.status);
        assertEquals(agent.tools, agent.publishedConfig.tools);
        verify(collection).replace(agent);
    }

    @Test
    void agentPublishRejectsDraftSubAgentDependencyBeforeMutation() {
        var collection = agentCollection();
        var agent = definition("agent-under-publish", "publisher", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.subAgentIds = List.of("draft-sub");
        var subAgent = definition("draft-sub", "publisher", DefinitionType.AGENT, AgentStatus.DRAFT);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(subAgent.id)).thenReturn(Optional.of(subAgent));

        assertPublishSubAgentUnavailable(service(collection), collection, agent);
    }

    @Test
    void agentPublishRejectsPublishedLlmCallDefinitionAsSubAgentBeforeMutation() {
        var collection = agentCollection();
        var agent = definition("agent-under-publish", "publisher", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.subAgentIds = List.of("published-llm");
        var subAgent = definition("published-llm", "other", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED);
        subAgent.publishedConfig = config("public llm");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(subAgent.id)).thenReturn(Optional.of(subAgent));

        assertPublishSubAgentUnavailable(service(collection), collection, agent);
    }

    @Test
    void agentPublishAcceptsUsablePublishedAgentDependency() {
        var collection = agentCollection();
        var agent = definition("agent-under-publish", "publisher", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.subAgentIds = List.of("published-sub");
        var subAgent = definition("published-sub", "other", DefinitionType.AGENT, AgentStatus.PUBLISHED);
        subAgent.publishedConfig = config("public sub-agent");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        when(collection.get(subAgent.id)).thenReturn(Optional.of(subAgent));

        service(collection).publish(agent.id, "publisher");

        assertEquals(List.of("published-sub"), agent.publishedConfig.subAgentIds);
        verify(collection).replace(agent);
    }

    @Test
    void publishMaterializesSystemPromptContentIntoImmutableConfig() {
        var collection = agentCollection();
        var agent = definition("llm-under-publish", "publisher", DefinitionType.LLM_CALL, AgentStatus.DRAFT);
        agent.systemPromptId = "prompt-1";
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        var service = service(collection);
        when(service.systemPromptService.resolveContent("prompt-1")).thenReturn("frozen prompt content");

        service.publish(agent.id, "publisher");

        assertEquals("frozen prompt content", agent.publishedConfig.systemPrompt);
        assertNull(agent.publishedConfig.systemPromptId);
        verify(service.systemPromptService).resolveContent("prompt-1");
        verify(collection).replace(agent);
    }

    @Test
    void publishAllowsNonOwnerAndRecordsLastModifier() {
        var collection = agentCollection();
        var agent = definition("shared-agent", "owner", DefinitionType.AGENT, AgentStatus.DRAFT);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));

        service(collection).publish(agent.id, "collaborator");

        assertEquals(AgentStatus.PUBLISHED, agent.status);
        assertEquals("collaborator", agent.updatedBy);
        verify(collection).replace(agent);
    }

    @Test
    void publishedConfigWithNoDatasetsDoesNotFallBackToEditableDatasetConfig() {
        var editableDataset = new AgentDatasetConfig();
        editableDataset.datasetId = "private-editable-dataset";
        var definition = new AgentDefinition();
        definition.datasetConfig = List.of(editableDataset);
        definition.publishedConfig = new AgentPublishedConfig();

        assertNull(AgentDefinitionService.resolveDatasetConfig(definition));
        assertNull(AgentDefinitionService.resolveOutputDatasetId(definition));
    }

    private AgentDefinition agent(String id) {
        var agent = new AgentDefinition();
        agent.id = id;
        return agent;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<AgentDefinition> agentCollection() {
        return mock(MongoCollection.class);
    }

    @SuppressWarnings("unchecked")
    private AgentDefinitionService service(MongoCollection<AgentDefinition> collection) {
        var service = new AgentDefinitionService();
        service.agentDefinitionCollection = collection;
        service.userCollection = mock(MongoCollection.class);
        service.skillService = mock(SkillService.class);
        service.systemPromptService = mock(SystemPromptService.class);
        return service;
    }

    private AgentDefinition definition(String id, String userId, DefinitionType type, AgentStatus status) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.userId = userId;
        definition.name = id;
        definition.type = type;
        definition.status = status;
        definition.createdAt = ZonedDateTime.parse("2026-08-05T10:00:00Z");
        definition.updatedAt = definition.createdAt;
        return definition;
    }

    private AgentPublishedConfig config(String systemPrompt) {
        var config = new AgentPublishedConfig();
        config.systemPrompt = systemPrompt;
        return config;
    }

    private AgentDefinition publishingAgent(ToolRef tool) {
        var agent = definition("agent-under-publish", "publisher", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.tools = List.of(tool);
        return agent;
    }

    private void assertCreateSkillUnavailable(String skillId) {
        var collection = agentCollection();
        when(collection.findOne(any(Bson.class))).thenReturn(Optional.empty());
        var service = service(collection);
        when(service.skillService.resolveAccessibleSkills(List.of(skillId), "owner"))
                .thenThrow(new ForbiddenException("skill is unavailable"));
        var request = new CreateAgentRequest();
        request.name = "agent";
        request.skillIds = List.of(skillId);

        var error = assertThrows(ForbiddenException.class, () -> service.create(request, "owner"));

        assertEquals("skill is unavailable", error.getMessage());
        verify(collection, never()).insert(any());
    }

    private void assertUpdateSkillUnavailable(String skillId) {
        var collection = agentCollection();
        var agent = definition("agent", "owner", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.skillIds = List.of("existing-owner-skill");
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        var service = service(collection);
        when(service.skillService.resolveAccessibleSkills(List.of(skillId), "owner"))
                .thenThrow(new ForbiddenException("skill is unavailable"));
        var request = new UpdateAgentRequest();
        request.skillIds = List.of(skillId);

        var error = assertThrows(ForbiddenException.class,
                () -> service.update(agent.id, request, "owner"));

        assertEquals("skill is unavailable", error.getMessage());
        assertEquals(List.of("existing-owner-skill"), agent.skillIds);
        verify(collection, never()).replace(any());
    }

    private void assertPublishSkillUnavailable(String skillId) {
        var collection = agentCollection();
        var agent = definition("agent", "owner", DefinitionType.AGENT, AgentStatus.DRAFT);
        agent.skillIds = List.of(skillId);
        when(collection.get(agent.id)).thenReturn(Optional.of(agent));
        var service = service(collection);
        when(service.skillService.resolveAccessibleSkills(List.of(skillId), "owner"))
                .thenThrow(new ForbiddenException("skill is unavailable"));

        var error = assertThrows(ForbiddenException.class,
                () -> service.publish(agent.id, "owner"));

        assertEquals("skill is unavailable", error.getMessage());
        assertEquals(AgentStatus.DRAFT, agent.status);
        assertNull(agent.publishedConfig);
        verify(collection, never()).replace(any());
    }

    private void assertRuntimeDependencyUnavailable(AgentDefinitionService service, String targetId,
                                                    String callerId, String privateValue) {
        var error = assertThrows(IllegalArgumentException.class,
            () -> service.resolveLlmCallToolDefinition(targetId, callerId));
        assertEquals("LLM call tool is unavailable", error.getMessage());
        assertFalse(error.getMessage().contains(privateValue));
    }

    private void assertPublishDependencyUnavailable(AgentDefinitionService service,
                                                    MongoCollection<AgentDefinition> collection,
                                                    AgentDefinition agent) {
        var error = assertThrows(BadRequestException.class, () -> service.publish(agent.id, "publisher"));
        assertEquals("agent has an unavailable LLM call dependency", error.getMessage());
        assertEquals(AgentStatus.DRAFT, agent.status);
        assertNull(agent.publishedConfig);
        assertTrue(agent.tools.stream().noneMatch(tool -> error.getMessage().contains(tool.id)));
        verify(collection, never()).replace(any());
    }

    private void assertPublishSubAgentUnavailable(AgentDefinitionService service,
                                                  MongoCollection<AgentDefinition> collection,
                                                  AgentDefinition agent) {
        var error = assertThrows(BadRequestException.class, () -> service.publish(agent.id, "publisher"));
        assertEquals("agent has an unavailable sub-agent dependency", error.getMessage());
        assertEquals(AgentStatus.DRAFT, agent.status);
        assertNull(agent.publishedConfig);
        assertTrue(agent.subAgentIds.stream().noneMatch(error.getMessage()::contains));
        verify(collection, never()).replace(any());
    }
}
