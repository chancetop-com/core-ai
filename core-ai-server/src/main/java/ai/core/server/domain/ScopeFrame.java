package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.List;

/**
 * One frame of a node-run's scope path: which container produced the scope and the iteration index within it.
 * The full path distinguishes the same sub-graph node executed across container iterations, so each repeat is
 * a distinct node-run record.
 *
 * @author Xander
 */
public class ScopeFrame {
    @Field(name = "scope_type")
    public ScopeType scopeType;

    @Field(name = "container_node_id")
    public String containerNodeId;

    @Field(name = "index")
    public Integer index;

    /** Canonical, stable key for the (run_id, node_id, scope_path_key) unique index. Root scope = "". */
    public static String canonicalKey(List<ScopeFrame> scopePath) {
        if (scopePath == null || scopePath.isEmpty()) {
            return "";
        }
        var builder = new StringBuilder();
        for (ScopeFrame frame : scopePath) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(frame.scopeType).append(':').append(frame.containerNodeId).append(':').append(frame.index);
        }
        return builder.toString();
    }
}
