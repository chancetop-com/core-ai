package ai.core.telemetry.context;

/**
 * Context object carrying agent group-specific trace information
 * Avoids circular dependencies between tracers and domain entities
 *
 * @author stephen
 */
public final class GroupTraceContext {
    public static Builder builder() {
        return new Builder();
    }

    private final String groupName;
    private final String groupId;
    private final String input;
    private final int agentCount;
    private final int currentRound;
    private final int maxRound;
    private final String sessionId;
    private final String userId;
    private String currentAgentName;
    private String output;
    private String status;

    private GroupTraceContext(Builder builder) {
        this.groupName = builder.groupName;
        this.groupId = builder.groupId;
        this.input = builder.input;
        this.agentCount = builder.agentCount;
        this.currentRound = builder.currentRound;
        this.maxRound = builder.maxRound;
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.currentAgentName = builder.currentAgentName;
        this.output = builder.output;
        this.status = builder.status;
    }

    public String getGroupName() {
        return groupName;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getInput() {
        return input;
    }

    public int getAgentCount() {
        return agentCount;
    }

    public int getCurrentRound() {
        return currentRound;
    }

    public int getMaxRound() {
        return maxRound;
    }

    public String getCurrentAgentName() {
        return currentAgentName;
    }

    public void setCurrentAgentName(String currentAgentName) {
        this.currentAgentName = currentAgentName;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public static class Builder {
        private String groupName;
        private String groupId;
        private String input;
        private int agentCount;
        private int currentRound;
        private int maxRound;
        private String sessionId;
        private String userId;
        private String currentAgentName;
        private String output;
        private String status;

        public Builder groupName(String groupName) {
            this.groupName = groupName;
            return this;
        }

        public Builder groupId(String groupId) {
            this.groupId = groupId;
            return this;
        }

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder agentCount(int agentCount) {
            this.agentCount = agentCount;
            return this;
        }

        public Builder currentRound(int currentRound) {
            this.currentRound = currentRound;
            return this;
        }

        public Builder maxRound(int maxRound) {
            this.maxRound = maxRound;
            return this;
        }

        public Builder currentAgentName(String currentAgentName) {
            this.currentAgentName = currentAgentName;
            return this;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
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

        public GroupTraceContext build() {
            return new GroupTraceContext(this);
        }
    }
}
