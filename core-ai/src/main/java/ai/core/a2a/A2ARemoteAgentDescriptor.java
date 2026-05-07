package ai.core.a2a;

import java.time.Duration;

/**
 * Configuration for exposing an A2A-compatible remote agent as a local tool.
 *
 * @author xander
 */
public class A2ARemoteAgentDescriptor {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(10);
    public static final int DEFAULT_MAX_INPUT_CHARS = 30_000;
    public static final int DEFAULT_MAX_OUTPUT_CHARS = 20_000;

    public static Builder builder() {
        return new Builder();
    }

    public String id;
    public String toolName;
    public String toolDescription;
    public Duration timeout = DEFAULT_TIMEOUT;
    public ContextPolicy contextPolicy = ContextPolicy.SESSION;
    public InvocationMode invocationMode = InvocationMode.STREAM_BLOCKING;
    public int maxInputChars = DEFAULT_MAX_INPUT_CHARS;
    public int maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS;

    private void validate() {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("remote agent id is required");
        }
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("remote agent tool name is required");
        }
        if (toolDescription == null || toolDescription.isBlank()) {
            throw new IllegalArgumentException("remote agent tool description is required");
        }
        if (timeout == null) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (contextPolicy == null) {
            contextPolicy = ContextPolicy.SESSION;
        }
        if (invocationMode == null) {
            invocationMode = InvocationMode.STREAM_BLOCKING;
        }
        if (maxInputChars <= 0) {
            maxInputChars = DEFAULT_MAX_INPUT_CHARS;
        }
        if (maxOutputChars <= 0) {
            maxOutputChars = DEFAULT_MAX_OUTPUT_CHARS;
        }
    }

    public enum ContextPolicy {
        NONE,
        SESSION
    }

    public enum InvocationMode {
        STREAM_BLOCKING,
        SEND_SYNC
    }

    public static final class Builder {
        private final A2ARemoteAgentDescriptor descriptor = new A2ARemoteAgentDescriptor();

        public Builder id(String id) {
            descriptor.id = id;
            return this;
        }

        public Builder toolName(String toolName) {
            descriptor.toolName = toolName;
            return this;
        }

        public Builder toolDescription(String toolDescription) {
            descriptor.toolDescription = toolDescription;
            return this;
        }

        public Builder timeout(Duration timeout) {
            descriptor.timeout = timeout;
            return this;
        }

        public Builder contextPolicy(ContextPolicy contextPolicy) {
            descriptor.contextPolicy = contextPolicy;
            return this;
        }

        public Builder invocationMode(InvocationMode invocationMode) {
            descriptor.invocationMode = invocationMode;
            return this;
        }

        public Builder maxInputChars(int maxInputChars) {
            descriptor.maxInputChars = maxInputChars;
            return this;
        }

        public Builder maxOutputChars(int maxOutputChars) {
            descriptor.maxOutputChars = maxOutputChars;
            return this;
        }

        public A2ARemoteAgentDescriptor build() {
            descriptor.validate();
            return descriptor;
        }
    }
}
