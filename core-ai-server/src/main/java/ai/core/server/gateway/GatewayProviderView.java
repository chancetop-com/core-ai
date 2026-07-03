package ai.core.server.gateway;

import java.time.ZonedDateTime;

public class GatewayProviderView {
    public String id;
    public String name;
    public String type;
    public String baseUrl;
    public String apiKeyMasked;
    public Boolean hasApiKey;
    public String apiVersion;
    public Boolean enabled;
    public String modelPrefix;
    public String defaultChatModel;
    public String defaultResponsesModel;
    public String requestExtraBody;
    public Long timeoutSeconds;
    public Long connectTimeoutSeconds;
    public String createdBy;
    public String updatedBy;
    public ZonedDateTime createdAt;
    public ZonedDateTime updatedAt;
    public String lastTestStatus;
    public String lastTestMessage;
    public ZonedDateTime lastTestAt;
}
