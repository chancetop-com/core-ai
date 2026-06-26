package ai.core.server.workflow;

import ai.core.server.workflow.engine.WorkflowEdge;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the resolved input snapshot shown in workflow run traces. This is observability only: executors still
 * own execution semantics, and the planner never reads this value.
 *
 * @author Xander
 */
final class NodeInputSnapshot {
    private NodeInputSnapshot() {
    }

    static String capture(NodeContext ctx) {
        String type = ctx.node().type();
        return switch (type) {
            case "START" -> ctx.run().input != null ? ctx.run().input : "{}";
            case "AGENT", "LLM" -> json(Map.of("input", agentInput(ctx)));
            case "CODE" -> json(Map.of("inputs", codeInputs(ctx)));
            case "HTTP" -> json(httpInput(ctx));
            case "MCP_TOOL" -> json(toolInput(ctx, "server_id"));
            case "API_TOOL" -> json(toolInput(ctx, "app_name"));
            case "IF_ELSE" -> json(ifElseInput(ctx));
            case "TEMPLATE" -> json(Map.of("template", rendered(ctx, "template")));
            case "AGGREGATOR", "END" -> json(Map.of("predecessors", predecessors(ctx)));
            case "HUMAN_INPUT" -> json(Map.of(
                "mode", str(ctx.node().config().get("mode"), "approval"),
                "prompt", ctx.pool().render(str(ctx.node().config().get("prompt"), ""))));
            default -> json(Map.of("config", ctx.node().config()));
        };
    }

    private static String agentInput(NodeContext ctx) {
        Object template = ctx.node().config().get("input");
        String input = template instanceof String value ? ctx.pool().render(value) : ctx.run().input;
        return input != null ? input : "{}";
    }

    private static Map<String, Object> codeInputs(NodeContext ctx) {
        Object inputs = ctx.node().config().get("inputs");
        if (!(inputs instanceof Map<?, ?> map)) {
            return Map.of();
        }
        var resolved = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() instanceof String selector) {
                ctx.pool().resolve(selector)
                    .ifPresent(value -> resolved.put(String.valueOf(entry.getKey()), coerce(value)));
            }
        }
        return resolved;
    }

    private static Map<String, Object> httpInput(NodeContext ctx) {
        var config = ctx.node().config();
        var input = new LinkedHashMap<String, Object>();
        input.put("method", str(config.get("method"), "GET"));
        input.put("url", ctx.pool().render(str(config.get("url"), "")).trim());
        if (config.get("headers") instanceof Map<?, ?> headers) {
            var rendered = new LinkedHashMap<String, String>();
            for (Map.Entry<?, ?> entry : headers.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String value = sensitiveHeader(key) ? "[redacted]" : ctx.pool().render(String.valueOf(entry.getValue()));
                rendered.put(key, value);
            }
            input.put("headers", rendered);
        }
        if (config.get("body") instanceof String body && !body.isBlank()) {
            input.put("body", ctx.pool().renderJson(body));
        }
        return input;
    }

    private static Map<String, Object> toolInput(NodeContext ctx, String appKey) {
        var config = ctx.node().config();
        var input = new LinkedHashMap<String, Object>();
        input.put(appKey, str(config.get(appKey), ""));
        input.put("tool_name", str(config.get("tool_name"), ""));
        Object arguments = config.get("arguments");
        input.put("arguments",
            arguments instanceof String template && !template.isBlank() ? ctx.pool().renderJson(template) : "{}");
        return input;
    }

    private static Map<String, Object> ifElseInput(NodeContext ctx) {
        var config = ctx.node().config();
        var input = new LinkedHashMap<String, Object>();
        var cases = new ArrayList<Object>();
        if (config.get("cases") instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> caseMap) {
                    cases.add(caseSnapshot(ctx, caseMap));
                }
            }
        }
        input.put("cases", cases);
        input.put("else_edge_id", str(config.get("else_edge_id"), ""));
        return input;
    }

    private static Map<String, Object> caseSnapshot(NodeContext ctx, Map<?, ?> caseMap) {
        var item = new LinkedHashMap<String, Object>();
        item.put("edge_id", str(caseMap.get("edge_id"), ""));
        item.put("logic", str(caseMap.get("logic"), "and"));
        var conditions = new ArrayList<Object>();
        if (caseMap.get("conditions") instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> condition) {
                    String selector = str(condition.get("selector"), "");
                    var one = new LinkedHashMap<String, Object>();
                    one.put("selector", selector);
                    one.put("operator", str(condition.get("operator"), ""));
                    one.put("expected", str(condition.get("value"), ""));
                    one.put("actual", selector.isBlank() ? null : ctx.pool().resolve(selector).orElse(null));
                    conditions.add(one);
                }
            }
        }
        item.put("conditions", conditions);
        return item;
    }

    private static Map<String, Object> predecessors(NodeContext ctx) {
        var result = new LinkedHashMap<String, Object>();
        for (String id : ctx.graph().inEdges(ctx.node().id()).stream()
            .map(WorkflowEdge::source)
            .distinct()
            .toList()) {
            var input = new LinkedHashMap<String, Object>();
            ctx.pool().resolve("nodes." + id + ".output").ifPresent(value -> input.put("output", coerce(value)));
            ctx.pool().resolve("nodes." + id + ".artifacts").ifPresent(value -> input.put("artifacts", coerce(value)));
            result.put(id, input);
        }
        return result;
    }

    private static String rendered(NodeContext ctx, String key) {
        Object template = ctx.node().config().get(key);
        return template instanceof String value ? ctx.pool().render(value) : "";
    }

    private static boolean sensitiveHeader(String key) {
        return WorkflowGraphSanitizer.shouldRedactKey(key);
    }

    private static Object coerce(Object value) {
        if (!(value instanceof String string)) {
            return value;
        }
        String trimmed = string.strip();
        try {
            if (trimmed.startsWith("{")) {
                return JSON.fromJSON(Map.class, trimmed);
            }
            if (trimmed.startsWith("[")) {
                return JSON.fromJSON(List.class, trimmed);
            }
        } catch (RuntimeException e) {
            // not JSON; keep the original scalar string
        }
        return value;
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static String json(Object value) {
        return JSON.toJSON(value);
    }
}
