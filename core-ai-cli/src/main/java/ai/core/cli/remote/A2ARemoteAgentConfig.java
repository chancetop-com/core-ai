package ai.core.cli.remote;

import ai.core.a2a.A2ARemoteAgentDescriptor;

import java.time.Duration;

/**
 * CLI properties-backed remote agent tool configuration.
 *
 * @author xander
 */
public class A2ARemoteAgentConfig {
    public String id;
    public boolean enabled = true;
    public String url;
    public String agentId;
    public String apiKeyEnv;
    public String apiKey;
    public String serverId;
    public String name;
    public String displayName;
    public String description;
    public String status;
    public boolean discoverable;
    public boolean autoDiscovered;
    public Duration timeout = A2ARemoteAgentDescriptor.DEFAULT_TIMEOUT;
    public A2ARemoteAgentDescriptor.ContextPolicy contextPolicy = A2ARemoteAgentDescriptor.ContextPolicy.SESSION;
    public A2ARemoteAgentDescriptor.InvocationMode invocationMode = A2ARemoteAgentDescriptor.InvocationMode.STREAM_BLOCKING;
    public int maxInputChars = A2ARemoteAgentDescriptor.DEFAULT_MAX_INPUT_CHARS;
    public int maxOutputChars = A2ARemoteAgentDescriptor.DEFAULT_MAX_OUTPUT_CHARS;

    public String resolvedApiKey() {
        if (apiKey != null && !apiKey.isBlank()) return apiKey;
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) return null;
        return System.getenv(apiKeyEnv);
    }

    public A2ARemoteAgentDescriptor toDescriptor() {
        return A2ARemoteAgentDescriptor.builder()
                .id(id)
                .toolName(name)
                .toolDescription(description)
                .timeout(timeout)
                .contextPolicy(contextPolicy)
                .invocationMode(invocationMode)
                .maxInputChars(maxInputChars)
                .maxOutputChars(maxOutputChars)
                .build();
    }
}
