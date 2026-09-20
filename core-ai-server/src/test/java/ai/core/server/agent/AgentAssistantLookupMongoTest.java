package ai.core.server.agent;

import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.workflow.WorkflowTestModule;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.mongo.MongoCollection;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat selector hides the shared assistant template and shows the caller's own fork instead, so the fork lookup
 * runs against the visibility filter alone: pairing the template exclusion with the template id makes the query
 * unsatisfiable and silently disables both the fork and the pinning.
 * <p>
 * A personal assistant fork is private to its owner, so a listing scope never offers somebody else's copy while the
 * owner keeps seeing its own.
 *
 * @author Xander
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class AgentAssistantLookupMongoTest {
    private static final String CALLER = "assistant-listing-caller";
    private static final String OTHER_USER = "assistant-listing-other";
    private static final String OWN_FORK = "assistant:" + CALLER;
    private static final String OTHER_FORK = "assistant:" + OTHER_USER;
    private static final String SHARED_AGENT = "assistant-listing-shared";
    private static final List<String> LISTING_IDS = List.of(OWN_FORK, OTHER_FORK, SHARED_AGENT);

    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    Mongo mongo;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    // wftest is not migrated, mirror the production agents indexes the listing filters run on, otherwise notablescan
    // reports the missing index instead of the listing being wrong
    @BeforeEach
    void ensureListingIndexes() {
        mongo.createIndex("agents", Indexes.ascending("user_id"));
        mongo.createIndex("agents", Indexes.ascending("system_default"));
    }

    @AfterEach
    void removeFixtures() {
        agentDefinitionCollection.delete(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID);
        for (var id : LISTING_IDS) {
            agentDefinitionCollection.delete(id);
        }
    }

    @Test
    void sharedAssistantTemplateIsHiddenFromTheListButVisibleToTheForkLookup() {
        insertTemplate();
        var request = new ListAgentsRequest();
        request.myAgents = "true";

        assertTrue(findTemplate(AgentQueryHelper.buildVisibilityFilter("user-1", request)).isPresent(),
            "the fork lookup must see the shared template");
        assertFalse(findTemplate(AgentQueryHelper.buildAccessFilter("user-1", request)).isPresent(),
            "the list must not offer the shared template as a selectable agent");
    }

    @Test
    void sharedAssistantTemplateIsInvisibleToTheSharedAgentListing() {
        insertTemplate();
        var request = new ListAgentsRequest();
        request.myAgents = "false";

        assertFalse(findTemplate(AgentQueryHelper.buildVisibilityFilter("user-1", request)).isPresent(),
            "browsing other users' agents must neither fork nor pin the assistant");
        assertFalse(findTemplate(AgentQueryHelper.buildAccessFilter("user-1", request)).isPresent());
    }

    @Test
    void personalAssistantForkIsListedForItsOwnerOnly() {
        insertTemplate();
        insertAgent(OWN_FORK, CALLER, PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID);
        insertAgent(OTHER_FORK, OTHER_USER, PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID);
        insertAgent(SHARED_AGENT, OTHER_USER, null);

        assertEquals(Set.of(SHARED_AGENT), listedIds(request("false")),
            "browsing other users' agents must not offer their personal assistant");
        assertEquals(Set.of(OWN_FORK), listedIds(request("true")),
            "the owner keeps its own personal assistant");
        assertEquals(Set.of(OWN_FORK, SHARED_AGENT), listedIds(new ListAgentsRequest()),
            "the picker's default scope is my agents plus the shared ones");
        assertTrue(agentDefinitionCollection.count(AgentQueryHelper.buildAccessFilter(CALLER, new ListAgentsRequest())) > 0,
            "the default listing scope has to stay index-backed under notablescan");
    }

    private Set<String> listedIds(ListAgentsRequest request) {
        var filter = AgentQueryHelper.combineFilters(AgentQueryHelper.buildAccessFilter(CALLER, request),
            Filters.in("_id", LISTING_IDS));
        return agentDefinitionCollection.find(filter).stream().map(agent -> agent.id).collect(Collectors.toSet());
    }

    private ListAgentsRequest request(String myAgents) {
        var request = new ListAgentsRequest();
        request.myAgents = myAgents;
        return request;
    }

    private Optional<AgentDefinition> findTemplate(Bson visibilityFilter) {
        var filter = AgentQueryHelper.combineFilters(visibilityFilter,
            Filters.eq("_id", PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID));
        return agentDefinitionCollection.findOne(filter);
    }

    private void insertTemplate() {
        var template = new AgentDefinition();
        template.id = PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID;
        template.userId = "system";
        template.name = "Default Assistant";
        template.systemDefault = Boolean.TRUE;
        template.type = DefinitionType.AGENT;
        template.status = AgentStatus.PUBLISHED;
        template.createdAt = ZonedDateTime.now();
        template.updatedAt = ZonedDateTime.now();
        agentDefinitionCollection.insert(template);
    }

    private void insertAgent(String id, String userId, String forkedFrom) {
        var agent = new AgentDefinition();
        agent.id = id;
        agent.userId = userId;
        agent.name = id;
        agent.type = DefinitionType.AGENT;
        agent.status = AgentStatus.PUBLISHED;
        agent.forkedFrom = forkedFrom;
        agent.createdAt = ZonedDateTime.now();
        agent.updatedAt = ZonedDateTime.now();
        agentDefinitionCollection.insert(agent);
    }
}
