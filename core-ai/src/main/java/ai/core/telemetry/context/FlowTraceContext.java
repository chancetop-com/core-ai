package ai.core.telemetry.context;

/**
 * Context object carrying flow-specific trace information
 * Avoids circular dependencies between tracers and domain entities
 *
 * @author stephen
 */
public final class FlowTraceContext {
    public static Builder builder() {
        return new Builder();
    }

    private final String flowId;
    private final String flowName;
    private final String nodeId;
    private final String nodeName;
    private final String sessionId;
    private final String userId;

    private FlowTraceContext(Builder builder) {
        this.flowId = builder.flowId;
        this.flowName = builder.flowName;
        this.nodeId = builder.nodeId;
        this.nodeName = builder.nodeName;
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
    }

    public String getFlowId() {
        return flowId;
    }

    public String getFlowName() {
        return flowName;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getNodeName() {
        return nodeName;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public static class Builder {
        private String flowId;
        private String flowName;
        private String nodeId;
        private String nodeName;
        private String sessionId;
        private String userId;

        public Builder flowId(String flowId) {
            this.flowId = flowId;
            return this;
        }

        public Builder flowName(String flowName) {
            this.flowName = flowName;
            return this;
        }

        public Builder nodeId(String nodeId) {
            this.nodeId = nodeId;
            return this;
        }

        public Builder nodeName(String nodeName) {
            this.nodeName = nodeName;
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public FlowTraceContext build() {
            return new FlowTraceContext(this);
        }
    }
}
