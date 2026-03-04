package ai.core.server.run;

import ai.core.api.server.run.AgentRunDetailView;
import ai.core.api.server.run.AgentRunView;
import ai.core.api.server.AgentRunWebService;
import ai.core.api.server.run.ListRunsRequest;
import ai.core.api.server.run.ListRunsResponse;
import ai.core.api.server.run.TriggerRunRequest;
import ai.core.api.server.run.TriggerRunResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.types.ObjectId;

import java.util.Map;

/**
 * @author stephen
 */
public class AgentRunWebServiceImpl implements AgentRunWebService {
    @Inject
    AgentRunner agentRunner;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    @Override
    public TriggerRunResponse trigger(String agentId, TriggerRunRequest request) {
        var definition = agentDefinitionCollection.get(new ObjectId(agentId))
            .orElseThrow(() -> new RuntimeException("agent not found, id=" + agentId));

        var input = request.input != null ? request.input : definition.inputTemplate;
        var runId = agentRunner.run(definition, input, TriggerType.MANUAL);

        var response = new TriggerRunResponse();
        response.runId = runId;
        response.status = RunStatus.RUNNING.name();
        return response;
    }

    @Override
    public ListRunsResponse listByAgent(String agentId, ListRunsRequest request) {
        var query = new Query();
        if (request.status != null && !request.status.isBlank()) {
            query.filter = Filters.and(
                Filters.eq("agent_id", new ObjectId(agentId)),
                Filters.eq("status", RunStatus.valueOf(request.status))
            );
        } else {
            query.filter = Filters.eq("agent_id", new ObjectId(agentId));
        }
        query.sort = Sorts.descending("started_at");
        query.limit = request.limit != null ? request.limit : 20;

        var runs = agentRunCollection.find(query);
        var response = new ListRunsResponse();
        response.runs = runs.stream().map(this::toView).toList();
        response.total = agentRunCollection.count(query.filter);
        return response;
    }

    @Override
    public AgentRunDetailView get(String id) {
        var entity = agentRunCollection.get(new ObjectId(id))
            .orElseThrow(() -> new RuntimeException("run not found, id=" + id));
        return toDetailView(entity);
    }

    @Override
    public void cancel(String id) {
        agentRunner.cancel(id);
    }

    private AgentRunView toView(AgentRun entity) {
        var view = new AgentRunView();
        view.id = entity.id.toHexString();
        view.agentId = entity.agentId.toHexString();
        view.triggeredBy = entity.triggeredBy.name();
        view.status = entity.status.name();
        view.input = entity.input;
        view.output = entity.output;
        view.error = entity.error;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
        if (entity.tokenUsage != null) {
            view.tokenUsage = Map.of(
                "input", entity.tokenUsage.input != null ? entity.tokenUsage.input : 0L,
                "output", entity.tokenUsage.output != null ? entity.tokenUsage.output : 0L
            );
        }
        return view;
    }

    private AgentRunDetailView toDetailView(AgentRun entity) {
        var view = new AgentRunDetailView();
        view.id = entity.id.toHexString();
        view.agentId = entity.agentId.toHexString();
        view.triggeredBy = entity.triggeredBy.name();
        view.status = entity.status.name();
        view.input = entity.input;
        view.output = entity.output;
        view.error = entity.error;
        view.startedAt = entity.startedAt;
        view.completedAt = entity.completedAt;
        if (entity.tokenUsage != null) {
            view.tokenUsage = Map.of(
                "input", entity.tokenUsage.input != null ? entity.tokenUsage.input : 0L,
                "output", entity.tokenUsage.output != null ? entity.tokenUsage.output : 0L
            );
        }
        if (entity.transcript != null) {
            view.transcript = entity.transcript.stream().map(t -> {
                var entry = new AgentRunDetailView.TranscriptEntryView();
                entry.timestamp = t.timestamp != null ? t.timestamp.toString() : null;
                entry.role = t.role;
                entry.content = t.content;
                entry.name = t.name;
                entry.args = t.args;
                entry.status = t.status;
                entry.result = t.result;
                return entry;
            }).toList();
        }
        return view;
    }
}
