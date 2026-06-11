package ai.core.server.workflow;

import ai.core.api.server.workflow.PendingInputFieldView;
import ai.core.api.server.workflow.PendingInputView;
import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.json.JSON;
import core.framework.web.exception.BadRequestException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The human-input contract for API callers. A HUMAN_INPUT node is a remote form: this class exposes that form
 * as data ({@link #describe}) so a PAUSED run is self-describing, and enforces it on resume ({@link #validate})
 * so a bad answer fails the HTTP request instead of a downstream node.
 *
 * @author Xander
 */
public final class HumanInputProtocol {
    public static String mode(WorkflowNode node) {
        return node.config().get("mode") instanceof String value && !value.isBlank() ? value : "approval";
    }

    /** Build the pending-input view: mode + form schema from the pinned graph, rendered prompt from the ask snapshot. */
    public static PendingInputView describe(WorkflowNode node, WorkflowNodeRun waiting) {
        var view = new PendingInputView();
        view.nodeId = node.id();
        view.mode = mode(node);
        view.prompt = promptOf(waiting.inputJson);
        if ("input".equals(view.mode)) {
            view.fields = fieldViews(node);
        }
        return view;
    }

    /** Reject a resume that does not match the node's mode, or an input-mode answer violating the form schema. */
    public static void validate(WorkflowNode node, Boolean approve, String input) {
        if ("approval".equals(mode(node))) {
            if (input != null) throw new BadRequestException("node is in approval mode, send 'approve' instead of 'input': " + node.id());
            if (approve == null) throw new BadRequestException("'approve' is required for approval mode: " + node.id());
            return;
        }
        if (approve != null) throw new BadRequestException("node is in input mode, send 'input' instead of 'approve': " + node.id());
        validateValues(node, parseValues(input));
    }

    private static void validateValues(WorkflowNode node, Map<String, Object> values) {
        for (Map<String, Object> field : fields(node)) {
            if (!(field.get("name") instanceof String name) || name.isBlank()) continue;
            Object value = values.get(name);
            if (Boolean.TRUE.equals(field.get("required")) && isBlank(value)) {
                throw new BadRequestException("required field missing: " + name);
            }
            if (value != null && field.get("type") instanceof String type) {
                checkType(name, type, value);
            }
        }
    }

    private static void checkType(String name, String type, Object value) {
        boolean ok = switch (type) {
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "text", "paragraph", "select" -> value instanceof String;
            default -> true;
        };
        if (!ok) throw new BadRequestException("field '" + name + "' must be of type " + type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseValues(String input) {
        if (input == null || input.isBlank()) return Map.of();
        try {
            return JSON.fromJSON(Map.class, input);
        } catch (RuntimeException e) {
            throw new BadRequestException("input must be a JSON object string");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fields(WorkflowNode node) {
        if (!(node.config().get("fields") instanceof List<?> raw)) return List.of();
        var fields = new ArrayList<Map<String, Object>>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> map) fields.add((Map<String, Object>) map);
        }
        return fields;
    }

    private static List<PendingInputFieldView> fieldViews(WorkflowNode node) {
        var views = new ArrayList<PendingInputFieldView>();
        for (Map<String, Object> field : fields(node)) {
            var view = new PendingInputFieldView();
            view.name = field.get("name") instanceof String name ? name : null;
            view.type = field.get("type") instanceof String type ? type : null;
            view.label = field.get("label") instanceof String label ? label : null;
            view.required = field.get("required") instanceof Boolean required ? required : Boolean.FALSE;
            views.add(view);
        }
        return views;
    }

    // the ask snapshot is {"mode":..,"prompt":..} written by HumanInputExecutor at pause time
    private static String promptOf(String ask) {
        if (ask == null || ask.isBlank()) return "";
        try {
            Map<?, ?> parsed = JSON.fromJSON(Map.class, ask);
            return parsed.get("prompt") instanceof String prompt ? prompt : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isBlank(Object value) {
        return value == null || value instanceof String text && text.isBlank();
    }

    private HumanInputProtocol() {
    }
}
