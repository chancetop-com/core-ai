package ai.core.server.workflow;

import ai.core.server.workflow.engine.DominatorValidator;
import ai.core.server.workflow.engine.GraphValidator;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The publish-time validation pipeline: structural rules ({@link GraphValidator}), node-type rules (entry is
 * START, sinks are END, every type is known and has an executable meaning), and cross-branch reference rules
 * ({@link DominatorValidator}). Pure — operates on the parsed graph; returns all errors at once.
 *
 * @author Xander
 */
public final class WorkflowValidator {
    public static List<String> validate(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>(GraphValidator.validate(graph));
        errors.addAll(typeErrors(graph));
        errors.addAll(terminalErrors(graph));
        // END and AGGREGATOR are the output-composer / join nodes — exempt them from the dominance requirement so
        // they can read conditional branch outputs (their whole purpose); a skipped branch renders empty at
        // runtime (same OutputComposer). Mid-graph consumers (AGENT/CODE/HTTP/IF) stay strict — empty there is a bug.
        errors.addAll(DominatorValidator.validateReferences(graph, joinNodeIds(graph)));
        return errors;
    }

    private static Set<String> joinNodeIds(WorkflowGraph graph) {
        Set<String> ids = new LinkedHashSet<>();
        for (WorkflowNode node : graph.nodes()) {
            if (NodeType.AGGREGATOR.name().equals(node.type()) || NodeType.END.name().equals(node.type())) {
                ids.add(node.id());
            }
        }
        return ids;
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
            if (graph.outEdges(node.id()).isEmpty() && type != NodeType.END) {
                errors.add("sink node " + node.id() + " must be END, was " + type);
            }
            configErrors(node, type, errors);
        }
        return errors;
    }

    // A workflow is a function input -> output: exactly one START (entry) and exactly one END (the single output).
    private static List<String> terminalErrors(WorkflowGraph graph) {
        List<String> errors = new ArrayList<>();
        long starts = graph.nodes().stream().filter(node -> "START".equals(node.type())).count();
        long ends = graph.nodes().stream().filter(node -> "END".equals(node.type())).count();
        if (starts != 1) {
            errors.add("workflow must have exactly one START node, found " + starts);
        }
        if (ends != 1) {
            errors.add("workflow must have exactly one END node, found " + ends);
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
            case WORKFLOW -> {
                requireConfig(node, "source_workflow_id", errors);
                requireConfig(node, "version_id", errors);
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

    private WorkflowValidator() {
    }
}
