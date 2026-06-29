package ai.core.server.workflow;

import ai.core.api.server.workflow.ExportWorkflowResponse;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Export a workflow draft to a portable envelope and import an envelope back into a new draft. Import is
 * non-destructive (always a new draft) and reports unresolved agent/tool references without blocking.
 *
 * @author Xander
 */
public class WorkflowPortService {
    public static final String EXPORT_FORMAT = "core-ai-workflow-export/v1";

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    MongoCollection<AgentDefinition> agentDefinitionCollection;

    @Inject
    MongoCollection<WorkflowDefinition> definitionCollection;

    @Inject
    MongoCollection<ToolRegistryEntry> toolRegistryCollection;

    public ExportWorkflowResponse export(String id, String userId) {
        WorkflowDefinition definition = definitionService.get(id, userId);   // ownership-checked
        var response = new ExportWorkflowResponse();
        response.format = EXPORT_FORMAT;
        response.exportedAt = ZonedDateTime.now();
        response.name = definition.name;
        response.mode = definition.mode != null ? definition.mode.name() : null;
        response.description = definition.description;
        response.graph = definition.draftGraph;
        return response;
    }

    public record UnresolvedReference(String nodeId, String nodeType, String refType, String refId, String message) {
    }

    public record WorkflowImportResult(WorkflowDefinition definition, List<UnresolvedReference> unresolved) {
    }

    public WorkflowImportResult importWorkflow(String content, String name, String userId) {
        ExportWorkflowResponse envelope = parseEnvelope(content);
        String workflowName = name != null && !name.isBlank() ? name : envelope.name;
        WorkflowDefinition definition = definitionService.create(workflowName, envelope.mode, envelope.graph, userId);
        if (envelope.description != null && !envelope.description.isBlank()) {
            definition.description = envelope.description;
            definition.updatedAt = ZonedDateTime.now();
            definitionCollection.replace(definition);
        }
        List<UnresolvedReference> unresolved = scanReferences(envelope.graph, userId);
        return new WorkflowImportResult(definition, unresolved);
    }

    // Parse + validate the envelope without touching Mongo, so it is unit-testable. A bad envelope is a client
    // error (400) and no draft is created.
    public static ExportWorkflowResponse parseEnvelope(String content) {
        ExportWorkflowResponse envelope;
        try {
            envelope = JSON.fromJSON(ExportWorkflowResponse.class, content);
        } catch (RuntimeException e) {
            throw new BadRequestException("invalid workflow export file: not valid JSON", "INVALID_IMPORT", e);
        }
        if (envelope == null || !EXPORT_FORMAT.equals(envelope.format)) {
            throw new BadRequestException("unrecognized workflow export format: " + (envelope == null ? null : envelope.format));
        }
        if (envelope.graph == null || envelope.graph.isBlank()) {
            throw new BadRequestException("workflow export is missing a graph");
        }
        if (envelope.mode != null && !"WORKFLOW".equals(envelope.mode) && !"CHATFLOW".equals(envelope.mode)) {
            throw new BadRequestException("workflow export has an invalid mode: " + envelope.mode);
        }
        return envelope;
    }

    // Walk the executable graph and report AGENT/LLM and MCP_TOOL references that do not resolve for the importer.
    // API_TOOL apps are deployment-global service APIs (not per-user resources), so they are not flagged here.
    private List<UnresolvedReference> scanReferences(String graphJson, String userId) {
        List<UnresolvedReference> unresolved = new ArrayList<>();
        try {
            WorkflowGraph graph = WorkflowGraphParser.parse(graphJson);
            for (WorkflowNode node : graph.nodes()) {
                String type = node.type();
                if (type == null) {
                    continue;   // typeless node: no reference to resolve; structural issues surface in the editor's Validate
                }
                switch (type) {
                    case "AGENT", "LLM" -> checkAgent(node, userId, unresolved);
                    case "MCP_TOOL" -> checkMcpTool(node, unresolved);
                    // todo: API_TOOL apps are global service APIs; revisit if per-deployment app gating is added.
                    default -> { }
                }
            }
        } catch (RuntimeException e) {
            return unresolved;   // a malformed graph is surfaced later by the editor's Validate; never break the import
        }
        return unresolved;
    }

    // Both AGENT and LLM nodes reference an agent via agent_id, so the reference category (refType) is always
    // "AGENT" per the AGENT|MCP_TOOL vocabulary; the originating node type is still carried in nodeType (node.type()).
    private void checkAgent(WorkflowNode node, String userId, List<UnresolvedReference> unresolved) {
        Object agentId = node.config().get("agent_id");
        if (agentId == null) {
            unresolved.add(new UnresolvedReference(node.id(), node.type(), "AGENT", null, "node is missing agent_id"));
            return;
        }
        AgentDefinition agent = agentDefinitionCollection.get(String.valueOf(agentId)).orElse(null);
        if (agent == null) {
            unresolved.add(new UnresolvedReference(node.id(), node.type(), "AGENT", String.valueOf(agentId), "references an unknown agent"));
        } else if (!isAccessible(agent, userId)) {
            unresolved.add(new UnresolvedReference(node.id(), node.type(), "AGENT", String.valueOf(agentId), "references an agent you cannot access"));
        } else if (agent.publishedConfig == null) {
            unresolved.add(new UnresolvedReference(node.id(), node.type(), "AGENT", String.valueOf(agentId), "references an unpublished agent"));
        }
    }

    private void checkMcpTool(WorkflowNode node, List<UnresolvedReference> unresolved) {
        Object serverId = node.config().get("server_id");
        if (serverId == null) {
            unresolved.add(new UnresolvedReference(node.id(), node.type(), "MCP_TOOL", null, "node is missing server_id"));
            return;
        }
        if (toolRegistryCollection.get(String.valueOf(serverId)).isEmpty()) {
            unresolved.add(new UnresolvedReference(node.id(), node.type(), "MCP_TOOL", String.valueOf(serverId), "references an unknown MCP server"));
        }
    }

    // Same own-OR-system_default rule WorkflowPublishService uses.
    private static boolean isAccessible(AgentDefinition agent, String userId) {
        return Boolean.TRUE.equals(agent.systemDefault) || (agent.userId != null && agent.userId.equals(userId));
    }
}
