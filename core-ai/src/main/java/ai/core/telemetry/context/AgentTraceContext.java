package ai.core.telemetry.context;

/**
 * Context object carrying agent-specific trace information
 * Avoids circular dependencies between tracers and domain entities
 *
 * @author stephen
 */
public final class AgentTraceContext {
    public static Builder builder() {
        return new Builder();
    }

    private final String name;
    private final String id;
    private final String type;
    private final String input;
    private final boolean hasTools;
    private final boolean hasRag;
    private String output;
    private String status;
    private int messageCount;

    private AgentTraceContext(Builder builder) {
        this.name = builder.name;
        this.id = builder.id;
        this.type = builder.type;
        this.input = builder.input;
        this.hasTools = builder.hasTools;
        this.hasRag = builder.hasRag;
        this.output = builder.output;
        this.status = builder.status;
        this.messageCount = builder.messageCount;
    }

    public String getName() {
        return name;
    }

    public String getId() {
        return id;
    }

    public String getType() {
        return type;
    }

    public String getInput() {
        return input;
    }

    public boolean hasTools() {
        return hasTools;
    }

    public boolean hasRag() {
        return hasRag;
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

    public int getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(int messageCount) {
        this.messageCount = messageCount;
    }

    public static class Builder {
        private String name;
        private String id;
        private String type;
        private String input;
        private boolean hasTools;
        private boolean hasRag;
        private String output;
        private String status;
        private int messageCount;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder input(String input) {
            this.input = input;
            return this;
        }

        public Builder withTools(boolean hasTools) {
            this.hasTools = hasTools;
            return this;
        }

        public Builder withRag(boolean hasRag) {
            this.hasRag = hasRag;
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

        public Builder messageCount(int messageCount) {
            this.messageCount = messageCount;
            return this;
        }

        public AgentTraceContext build() {
            return new AgentTraceContext(this);
        }
    }
}
