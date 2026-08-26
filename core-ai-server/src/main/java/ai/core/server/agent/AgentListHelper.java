package ai.core.server.agent;

import ai.core.api.server.agent.AgentDefinitionView;
import ai.core.server.domain.AgentDefinition;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * List query helpers for {@link AgentDefinitionService} to keep the service file under the length limit.
 *
 * @author stephen
 */
final class AgentListHelper {
    static final String DEFAULT_ASSISTANT_AGENT_ID = "default-assistant";

    static void prioritizeDefaultAssistant(List<AgentDefinition> agents) {
        for (int i = 0; i < agents.size(); i++) {
            if (DEFAULT_ASSISTANT_AGENT_ID.equals(agents.get(i).id)) {
                agents.add(0, agents.remove(i));
                return;
            }
        }
    }

    static AgentDefinitionView toSummaryView(AgentDefinition entity) {
        var view = new AgentDefinitionView();
        view.id = entity.id;
        view.name = entity.name;
        return view;
    }

    private static boolean contains(String text, String lowerKeyword) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(lowerKeyword);
    }

    private final MongoCollection<AgentDefinition> agentDefinitionCollection;

    AgentListHelper(MongoCollection<AgentDefinition> agentDefinitionCollection) {
        this.agentDefinitionCollection = agentDefinitionCollection;
    }

    AgentDefinition findDefaultAssistant(Bson filter) {
        return agentDefinitionCollection.findOne(AgentQueryHelper.combineFilters(filter, Filters.eq("_id", DEFAULT_ASSISTANT_AGENT_ID))).orElse(null);
    }

    List<AgentDefinition> listWithDefaultAssistantFirst(Bson filter, String sortField, Integer skip, Integer limit, AgentDefinition defaultAssistant, Bson projection) {
        if (limit == null) {
            var agents = findAgents(filter, sortField, null, null, projection);
            prioritizeDefaultAssistant(agents);
            return agents;
        }
        if (skip != null && skip > 0) {
            return findAgents(excludeDefaultAssistant(filter), sortField, skip - 1, limit, projection);
        }
        var agents = limit > 1 ? findAgents(excludeDefaultAssistant(filter), sortField, 0, limit - 1, projection) : new ArrayList<AgentDefinition>();
        agents.add(0, defaultAssistant);
        return agents;
    }

    List<AgentDefinition> findAgents(Bson filter, String sortField, Integer skip, Integer limit, Bson projection) {
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending(sortField);
        if (skip != null) query.skip = skip;
        if (limit != null) query.limit = limit;
        if (projection != null) query.projection = projection;
        return agentDefinitionCollection.find(query);
    }

    // Mongo cannot use an index for an unanchored case-insensitive regex, so keyword search is rejected by
    // notablescan. Fetch the index-backed access matches and filter in Java instead, like TraceService does.
    List<AgentDefinition> searchAgents(Bson filter, String keyword, String sortField) {
        var lower = keyword.toLowerCase(Locale.ROOT);
        var matches = new ArrayList<AgentDefinition>();
        for (var agent : findAgents(filter, sortField, null, null, null)) {
            if (contains(agent.name, lower) || contains(agent.description, lower)) matches.add(agent);
        }
        prioritizeDefaultAssistant(matches);
        return matches;
    }

    private Bson excludeDefaultAssistant(Bson filter) {
        return AgentQueryHelper.combineFilters(filter, Filters.ne("_id", DEFAULT_ASSISTANT_AGENT_ID));
    }
}
