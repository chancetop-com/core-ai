package ai.core.prompt.langfuse;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Provider for fetching prompts from Langfuse prompt management API
 *
 * @author stephen
 */
public class LangfusePromptProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(LangfusePromptProvider.class);

    private final LangfusePromptConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, LangfusePrompt> cache;
    private final boolean cacheEnabled;

    /**
     * Create a new LangfusePromptProvider with caching enabled
     */
    public LangfusePromptProvider(LangfusePromptConfig config) {
        this(config, true);
    }

    /**
     * Create a new LangfusePromptProvider
     *
     * @param config       Langfuse configuration
     * @param cacheEnabled Whether to cache fetched prompts
     */
    public LangfusePromptProvider(LangfusePromptConfig config, boolean cacheEnabled) {
        this.config = config;
        this.cacheEnabled = cacheEnabled;
        this.cache = new HashMap<>();

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
            .build();

        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Get a prompt by name (fetches the latest production version)
     *
     * @param name Prompt name
     * @return LangfusePrompt object
     * @throws LangfusePromptException if fetch fails
     */
    public LangfusePrompt getPrompt(String name) throws LangfusePromptException {
        return getPrompt(name, null, null);
    }

    /**
     * Get a prompt by name and version
     *
     * @param name    Prompt name
     * @param version Prompt version (null for latest production)
     * @return LangfusePrompt object
     * @throws LangfusePromptException if fetch fails
     */
    public LangfusePrompt getPrompt(String name, Integer version) throws LangfusePromptException {
        return getPrompt(name, version, null);
    }

    /**
     * Get a prompt by name with optional version or label
     *
     * @param name    Prompt name (required)
     * @param version Prompt version (optional, mutually exclusive with label)
     * @param label   Prompt label (optional, mutually exclusive with version)
     * @return LangfusePrompt object
     * @throws LangfusePromptException if fetch fails
     */
    public LangfusePrompt getPrompt(String name, Integer version, String label) throws LangfusePromptException {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Prompt name is required");
        }

        // Check cache first
        String cacheKey = buildCacheKey(name, version, label);
        if (cacheEnabled && cache.containsKey(cacheKey)) {
            LOGGER.debug("Returning cached prompt: {}", cacheKey);
            return cache.get(cacheKey);
        }

        // Build query parameters
        Map<String, String> queryParams = new HashMap<>();
//        queryParams.put("name", name);
        if (version != null) {
            queryParams.put("version", String.valueOf(version));
        }
        if (label != null && !label.isEmpty()) {
            queryParams.put("label", label);
        }

        String url = buildUrl(config.getPromptEndpoint()+"/"+name, queryParams);

        try {
            LOGGER.debug("Fetching prompt from Langfuse: {}", url);

            // Build request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .GET();

            // Add headers
            config.getHeaders().forEach(requestBuilder::header);
            requestBuilder.header("Content-Type", "application/json");

            HttpRequest request = requestBuilder.build();

            // Send request
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            // Check response status
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LangfusePrompt prompt = objectMapper.readValue(response.body(), LangfusePrompt.class);

                // Cache the result
                if (cacheEnabled) {
                    cache.put(cacheKey, prompt);
                }

                LOGGER.info("Successfully fetched prompt '{}' (version: {}, type: {})",
                    prompt.getName(), prompt.getVersion(), prompt.getType());
                return prompt;
            } else {
                String errorMessage = String.format("Failed to fetch prompt '%s': HTTP %d - %s",
                    name, response.statusCode(), response.body());
                LOGGER.error(errorMessage);
                throw new LangfusePromptException(errorMessage);
            }
        } catch (IOException | InterruptedException e) {
            String errorMessage = String.format("Error fetching prompt '%s': %s", name, e.getMessage());
            LOGGER.error(errorMessage, e);
            throw new LangfusePromptException(errorMessage, e);
        }
    }

    /**
     * Get a prompt by name and label
     *
     * @param name  Prompt name
     * @param label Prompt label (e.g., "production", "staging")
     * @return LangfusePrompt object
     * @throws LangfusePromptException if fetch fails
     */
    public LangfusePrompt getPromptByLabel(String name, String label) throws LangfusePromptException {
        return getPrompt(name, null, label);
    }

    /**
     * Get the prompt content as a string
     * This is a convenience method that fetches the prompt and returns its content
     *
     * @param name Prompt name
     * @return Prompt content as string
     * @throws LangfusePromptException if fetch fails
     */
    public String getPromptContent(String name) throws LangfusePromptException {
        return getPrompt(name).getPromptContent();
    }

    /**
     * Get the prompt content with version
     *
     * @param name    Prompt name
     * @param version Prompt version
     * @return Prompt content as string
     * @throws LangfusePromptException if fetch fails
     */
    public String getPromptContent(String name, Integer version) throws LangfusePromptException {
        return getPrompt(name, version).getPromptContent();
    }

    /**
     * Get the prompt content with label
     *
     * @param name  Prompt name
     * @param label Prompt label
     * @return Prompt content as string
     * @throws LangfusePromptException if fetch fails
     */
    public String getPromptContentByLabel(String name, String label) throws LangfusePromptException {
        return getPromptByLabel(name, label).getPromptContent();
    }

    /**
     * Clear the prompt cache
     */
    public void clearCache() {
        cache.clear();
        LOGGER.debug("Prompt cache cleared");
    }

    /**
     * Remove a specific prompt from cache
     *
     * @param name    Prompt name
     * @param version Prompt version
     * @param label   Prompt label
     */
    public void removeCachedPrompt(String name, Integer version, String label) {
        String cacheKey = buildCacheKey(name, version, label);
        cache.remove(cacheKey);
        LOGGER.debug("Removed cached prompt: {}", cacheKey);
    }

    /**
     * Build cache key from prompt parameters
     */
    private String buildCacheKey(String name, Integer version, String label) {
        var key = new StringBuilder(name);
        if (version != null) {
            key.append(":v").append(version);
        }
        if (label != null && !label.isEmpty()) {
            key.append(':').append(label);
        }
        return key.toString();
    }

    /**
     * Build URL with query parameters
     */
    private String buildUrl(String baseUrl, Map<String, String> queryParams) {
        if (queryParams.isEmpty()) {
            return baseUrl;
        }

        var url = new StringBuilder(baseUrl);
        url.append('?');

        boolean first = true;
        for (Map.Entry<String, String> entry : queryParams.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                .append('=')
                .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
            first = false;
        }

        return url.toString();
    }

    /**
     * Exception thrown when prompt fetching fails
     */
    public static class LangfusePromptException extends Exception {
        private static final long serialVersionUID = 1L;

        public LangfusePromptException(String message) {
            super(message);
        }

        public LangfusePromptException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
