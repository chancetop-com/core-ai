package ai.core.server.agent;

import ai.core.api.server.agent.ListAgentsRequest;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.workflow.WorkflowTestModule;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat selector hides the shared assistant template and shows the caller's own fork instead, so the fork lookup
 * runs against the visibility filter alone: pairing the template exclusion with the template id makes the query
 * unsatisfiable and silently disables both the fork and the pinning.
 *
 * @author Xander
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class AgentAssistantLookupMongoTest {
    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @AfterEach
    void removeTemplate() {
        agentDefinitionCollection.delete(PersonalAssistantService.DEFAULT_ASSISTANT_TEMPLATE_ID);
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
}
