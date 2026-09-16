package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ResourcePermission;
import ai.core.server.domain.SkillDefinition;
import ai.core.server.domain.User;
import ai.core.server.skill.SkillService;
import com.mongodb.MongoWriteException;
import core.framework.mongo.MongoCollection;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PersonalAssistantServiceTest {
    @Test
    void onlyTheSharedTemplateIsExcludedFromMemoryExtraction() {
        assertTrue(PersonalAssistantService.isForkableTemplate(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID));
        assertFalse(PersonalAssistantService.isForkableTemplate("assistant:user-1"));
        assertFalse(PersonalAssistantService.isForkableTemplate("assistant"));
        assertFalse(PersonalAssistantService.isForkableTemplate(null));
    }

    @Test
    void personalAssistantIsRecognizedByItsForkOrigin() {
        var definition = definition("assistant:user-1");
        definition.forkedFrom = PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID;

        assertTrue(PersonalAssistantService.isPersonalAssistant(definition));
        assertFalse(PersonalAssistantService.isPersonalAssistant(new AgentDefinition()));
        assertFalse(PersonalAssistantService.isPersonalAssistant(null));
    }

    @Test
    void resolvePassesThroughIdsThatAreNotTemplates() {
        var service = service();
        var users = users();
        service.userCollection = users;

        assertEquals("agent-1", service.resolve("agent-1", "user-1"));
        assertEquals("assistant:user-1", service.resolve("assistant:user-1", "user-1"));
        assertNull(service.resolve(null, "user-1"));
        verify(users, never()).get(any());
    }

    @Test
    void resolveKeepsTemplateForCallersThatCannotOwnAnAgent() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID))
                .thenReturn(Optional.of(template()));
        when(users.get("system")).thenReturn(Optional.empty());
        when(users.get("api-1")).thenReturn(Optional.of(user("api-1", "api")));
        when(users.get("ghost")).thenReturn(Optional.empty());

        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "system"));
        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "api-1"));
        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "ghost"));
        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, null));
        verify(service.agentDefinitionCollection, never()).insert(any());
    }

    @Test
    void resolveKeepsTemplateWhenTheTemplateRecordIsGone() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));

        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1"));
        verify(service.agentDefinitionCollection, never()).insert(any());
    }

    @Test
    void resolveForksOnceAndReusesTheCopyOnLaterCalls() {
        var service = service();
        var users = users();
        service.userCollection = users;
        var template = template();
        template.publishedConfig = published("template prompt");
        template.publishedConfig.datasetConfig = null;
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));

        var first = service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");
        var fork = capturedFork(service);
        when(service.agentDefinitionCollection.get("assistant:user-1")).thenReturn(Optional.of(fork));
        var second = service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        assertEquals("assistant:user-1", first);
        assertEquals(first, second);
        assertEquals("user-1", fork.userId);
        assertEquals("Alice's Assistant", fork.name);
        assertEquals("alice's assistant", fork.nameKey);
        assertEquals(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, fork.forkedFrom);
        assertEquals(AgentStatus.PUBLISHED, fork.status);
        assertEquals(DefinitionType.AGENT, fork.type);
        assertNull(fork.systemDefault);
        assertEquals(Boolean.TRUE, fork.enableMemory);
        assertEquals(Boolean.TRUE, fork.publishedConfig.enableMemory);
        assertNull(fork.systemPromptId);
        assertNull(fork.datasetConfig);
        assertNull(fork.publishedConfig.systemPromptId);
        assertEquals("template prompt", fork.systemPrompt);
    }

    @Test
    void resolveFallsBackToEmailLocalPartWhenDisplayNameIsMissing() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template()));
        when(users.get("user-1")).thenReturn(Optional.of(user("user-1", "internal")));

        service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        assertEquals("alice's Assistant", capturedFork(service).name);
    }

    @Test
    void resolveRetriesWithSuffixWhenTheNameIsAlreadyTaken() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template()));
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));
        when(service.agentDefinitionCollection.findOne(any(Bson.class)))
                .thenReturn(Optional.of(new AgentDefinition()))
                .thenReturn(Optional.empty());

        service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        assertEquals("Alice's Assistant 2", capturedFork(service).name);
    }

    @Test
    void forkGrantsAgentPermissionOnlyToWhitelistedUsers() {
        var service = service();
        var users = users();
        service.userCollection = users;
        var restricted = platformUser("user-1");
        restricted.permissions = List.of(permission("skill", "skill-1"));
        when(users.get("user-1")).thenReturn(Optional.of(restricted));
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template()));

        service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(users, atLeastOnce()).update(any(Bson.class), update.capture());
        assertTrue(contains(update.getAllValues(), "permissions", "assistant:user-1"), update.getAllValues().toString());
    }

    @Test
    void forkPinsTheCopyIntoTheCallersFavorites() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template()));

        service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(users, times(2)).update(any(Bson.class), update.capture());
        assertTrue(contains(update.getAllValues(), "favorite_agent_ids", "assistant:user-1"), update.getAllValues().toString());
    }

    @Test
    void forkKeepsUnrestrictedUsersPermissionless() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template()));

        service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        var update = ArgumentCaptor.forClass(Bson.class);
        verify(users, times(2)).update(any(Bson.class), update.capture());
        assertFalse(contains(update.getAllValues(), "permissions", "assistant:user-1"), update.getAllValues().toString());
    }

    @Test
    void concurrentForkReturnsTheWinnersCopy() {
        var service = service();
        var users = users();
        service.userCollection = users;
        var template = template();
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));
        var winner = new AgentDefinition();
        winner.id = "assistant:user-1";
        doThrow(duplicateKey()).when(service.agentDefinitionCollection).insert(any());
        when(service.agentDefinitionCollection.get("assistant:user-1")).thenReturn(Optional.of(winner));

        assertEquals("assistant:user-1", service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1"));
        verify(users, never()).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void forkDropsUnreachableDependencies() {
        var service = service();
        var users = users();
        var skills = mock(SkillService.class);
        service.userCollection = users;
        service.skillService = skills;
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));
        var template = template();
        template.skillIds = List.of("kept-skill", "missing-skill");
        template.subAgentIds = List.of("missing-sub-agent");
        when(service.agentDefinitionCollection.get(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(skills.get("kept-skill")).thenReturn(mock(SkillDefinition.class));
        when(skills.get("missing-skill")).thenThrow(new RuntimeException("skill not found"));

        service.resolve(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID, "user-1");

        var fork = capturedFork(service);
        assertEquals(List.of("kept-skill"), fork.skillIds);
        assertNull(fork.subAgentIds);
    }

    @Test
    void findOrForkOnlyForksTheSharedTemplate() {
        var service = service();
        var users = users();
        service.userCollection = users;
        when(users.get("user-1")).thenReturn(Optional.of(platformUser("user-1")));

        assertNull(service.findOrFork(null, "user-1"));
        assertNull(service.findOrFork(definition("agent-1"), "user-1"));
        verify(service.agentDefinitionCollection, never()).insert(any());
    }

    @Test
    void findPersonalAssistantSkipsForking() {
        var service = service();
        var existing = new AgentDefinition();
        existing.id = "assistant:user-1";
        when(service.agentDefinitionCollection.get("assistant:user-1")).thenReturn(Optional.of(existing));

        assertSame(existing, service.findPersonalAssistant("user-1"));
        assertSame(existing, service.findOrFork(template(), "user-1"));
        assertNull(service.findPersonalAssistant(null));
        assertNull(service.findPersonalAssistant(" "));
        verify(service.agentDefinitionCollection, never()).insert(any());
    }

    private AgentDefinition capturedFork(PersonalAssistantService service) {
        var captor = ArgumentCaptor.forClass(AgentDefinition.class);
        verify(service.agentDefinitionCollection).insert(captor.capture());
        return captor.getValue();
    }

    private boolean contains(List<Bson> updates, String field, String value) {
        return updates.stream().anyMatch(update -> {
            var document = update.toBsonDocument().toJson();
            return document.contains(field) && document.contains(value);
        });
    }

    @SuppressWarnings("unchecked")
    private PersonalAssistantService service() {
        var service = new PersonalAssistantService();
        service.agentDefinitionCollection = mock(MongoCollection.class);
        service.userCollection = mock(MongoCollection.class);
        service.skillService = mock(SkillService.class);
        return service;
    }

    @SuppressWarnings("unchecked")
    private MongoCollection<User> users() {
        return mock(MongoCollection.class);
    }

    private AgentDefinition template() {
        var template = definition(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID);
        template.userId = "system";
        template.name = "Assistant";
        template.systemDefault = Boolean.TRUE;
        template.status = AgentStatus.PUBLISHED;
        template.enableMemory = Boolean.FALSE;
        template.systemPrompt = "template prompt";
        return template;
    }

    private AgentDefinition definition(String id) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.type = DefinitionType.AGENT;
        definition.status = AgentStatus.PUBLISHED;
        return definition;
    }

    private AgentPublishedConfig published(String systemPrompt) {
        var config = new AgentPublishedConfig();
        config.systemPrompt = systemPrompt;
        config.skillIds = new ArrayList<>(List.of());
        config.subAgentIds = new ArrayList<>(List.of());
        return config;
    }

    private User platformUser(String id) {
        var user = user(id, "internal");
        user.name = "Alice";
        return user;
    }

    private User user(String id, String userType) {
        var user = new User();
        user.id = id;
        user.email = "alice@example.com";
        user.userType = userType;
        return user;
    }

    private ResourcePermission permission(String resourceType, String resourceId) {
        var permission = new ResourcePermission();
        permission.resourceType = resourceType;
        permission.resourceId = resourceId;
        return permission;
    }

    private MongoWriteException duplicateKey() {
        var duplicate = mock(MongoWriteException.class);
        when(duplicate.getCode()).thenReturn(11000);
        return duplicate;
    }
}
