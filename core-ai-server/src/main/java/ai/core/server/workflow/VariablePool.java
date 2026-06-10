package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowNodeRun;
import core.framework.json.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only projection of the data a node can read when it executes: the outputs and artifact references of
 * upstream COMPLETED nodes (by id) plus the run input. Executors resolve dotted selectors like
 * {@code nodes.http1.output.body.id}, {@code nodes.agent1.artifacts.0.url} or {@code sys.input}, and render
 * {@code {{ selector }}} templates. This is the lean variable model (P2); env/conversation/loop scopes and a
 * typed value model land later. The engine never touches it — only executors do.
 *
 * @author Xander
 */
public final class VariablePool {
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");

    /** Build the pool from a run's node-runs: every COMPLETED node-run in the scope contributes its output and artifacts. */
    public static VariablePool fromNodeRuns(List<WorkflowNodeRun> nodeRuns, String scopePathKey, String runInput) {
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, List<ArtifactRef>> artifacts = new LinkedHashMap<>();
        for (WorkflowNodeRun nodeRun : nodeRuns) {
            if (nodeRun.status != NodeRunStatus.COMPLETED || !Objects.equals(scopePathKey, nodeRun.scopePathKey)) {
                continue;
            }
            if (nodeRun.output != null) {
                outputs.put(nodeRun.nodeId, nodeRun.output);
            }
            if (nodeRun.artifacts != null && !nodeRun.artifacts.isEmpty()) {
                artifacts.put(nodeRun.nodeId, nodeRun.artifacts);
            }
        }
        return new VariablePool(outputs, artifacts, runInput);
    }

    private final Map<String, String> nodeOutputs;
    private final Map<String, List<ArtifactRef>> nodeArtifacts;
    private final String runInput;

    public VariablePool(Map<String, String> nodeOutputs, String runInput) {
        this(nodeOutputs, Map.of(), runInput);
    }

    public VariablePool(Map<String, String> nodeOutputs, Map<String, List<ArtifactRef>> nodeArtifacts, String runInput) {
        this.nodeOutputs = Map.copyOf(nodeOutputs);
        var copy = new LinkedHashMap<String, List<ArtifactRef>>();
        nodeArtifacts.forEach((id, refs) -> copy.put(id, List.copyOf(refs)));
        this.nodeArtifacts = Map.copyOf(copy);
        this.runInput = runInput == null ? "{}" : runInput;
    }

    /** The artifact references an upstream node produced — for output composition (e.g. AGGREGATOR union). */
    public List<ArtifactRef> artifactsOf(String nodeId) {
        return nodeArtifacts.getOrDefault(nodeId, List.of());
    }

    /** Resolve a dotted selector to a value (String/Number/Boolean/Map/List), or empty if it can't be reached. */
    public Optional<Object> resolve(String selector) {
        String[] parts = selector.trim().split("\\.");
        String baseJson;
        int pathStart;
        if ("nodes".equals(parts[0]) && parts.length >= 3 && "output".equals(parts[2])) {
            baseJson = nodeOutputs.get(parts[1]);
            pathStart = 3;
        } else if ("nodes".equals(parts[0]) && parts.length >= 3 && "artifacts".equals(parts[2])) {
            List<ArtifactRef> refs = nodeArtifacts.get(parts[1]);
            baseJson = refs == null ? null : artifactsToJson(refs);
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
            return Optional.of(baseJson);   // whole output / artifact list requested -> the raw JSON string
        }
        return navigate(baseJson, parts, pathStart);
    }

    private static Optional<Object> navigate(String json, String[] parts, int from) {
        Object current;
        try {
            current = parseJson(json);
        } catch (RuntimeException e) {
            return Optional.empty();   // base is not a JSON object/array, so a path can't be navigated into it
        }
        for (int i = from; i < parts.length && current != null; i++) {
            current = step(current, parts[i]);
        }
        return Optional.ofNullable(current);
    }

    // One navigation hop: by key into an object, or by integer index into an array (e.g. artifacts.0.url).
    private static Object step(Object current, String key) {
        if (current instanceof Map<?, ?> map) {
            return map.get(key);
        }
        if (current instanceof List<?> list) {
            int index = parseIndex(key);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        return null;
    }

    private static int parseIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Object parseJson(String json) {
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            return JSON.fromJSON(List.class, json);
        }
        if (trimmed.startsWith("{")) {
            return JSON.fromJSON(Map.class, json);
        }
        return null;   // a scalar/text base has no navigable structure
    }

    // Serialize artifact references to a snake_case JSON array string the selector path navigates into — no bean
    // annotations needed since it goes through plain Map/List, decoupling the pool from ArtifactRef's mapping.
    private static String artifactsToJson(List<ArtifactRef> refs) {
        var list = new ArrayList<Map<String, Object>>(refs.size());
        for (ArtifactRef ref : refs) {
            var map = new LinkedHashMap<String, Object>();
            map.put("file_id", ref.fileId);
            map.put("file_name", ref.fileName);
            map.put("content_type", ref.contentType);
            map.put("size", ref.size);
            map.put("url", ref.url);
            map.put("title", ref.title);
            map.put("description", ref.description);
            list.add(map);
        }
        return JSON.toJSON(list);
    }

    /** Substitute every {@code {{ selector }}} in the template with the resolved value's string form. */
    public String render(String template) {
        return render(template, false);
    }

    /**
     * Render for a JSON-string context: each substituted value is JSON-string-escaped, so a resolved value
     * containing {@code "}, {@code \} or control chars cannot break out of the surrounding JSON string literal
     * or inject structure. Used for tool arguments / HTTP body templates the editor emits as {@code "{{ … }}"}.
     */
    public String renderJson(String template) {
        return render(template, true);
    }

    private String render(String template, boolean jsonEscape) {
        if (template == null) {
            return null;
        }
        Matcher matcher = TEMPLATE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = resolve(matcher.group(1)).map(VariablePool::stringify).orElse("");
            if (jsonEscape) {
                value = jsonEscape(value);
            }
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

    // Escape the content of a JSON string literal (no surrounding quotes): a value placed inside "{{ … }}" can
    // never terminate the string or inject keys, regardless of upstream content.
    private static String jsonEscape(String value) {
        var out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
