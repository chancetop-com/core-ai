package ai.core.server.trace.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author stephen
 */
final class OTLPTraceMetadata {
    static final String CORE_AI_RUN_ID = "core_ai.run_id";
    private static final String CORE_AI_WORKFLOW_ID = "core_ai.workflow_id";
    private static final String CORE_AI_WORKFLOW_RUN_ID = "core_ai.workflow_run_id";
    private static final String CORE_AI_WORKFLOW_NODE_ID = "core_ai.workflow_node_id";
    private static final String CORE_AI_WORKFLOW_NODE_TYPE = "core_ai.workflow_node_type";

    static Map<String, String> traceMetadata(Map<String, String> attrs, Map<String, String> resourceAttrs) {
        var metadata = new LinkedHashMap<String, String>();
        metadata.put("service", resourceAttrs.getOrDefault("service.name", "unknown"));
        metadata.put("version", resourceAttrs.getOrDefault("service.version", "unknown"));
        metadata.put("environment", resourceAttrs.getOrDefault("deployment.environment", "unknown"));
        metadata.putAll(workflowMetadata(attrs));
        return metadata;
    }

    static Map<String, String> workflowMetadata(Map<String, String> attrs) {
        var metadata = new LinkedHashMap<String, String>();
        putAttr(metadata, "agent_run_id", attrs, CORE_AI_RUN_ID);
        putAttr(metadata, "workflow_id", attrs, CORE_AI_WORKFLOW_ID);
        putAttr(metadata, "workflow_run_id", attrs, CORE_AI_WORKFLOW_RUN_ID);
        putAttr(metadata, "workflow_node_id", attrs, CORE_AI_WORKFLOW_NODE_ID);
        putAttr(metadata, "workflow_node_type", attrs, CORE_AI_WORKFLOW_NODE_TYPE);
        return metadata;
    }

    private static void putAttr(Map<String, String> target, String targetKey, Map<String, String> attrs, String attrKey) {
        var value = attrs.get(attrKey);
        if (value != null && !value.isBlank()) target.put(targetKey, value);
    }
}
