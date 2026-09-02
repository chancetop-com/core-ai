package ai.core.prompt.system;

import ai.core.internal.http.PatchedHTTPClientBuilder;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.http.HTTPResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Serial;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Fetches prompt content from a core-ai-server SystemPrompt API by name.
 * <p>
 * The server side is {@code GET /api/system-prompts/name/:name} which returns the
 * latest version of the prompt. Prompts are cached in memory after first fetch.
 * <p>
 * Configured via {@code system.prompt.api.*} properties (core-ai standard):
 * <ul>
 *   <li>{@code system.prompt.api.base.url} - core-ai-server base URL (required, use https since core-ng sessions require it)</li>
 *   <li>{@code system.prompt.api.key} - bearer API key for core-ai-server</li>
 *   <li>{@code system.prompt.api.trust.all} - trust any server certificate, e.g. core-ai-server's self-signed cert in-cluster (default false)</li>
 *   <li>{@code system.prompt.timeout.seconds} - request timeout (default 10)</li>
 * </ul>
 *
 * @author stephen
 */
public class SystemPromptProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemPromptProvider.class);

    private final SystemPromptConfig config;
    private final HTTPClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, String> cache;
    private final boolean cacheEnabled;

    public SystemPromptProvider(SystemPromptConfig config) {
        this(config, true);
    }

    public SystemPromptProvider(SystemPromptConfig config, boolean cacheEnabled) {
        this.config = config;
        this.cacheEnabled = cacheEnabled;
        this.cache = new HashMap<>();

        var clientBuilder = new PatchedHTTPClientBuilder()
            .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .timeout(Duration.ofSeconds(config.getTimeoutSeconds()));
        if (config.isTrustAll()) {
            clientBuilder.trustAll();
        }
        this.httpClient = clientBuilder.build();

        this.objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        if (config.getApiKey() == null || config.getApiKey().isEmpty()) {
            LOGGER.warn("system.prompt.api.key not configured - prompt fetch will fail if the server requires auth");
        }
    }

    /**
     * Fetch the latest prompt content by name, with in-memory caching.
     */
    public String getPromptContent(String name) throws SystemPromptException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Prompt name is required");
        }

        var cached = checkCache(name);
        if (cached != null) return cached;

        String url = config.getBaseUrl() + "/api/system-prompts/name/" + URLEncoder.encode(name, StandardCharsets.UTF_8);
        var request = new HTTPRequest(HTTPMethod.GET, url);
        if (config.getApiKey() != null && !config.getApiKey().isEmpty()) {
            request.headers.put("Authorization", "Bearer " + config.getApiKey());
        }
        request.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());

        try {
            LOGGER.debug("Fetching system prompt from core-ai-server: {}", url);
            var response = httpClient.execute(request);
            return handleResponse(name, response);
        } catch (Exception e) {
            String errorMessage = String.format("Error fetching prompt '%s': %s", name, e.getMessage());
            LOGGER.error(errorMessage, e);
            throw new SystemPromptException(errorMessage, e);
        }
    }

    private String checkCache(String name) {
        if (!cacheEnabled) return null;
        String cached = cache.get(name);
        if (cached != null) LOGGER.debug("Returning cached system prompt: {}", name);
        return cached;
    }

    private String handleResponse(String name, HTTPResponse response) throws SystemPromptException {
        if (response.statusCode < 200 || response.statusCode >= 300) {
            String errorMessage = String.format("Failed to fetch prompt '%s': HTTP %d - %s", name, response.statusCode, response.text());
            LOGGER.error(errorMessage);
            throw new SystemPromptException(errorMessage);
        }
        String content;
        try {
            var body = objectMapper.readValue(response.text(), SystemPromptResponse.class);
            content = body != null ? body.content : null;
        } catch (IOException e) {
            throw new SystemPromptException("Failed to parse prompt response: " + name, e);
        }
        if (content == null || content.isBlank()) {
            throw new SystemPromptException("Prompt content is empty: " + name);
        }
        if (cacheEnabled) cache.put(name, content);
        LOGGER.debug("Successfully fetched system prompt '{}' (length: {})", name, content.length());
        return content;
    }

    public void clearCache() {
        cache.clear();
        LOGGER.debug("System prompt cache cleared");
    }

    /**
     * Minimal projection of the SystemPromptView JSON returned by core-ai-server.
     */
    private static final class SystemPromptResponse {
        public String name;
        public String content;
    }

    public static class SystemPromptException extends Exception {
        @Serial
        private static final long serialVersionUID = 3985716413853906634L;

        public SystemPromptException(String message) {
            super(message);
        }

        public SystemPromptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
