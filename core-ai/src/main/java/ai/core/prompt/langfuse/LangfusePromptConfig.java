package ai.core.prompt.langfuse;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public final class LangfusePromptConfig {
    public static Builder builder() {
        return new Builder();
    }

    private final String baseUrl;
    private final String publicKey;
    private final String secretKey;
    private final Map<String, String> headers;
    private final int timeoutSeconds;

    private LangfusePromptConfig(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.publicKey = builder.publicKey;
        this.secretKey = builder.secretKey;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.headers = new HashMap<>(builder.headers);

        // Add Basic Auth header if credentials are provided
        if (publicKey != null && secretKey != null) {
            String credentials = publicKey + ":" + secretKey;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());
            this.headers.put("Authorization", "Basic " + encodedCredentials);
        }
    }

    private String normalizeBaseUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        return url.replaceAll("/+$", "");
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public Map<String, String> getHeaders() {
        return new HashMap<>(headers);
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getPromptEndpoint() {
        return baseUrl + "/api/public/v2/prompts";
    }

    public static class Builder {
        private String baseUrl = "https://cloud.langfuse.com";
        private String publicKey;
        private String secretKey;
        private final Map<String, String> headers = new HashMap<>();
        private int timeoutSeconds = 10;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder publicKey(String publicKey) {
            this.publicKey = publicKey;
            return this;
        }

        public Builder secretKey(String secretKey) {
            this.secretKey = secretKey;
            return this;
        }

        public Builder credentials(String publicKey, String secretKey) {
            this.publicKey = publicKey;
            this.secretKey = secretKey;
            return this;
        }

        public Builder addHeader(String key, String value) {
            this.headers.put(key, value);
            return this;
        }

        public Builder addHeaders(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public LangfusePromptConfig build() {
            if (baseUrl == null || baseUrl.isEmpty()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            return new LangfusePromptConfig(this);
        }
    }
}
