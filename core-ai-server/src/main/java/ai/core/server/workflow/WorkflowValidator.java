package ai.core.server.workflow;

import ai.core.server.workflow.engine.DominatorValidator;
import ai.core.server.workflow.engine.GraphValidator;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The publish-time validation pipeline: structural rules ({@link GraphValidator}), node-type rules (entry is
 * START, sinks are END, every type is known and has an executable meaning), and cross-branch reference rules
 * ({@link DominatorValidator}). Pure — operates on the parsed graph; returns all errors at once.
 *
 * @author Xander
 */
public final class WorkflowValidator {
    private WorkflowValidator() {
    }

    public static List<String> validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>(GraphValidator.validate(graph));
        errors.addAll(typeErrors(graph));
        errors.addAll(DominatorValidator.validateReferences(graph));
        return errors;
    }

    private static List<String> typeErrors(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        for (WorkflowNode node : graph.nodes()) {
            NodeType type;
            try {
                type = NodeType.of(node.type());
            } catch (IllegalStateException e) {
                errors.add("node " + node.id() + " has unknown type: " + node.type());
                continue;
            }
            if (graph.inEdges(node.id()).isEmpty() && type != NodeType.START) {
                errors.add("entry node " + node.id() + " must be START, was " + type);
            }
            if (graph.outEdges(node.id()).isEmpty() && type != NodeType.END && type != NodeType.ANSWER) {
                errors.add("sink node " + node.id() + " must be END or ANSWER, was " + type);
            }
            configErrors(node, type, errors);
        }
        return errors;
    }

    // Per-type required-config checks: a tool node with no target selected cannot run, so catch it at publish time.
    private static void configErrors(WorkflowNode node, NodeType type, List<String> errors) {
        switch (type) {
            case MCP_TOOL -> {
                requireConfig(node, "server_id", errors);
                requireConfig(node, "tool_name", errors);
            }
            case API_TOOL -> {
                requireConfig(node, "app_name", errors);
                requireConfig(node, "tool_name", errors);
            }
            default -> { /* other types carry no publish-time required config here */ }
        }
    }

    private static void requireConfig(WorkflowNode node, String key, List<String> errors) {
        Object value = node.config().get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            errors.add("node " + node.id() + " (" + node.type() + ") is missing required config: " + key);
        }
    }
}
