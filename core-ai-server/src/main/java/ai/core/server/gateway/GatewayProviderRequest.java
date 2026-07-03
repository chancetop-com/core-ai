package ai.core.server.gateway;

public class GatewayProviderRequest {
    public String name;
    public String type;
    public String baseUrl;
    public String apiKey;
    public String apiVersion;
    public Boolean enabled;
    public String modelPrefix;
    public String defaultChatModel;
    public String defaultResponsesModel;
    public String requestExtraBody;
    public Long timeoutSeconds;
    public Long connectTimeoutSeconds;
}
