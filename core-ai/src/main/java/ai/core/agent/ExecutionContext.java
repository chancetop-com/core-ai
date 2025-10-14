package ai.core.agent;

import core.framework.util.Maps;

import java.util.Map;

/**
 * Execution context for passing session_id, user_id, and custom variables to nodes
 * This context is passed through the execution chain and can be used for:
 * - Tracing and observability (session_id, user_id)
 * - Custom business logic variables
 * - Propagating context between agents/flows
 *
 * @author stephen
 */
public final class ExecutionContext {
    public static Builder builder() {
        return new Builder();
    }

    public static ExecutionContext empty() {
        return new Builder().build();
    }

    private final String sessionId;
    private final String userId;
    private final Map<String, Object> customVariables;

    private ExecutionContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.customVariables = Maps.newHashMap();
        this.customVariables.putAll(builder.customVariables);
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public Map<String, Object> getCustomVariables() {
        return customVariables;
    }

    /**
     * Get a custom variable by key
     */
    public Object getCustomVariable(String key) {
        return customVariables.get(key);
    }

    /**
     * Get a custom variable by key with type casting
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomVariable(String key, Class<T> type) {
        return (T) customVariables.get(key);
    }

    /**
     * Check if a custom variable exists
     */
    public boolean hasCustomVariable(String key) {
        return customVariables.containsKey(key);
    }

    /**
     * Create a new ExecutionContext with additional custom variables
     */
    public ExecutionContext withCustomVariables(Map<String, Object> additionalVariables) {
        var builder = builder()
            .sessionId(this.sessionId)
            .userId(this.userId)
            .customVariables(this.customVariables);

        if (additionalVariables != null) {
            builder.customVariables.putAll(additionalVariables);
        }

        return builder.build();
    }

    /**
     * Create a new ExecutionContext with a single additional custom variable
     */
    public ExecutionContext withCustomVariable(String key, Object value) {
        var builder = builder()
            .sessionId(this.sessionId)
            .userId(this.userId)
            .customVariables(this.customVariables);

        builder.customVariables.put(key, value);

        return builder.build();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private final Map<String, Object> customVariables = Maps.newHashMap();

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder customVariables(Map<String, Object> customVariables) {
            if (customVariables != null) {
                this.customVariables.putAll(customVariables);
            }
            return this;
        }

        public Builder customVariable(String key, Object value) {
            this.customVariables.put(key, value);
            return this;
        }

        public ExecutionContext build() {
            return new ExecutionContext(this);
        }
    }
}
