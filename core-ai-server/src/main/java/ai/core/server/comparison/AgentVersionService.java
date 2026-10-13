package ai.core.server.comparison;

import ai.core.api.server.comparison.AgentVersionView;
import ai.core.api.server.comparison.CreateAgentVersionRequest;
import ai.core.api.server.comparison.ListAgentVersionsResponse;
import ai.core.api.server.comparison.UpdateAgentVersionRequest;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.agent.AgentExecutableConfigFactory;
import ai.core.server.comparison.domain.AgentComparison;
import ai.core.server.comparison.domain.AgentVersion;
import ai.core.server.comparison.domain.ComparisonStatus;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.util.Strings;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Saved agent versions: full config snapshots that can be edited freely, selected as the sides of a
 * comparison experiment and adopted after winning. Access mirrors comparison management (agent
 * owner or admin).
 *
 * @author stephen
 */
public class AgentVersionService {
    static AgentVersionView toView(AgentVersion version) {
        var view = new AgentVersionView();
        view.id = version.id;
        view.agentId = version.agentId;
        view.agentName = version.agentName;
        view.label = version.label;
        view.model = version.config != null ? version.config.model : null;
        view.config = ComparisonViews.toConfigView(version.config);
        view.createdBy = version.createdBy;
        view.createdAt = version.createdAt;
        view.updatedAt = version.updatedAt;
        return view;
    }

    @Inject
    MongoCollection<AgentVersion> versionCollection;
    @Inject
    MongoCollection<AgentComparison> comparisonCollection;
    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;
    @Inject
    MongoCollection<User> userCollection;

    public AgentVersionView create(CreateAgentVersionRequest request, String userId) {
        if (request == null || Strings.isBlank(request.agentId)) throw new BadRequestException("agent_id required");
        var agent = agentDefinitionCollection.get(request.agentId)
            .orElseThrow(() -> new BadRequestException("agent is unavailable"));
        requireAgentOwnerOrAdmin(agent, userId);
        var config = Strings.isBlank(request.copyFromVersionId)
            ? liveConfigSnapshot(agent)
            : AgentExecutableConfigFactory.fromPublishedConfig(requireVersion(request.copyFromVersionId).config);
        var version = new AgentVersion();
        version.id = UUID.randomUUID().toString();
        version.agentId = agent.id;
        version.agentName = agent.name;
        version.label = Strings.isBlank(request.label) ? defaultLabel(agent.id) : request.label.trim();
        version.config = config;
        version.createdBy = userId;
        version.createdAt = ZonedDateTime.now();
        version.updatedAt = version.createdAt;
        versionCollection.insert(version);
        return toView(version);
    }

    public ListAgentVersionsResponse list(String agentId, String userId) {
        if (Strings.isBlank(agentId)) throw new BadRequestException("agent_id required");
        requireAgentOwnerOrAdmin(agentId, userId);
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("created_at");
        query.limit = 100;
        var response = new ListAgentVersionsResponse();
        response.versions = versionCollection.find(query).stream().map(AgentVersionService::toView).toList();
        return response;
    }

    public AgentVersionView get(String id, String userId) {
        var version = requireVersion(id);
        requireAgentOwnerOrAdmin(version.agentId, userId);
        return toView(version);
    }

    public AgentVersionView update(String id, UpdateAgentVersionRequest request, String userId) {
        var version = requireVersion(id);
        requireAgentOwnerOrAdmin(version.agentId, userId);
        if (request != null) {
            applyLabel(version, request.label);
            if (request.config != null) ComparisonViews.applyConfigPatch(version.config, request.config);
        }
        version.updatedAt = ZonedDateTime.now();
        versionCollection.replace(version);
        return toView(version);
    }

    private void applyLabel(AgentVersion version, String label) {
        if (label == null) return;
        if (label.isBlank()) throw new BadRequestException("label must not be blank");
        version.label = label.trim();
    }

    public void delete(String id, String userId) {
        var version = requireVersion(id);
        requireAgentOwnerOrAdmin(version.agentId, userId);
        // a version feeding a live experiment must survive until that experiment ends (its sides are
        // re-resolved at start), so only completed experiments let go of their references
        var query = new Query();
        query.filter = Filters.eq("agent_id", version.agentId);
        for (var comparison : comparisonCollection.find(query)) {
            if (comparison.status == ComparisonStatus.ENDED) continue;
            if (id.equals(comparison.versionAId) || id.equals(comparison.versionBId)) {
                throw new BadRequestException("version is used by an unfinished experiment");
            }
        }
        versionCollection.delete(id);
    }

    private AgentVersion requireVersion(String id) {
        return versionCollection.get(id).orElseThrow(() -> new NotFoundException("version not found, id=" + id));
    }

    private AgentPublishedConfig liveConfigSnapshot(AgentDefinition agent) {
        if (!AgentDependencyAccessPolicy.hasUsablePublishedConfig(agent)) {
            throw new BadRequestException("agent must be published before creating a version");
        }
        return AgentExecutableConfigFactory.fromPublishedConfig(agent.publishedConfig);
    }

    private String defaultLabel(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        return "v" + (versionCollection.count(query.filter) + 1);
    }

    private void requireAgentOwnerOrAdmin(String agentId, String userId) {
        var agent = agentDefinitionCollection.get(agentId).orElse(null);
        if (agent == null) {
            if (ComparisonAccess.isAdmin(userCollection, userId)) return;
            throw new ForbiddenException("agent is unavailable");
        }
        requireAgentOwnerOrAdmin(agent, userId);
    }

    private void requireAgentOwnerOrAdmin(AgentDefinition agent, String userId) {
        if (userId != null && (userId.equals(agent.userId) || ComparisonAccess.isAdmin(userCollection, userId))) return;
        throw new ForbiddenException("only the agent owner or admin can manage versions");
    }
}
