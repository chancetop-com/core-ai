package ai.core.cli.remote;

import ai.core.a2a.A2ARemoteAgentDescriptor;

import java.time.Duration;
import java.util.List;

/**
 * CLI properties-backed remote server configuration for discovering A2A agents.
 *
 * @author xander
 */
public class A2ARemoteServerConfig {
    public String id;
    public boolean enabled = true;
    public String url;
    public String apiKeyEnv;
    public String apiKey;
    public boolean discoveryEnabled = true;
    public String toolPrefix;
    public List<String> includeAgents = List.of();
    public List<String> excludeAgents = List.of();
    public boolean discoverable = true;
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
}
