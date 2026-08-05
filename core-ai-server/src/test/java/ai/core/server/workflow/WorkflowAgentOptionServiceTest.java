package ai.core.server.workflow;

import ai.core.api.server.workflow.ListWorkflowAgentOptionsRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import com.mongodb.MongoClientSettings;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowAgentOptionServiceTest {
    private static final String USER_ID = "user-1";

    @Test
    void mineFilterAllowsOwnedDraftsAndExcludesSystemDefaults() {
        var filter = bson(WorkflowAgentOptionService.filter("mine", DefinitionType.AGENT, "rev", USER_ID));

        assertEquals(USER_ID, field(filter, "user_id").getString("user_id").getValue());
        assertEquals("AGENT", field(filter, "type").getString("type").getValue());
        assertTrue(field(filter, "system_default").getDocument("system_default").getBoolean("$ne").getValue());
        assertEquals("^\\Qrev\\E", field(filter, "name_key").getRegularExpression("name_key").getPattern());
        assertFalse(filter.toJson().contains("\"status\""));
        assertFalse(filter.toJson().contains("\"published_config\""));
    }

    @Test
    void sharedFilterRequiresPublishedConfigAndExcludesOwnedNonSystemAgents() {
        var filter = bson(WorkflowAgentOptionService.filter("shared", DefinitionType.LLM_CALL, "", USER_ID));
        String json = filter.toJson();

        assertTrue(json.contains("\"status\": \"PUBLISHED\""));
        assertTrue(json.contains("\"type\": \"LLM_CALL\""));
        var configConditions = filter.getArray("$and");
        assertTrue(configConditions.stream().map(value -> value.asDocument().toJson())
            .anyMatch(clause -> clause.contains("published_config") && clause.contains("$exists") && clause.contains("true")));
        assertTrue(configConditions.stream().map(value -> value.asDocument().toJson())
            .anyMatch(clause -> clause.contains("published_config") && clause.contains("$ne") && clause.contains("null")));
        var accessAlternatives = field(filter, "$or").getArray("$or");
        assertTrue(accessAlternatives.stream().map(BsonValue::asDocument)
            .anyMatch(clause -> clause.containsKey("system_default") && clause.getBoolean("system_default").getValue()));
        assertTrue(accessAlternatives.stream().map(BsonValue::asDocument)
            .anyMatch(clause -> clause.containsKey("user_id")
                                && USER_ID.equals(clause.getDocument("user_id").getString("$ne").getValue())));
        assertFalse(json.contains("\"name_key\""));
    }

    @Test
    void queryPrefixUsesNormalizedLiteralPrefix() {
        var filter = bson(WorkflowAgentOptionService.filter("mine", DefinitionType.AGENT, "  Rev.*  ", USER_ID));

        assertEquals("^\\Qrev.*\\E", field(filter, "name_key").getRegularExpression("name_key").getPattern());
    }

    @Test
    void invalidScopeAndTypeHaveBadRequestContract() {
        assertBadRequest(request("private", "AGENT"), "invalid scope");
        assertBadRequest(request("mine", "TOOL"), "invalid type");
        assertBadRequest(request(null, "AGENT"), "invalid scope");
        assertBadRequest(request("mine", null), "invalid type");
    }

    @Test
    void limitIsClampedToOneThroughFifty() {
        assertPaging(1, 1, 0, 1);
        assertPaging(1, 50, 75, 50);
        assertPaging(1, 20, null, 20);
    }

    @Test
    void pageTwoUsesStableNameOrdering() {
        var service = service();
        var request = request("mine", "AGENT");
        request.page = 2;
        request.limit = 20;

        service.list(USER_ID, request);

        var query = ArgumentCaptor.forClass(Query.class);
        verify(service.agentDefinitionCollection).find(query.capture());
        assertEquals(20, query.getValue().skip);
        assertEquals(20, query.getValue().limit);
        var sort = bson(query.getValue().sort);
        assertEquals(List.of("name_key", "_id"), sort.keySet().stream().toList());
        assertEquals(1, sort.getInt32("name_key").getValue());
        assertEquals(1, sort.getInt32("_id").getValue());
    }

    @Test
    void selectedResolutionDoesNotReturnInaccessibleDraft() {
        var service = service();
        when(service.agentDefinitionCollection.get("private-agent"))
            .thenReturn(Optional.of(agent("private-agent", "other", DefinitionType.AGENT, AgentStatus.DRAFT, false, false)));
        var request = request("mine", "AGENT");
        request.selectedId = "private-agent";

        var response = service.list(USER_ID, request);

        assertNull(response.selected);
    }

    @Test
    void selectedResolutionAllowsOwnedDraftIndependentOfCurrentScope() {
        var service = service();
        when(service.agentDefinitionCollection.get("owned-draft"))
            .thenReturn(Optional.of(agent("owned-draft", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, false, false)));
        var request = request("shared", "AGENT");
        request.selectedId = "owned-draft";

        var response = service.list(USER_ID, request);

        assertEquals("owned-draft", response.selected.id);
        assertEquals("MINE", response.selected.ownership);
    }

    @Test
    void selectedResolutionRequiresMatchingType() {
        var service = service();
        when(service.agentDefinitionCollection.get("owned-llm"))
            .thenReturn(Optional.of(agent("owned-llm", USER_ID, DefinitionType.LLM_CALL, AgentStatus.DRAFT, false, false)));
        var request = request("mine", "AGENT");
        request.selectedId = "owned-llm";

        var response = service.list(USER_ID, request);

        assertNull(response.selected);
    }

    @Test
    void selectedResolutionRequiresUsablePublishedConfigForOtherOwner() {
        var service = service();
        when(service.agentDefinitionCollection.get("published-without-config"))
            .thenReturn(Optional.of(agent("published-without-config", "other", DefinitionType.AGENT, AgentStatus.PUBLISHED, false, false)));
        when(service.agentDefinitionCollection.get("published-with-config"))
            .thenReturn(Optional.of(agent("published-with-config", "other", DefinitionType.AGENT, AgentStatus.PUBLISHED, false, true)));

        var unavailable = request("mine", "AGENT");
        unavailable.selectedId = "published-without-config";
        var available = request("mine", "AGENT");
        available.selectedId = "published-with-config";

        assertNull(service.list(USER_ID, unavailable).selected);
        assertEquals("SHARED", service.list(USER_ID, available).selected.ownership);
    }

    @Test
    void accessPolicyCoversOwnedSystemAndPublishedBoundaries() {
        var ownedDraft = agent("owned", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, false, false);
        var ownedSystemDraft = agent("system", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, true, false);
        var otherPublished = agent("shared", "other", DefinitionType.AGENT, AgentStatus.PUBLISHED, false, true);
        var otherDraftWithStaleConfig = agent("stale", "other", DefinitionType.AGENT, AgentStatus.DRAFT, false, true);

        assertTrue(WorkflowAgentAccessPolicy.isOwnedEditable(ownedDraft, USER_ID));
        assertTrue(WorkflowAgentAccessPolicy.canReference(ownedDraft, USER_ID));
        assertFalse(WorkflowAgentAccessPolicy.isOwnedEditable(ownedSystemDraft, USER_ID));
        assertFalse(WorkflowAgentAccessPolicy.canReference(ownedSystemDraft, USER_ID));
        assertTrue(WorkflowAgentAccessPolicy.canReference(otherPublished, USER_ID));
        assertFalse(WorkflowAgentAccessPolicy.canReference(otherDraftWithStaleConfig, USER_ID));
    }

    @Test
    void responseContainsOnlyPickerFields() {
        var entity = agent("a1", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, false, false);
        entity.name = "Safe Name";
        entity.systemPrompt = "must not leave the server";
        entity.publishedConfig = new AgentPublishedConfig();

        String json = JSON.toJSON(WorkflowAgentOptionService.toView(entity, USER_ID));

        assertTrue(json.contains("Safe Name"));
        assertFalse(json.contains("system_prompt"));
        assertFalse(json.contains("must not leave the server"));
        assertFalse(json.contains("published_config"));
        assertEquals(6, BsonDocument.parse(json).size());
    }

    @Test
    void listMapsMinimalItemsAndPagingMetadata() {
        var service = service();
        var item = agent("a1", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, false, false);
        when(service.agentDefinitionCollection.find(any(Query.class))).thenReturn(List.of(item));
        when(service.agentDefinitionCollection.count(any(Bson.class))).thenReturn(7L);

        var response = service.list(USER_ID, request("mine", "AGENT"));

        assertEquals(List.of("a1"), response.items.stream().map(view -> view.id).toList());
        assertEquals(7L, response.total);
        assertEquals(1, response.page);
        assertEquals(20, response.limit);
    }

    @Test
    void mineListDropsRowsOutsideOwnedEditableAndRequestedType() {
        var service = service();
        when(service.agentDefinitionCollection.find(any(Query.class))).thenReturn(List.of(
            agent("owned-agent", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, false, false),
            agent("other-agent", "other", DefinitionType.AGENT, AgentStatus.DRAFT, false, false),
            agent("owned-system", USER_ID, DefinitionType.AGENT, AgentStatus.PUBLISHED, true, true),
            agent("owned-llm", USER_ID, DefinitionType.LLM_CALL, AgentStatus.DRAFT, false, false)));

        var response = service.list(USER_ID, request("mine", "AGENT"));

        assertEquals(List.of("owned-agent"), response.items.stream().map(view -> view.id).toList());
    }

    @Test
    void sharedListDropsRowsOutsidePublishedSharedPlacementAndRequestedType() {
        var service = service();
        when(service.agentDefinitionCollection.find(any(Query.class))).thenReturn(List.of(
            agent("system-agent", USER_ID, DefinitionType.AGENT, AgentStatus.PUBLISHED, true, true),
            agent("other-agent", "other", DefinitionType.AGENT, AgentStatus.PUBLISHED, false, true),
            agent("owned-agent", USER_ID, DefinitionType.AGENT, AgentStatus.PUBLISHED, false, true),
            agent("other-no-config", "other", DefinitionType.AGENT, AgentStatus.PUBLISHED, false, false),
            agent("other-draft", "other", DefinitionType.AGENT, AgentStatus.DRAFT, false, true),
            agent("other-llm", "other", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED, false, true)));

        var response = service.list(USER_ID, request("shared", "AGENT"));

        assertEquals(List.of("system-agent", "other-agent"),
            response.items.stream().map(view -> view.id).toList());
    }

    @Test
    void rejectsPageOffsetBeyondMongoIntegerRange() {
        var request = request("mine", "AGENT");
        request.page = Integer.MAX_VALUE;
        request.limit = 50;

        var exception = assertThrows(BadRequestException.class, () -> service().list(USER_ID, request));

        assertEquals("BAD_REQUEST", exception.errorCode());
        assertTrue(exception.getMessage().contains("page offset"));
    }

    @Test
    void viewOwnershipPrioritizesSystemThenMineThenShared() {
        var system = agent("system", USER_ID, DefinitionType.AGENT, AgentStatus.PUBLISHED, true, true);
        var mine = agent("mine", USER_ID, DefinitionType.AGENT, AgentStatus.DRAFT, false, false);
        var shared = agent("shared", "other", DefinitionType.LLM_CALL, AgentStatus.PUBLISHED, false, true);

        assertEquals("SYSTEM", WorkflowAgentOptionService.toView(system, USER_ID).ownership);
        assertEquals("MINE", WorkflowAgentOptionService.toView(mine, USER_ID).ownership);
        assertEquals("SHARED", WorkflowAgentOptionService.toView(shared, USER_ID).ownership);
        assertEquals("LLM_CALL", WorkflowAgentOptionService.toView(shared, USER_ID).type);
        assertEquals("PUBLISHED", WorkflowAgentOptionService.toView(shared, USER_ID).status);
    }

    private void assertBadRequest(ListWorkflowAgentOptionsRequest request, String expectedMessage) {
        var exception = assertThrows(BadRequestException.class, () -> service().list(USER_ID, request));
        assertEquals("BAD_REQUEST", exception.errorCode());
        assertTrue(exception.getMessage().contains(expectedMessage));
    }

    private void assertPaging(int requestedPage, int expectedLimit, Integer requestedLimit, int expectedSkipUnit) {
        var service = service();
        var request = request("mine", "AGENT");
        request.page = requestedPage;
        request.limit = requestedLimit;

        var response = service.list(USER_ID, request);

        var query = ArgumentCaptor.forClass(Query.class);
        verify(service.agentDefinitionCollection).find(query.capture());
        assertEquals(expectedLimit, query.getValue().limit);
        assertEquals((requestedPage - 1) * expectedSkipUnit, query.getValue().skip);
        assertEquals(expectedLimit, response.limit);
    }

    @SuppressWarnings("unchecked")
    private WorkflowAgentOptionService service() {
        var service = new WorkflowAgentOptionService();
        service.agentDefinitionCollection = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        when(service.agentDefinitionCollection.find(any(Query.class))).thenReturn(List.of());
        when(service.agentDefinitionCollection.count(any(Bson.class))).thenReturn(0L);
        return service;
    }

    private ListWorkflowAgentOptionsRequest request(String scope, String type) {
        var request = new ListWorkflowAgentOptionsRequest();
        request.scope = scope;
        request.type = type;
        return request;
    }

    private AgentDefinition agent(String id, String userId, DefinitionType type, AgentStatus status,
                                  boolean systemDefault, boolean publishedConfig) {
        var agent = new AgentDefinition();
        agent.id = id;
        agent.userId = userId;
        agent.name = id;
        agent.nameKey = id;
        agent.type = type;
        agent.status = status;
        agent.systemDefault = systemDefault;
        agent.publishedConfig = publishedConfig ? new AgentPublishedConfig() : null;
        agent.updatedAt = ZonedDateTime.parse("2026-08-05T10:00:00+08:00");
        return agent;
    }

    @SuppressWarnings("PMD.LooseCoupling")
    private BsonDocument bson(Bson value) {
        return value.toBsonDocument(BsonDocument.class, MongoClientSettings.getDefaultCodecRegistry());
    }

    @SuppressWarnings("PMD.LooseCoupling")
    private BsonDocument field(BsonDocument document, String name) {
        if (document.containsKey(name)) return document;
        for (BsonValue value : document.values()) {
            if (value.isDocument()) {
                var match = nullableField(value.asDocument(), name);
                if (match != null) return match;
            } else if (value.isArray()) {
                for (var item : value.asArray()) {
                    if (!item.isDocument()) continue;
                    var match = nullableField(item.asDocument(), name);
                    if (match != null) return match;
                }
            }
        }
        throw new AssertionError("missing BSON field: " + name + " in " + document.toJson());
    }

    @SuppressWarnings("PMD.LooseCoupling")
    private BsonDocument nullableField(BsonDocument document, String name) {
        try {
            return field(document, name);
        } catch (AssertionError ignored) {
            return null;
        }
    }
}
