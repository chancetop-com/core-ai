package ai.core.server.gateway;

public class GatewayProviderRequest {
    public String name;
    public String type;
    public String baseUrl;
    public String apiKey;
    public String apiVersion;
    public Boolean enabled;
    public Boolean allowPrivateNetwork;
    public String modelPrefix;
    public String defaultChatModel;
    public String defaultResponsesModel;
    public String defaultImageModel;
    public String defaultVideoModel;
    public String mediaProtocol;
    public String mediaAuthType;
    public String googleCredentialsJson;
    public String vertexProjectId;
    public String vertexLocation;
    public String requestExtraBody;
    public Long timeoutSeconds;
    public Long connectTimeoutSeconds;
}
