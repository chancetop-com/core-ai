package ai.core.prompt.system;

/**
 * @author stephen
 */
public final class SystemPromptConfig {
    public static Builder builder() {
        return new Builder();
    }

    private final String baseUrl;
    private final String apiKey;
    private final int timeoutSeconds;
    private final boolean trustAll;

    private SystemPromptConfig(Builder builder) {
        this.baseUrl = normalizeBaseUrl(builder.baseUrl);
        this.apiKey = builder.apiKey;
        this.timeoutSeconds = builder.timeoutSeconds;
        this.trustAll = builder.trustAll;
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

    public String getApiKey() {
        return apiKey;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public boolean isTrustAll() {
        return trustAll;
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private int timeoutSeconds = 10;
        private boolean trustAll;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder timeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
            return this;
        }

        public Builder trustAll(boolean trustAll) {
            this.trustAll = trustAll;
            return this;
        }

        public SystemPromptConfig build() {
            if (baseUrl == null || baseUrl.isEmpty()) {
                throw new IllegalArgumentException("baseUrl is required");
            }
            return new SystemPromptConfig(this);
        }
    }
}
