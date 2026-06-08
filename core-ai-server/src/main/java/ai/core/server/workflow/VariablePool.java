package ai.core.server.workflow;

import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import core.framework.json.JSON;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only projection of the data a node can read when it executes: the outputs of upstream COMPLETED nodes
 * (by id) plus the run input. Executors resolve dotted selectors like {@code nodes.http1.output.body.id} or
 * {@code sys.input}, and render {@code {{ selector }}} templates. This is the lean variable model (P2);
 * env/conversation/loop scopes and a typed value model land later. The engine never touches it — only executors do.
 *
 * @author Xander
 */
public final class VariablePool {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    /** Build the pool from a run's node-runs: every COMPLETED node-run in the given scope contributes its output. */
    public static VariablePool fromNodeRuns(List<WorkflowNodeRun> nodeRuns, String scopePathKey, String runInput) {
        Map<String, String> outputs = new LinkedHashMap<>();
        for (WorkflowNodeRun nodeRun : nodeRuns) {
            if (nodeRun.status == NodeRunStatus.COMPLETED && nodeRun.output != null
                && Objects.equals(scopePathKey, nodeRun.scopePathKey)) {
                outputs.put(nodeRun.nodeId, nodeRun.output);
            }
        }
        return new VariablePool(outputs, runInput);
    }

    private final Map<String, String> nodeOutputs;
    private final String runInput;

    public VariablePool(Map<String, String> nodeOutputs, String runInput) {
        this.nodeOutputs = Map.copyOf(nodeOutputs);
        this.runInput = runInput == null ? "{}" : runInput;
    }

    /** Resolve a dotted selector to a value (String/Number/Boolean/Map/List), or empty if it can't be reached. */
    public Optional<Object> resolve(String selector) {
        String[] parts = selector.trim().split("\\.");
        String baseJson;
        int pathStart;
        if ("nodes".equals(parts[0]) && parts.length >= 3 && "output".equals(parts[2])) {
            baseJson = nodeOutputs.get(parts[1]);
            pathStart = 3;
        } else if ("sys".equals(parts[0]) && parts.length >= 2 && "input".equals(parts[1])) {
            baseJson = runInput;
            pathStart = 2;
        } else if ("input".equals(parts[0])) {
            baseJson = runInput;
            pathStart = 1;
        } else {
            return Optional.empty();
        }
        if (baseJson == null) {
            return Optional.empty();
        }
        if (pathStart >= parts.length) {
            return Optional.of(baseJson);   // whole output requested -> the raw JSON string
        }
        return navigate(baseJson, parts, pathStart);
    }

    private static Optional<Object> navigate(String json, String[] parts, int from) {
        Object current;
        try {
            current = JSON.fromJSON(Map.class, json);
        } catch (RuntimeException e) {
            return Optional.empty();   // base is not a JSON object, so a path can't be navigated into it
        }
        for (int i = from; i < parts.length; i++) {
            if (!(current instanceof Map<?, ?> map)) {
                return Optional.empty();
            }
            current = map.get(parts[i]);
            if (current == null) {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(current);
    }

    /** Substitute every {@code {{ selector }}} in the template with the resolved value's string form. */
    public String render(String template) {
        if (template == null) {
            return null;
        }
        Matcher matcher = TEMPLATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = resolve(matcher.group(1)).map(VariablePool::stringify).orElse("");
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String stringify(Object value) {
        if (value instanceof String string) {
            return string;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        return JSON.toJSON(value);
    }
}
