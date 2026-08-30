package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class GatewayProviderView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "baseUrl")
    public String baseUrl;

    @Property(name = "apiKeyMasked")
    public String apiKeyMasked;

    @Property(name = "hasApiKey")
    public Boolean hasApiKey;

    @Property(name = "apiVersion")
    public String apiVersion;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "allowPrivateNetwork")
    public Boolean allowPrivateNetwork;

    @Property(name = "modelPrefix")
    public String modelPrefix;

    @Property(name = "defaultChatModel")
    public String defaultChatModel;

    @Property(name = "defaultResponsesModel")
    public String defaultResponsesModel;

    @Property(name = "defaultImageModel")
    public String defaultImageModel;

    @Property(name = "defaultVideoModel")
    public String defaultVideoModel;

    @Property(name = "mediaProtocol")
    public String mediaProtocol;

    @Property(name = "mediaAuthType")
    public String mediaAuthType;

    @Property(name = "hasGoogleCredentials")
    public Boolean hasGoogleCredentials;

    @Property(name = "vertexProjectId")
    public String vertexProjectId;

    @Property(name = "vertexLocation")
    public String vertexLocation;

    @Property(name = "vertexGcsBucket")
    public String vertexGcsBucket;

    @Property(name = "requestExtraBody")
    public String requestExtraBody;

    @Property(name = "timeoutSeconds")
    public Long timeoutSeconds;

    @Property(name = "connectTimeoutSeconds")
    public Long connectTimeoutSeconds;

    @Property(name = "creditUsdRate")
    public Double creditUsdRate;

    @Property(name = "createdBy")
    public String createdBy;

    @Property(name = "updatedBy")
    public String updatedBy;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;

    @Property(name = "updatedAt")
    public ZonedDateTime updatedAt;

    @Property(name = "lastTestStatus")
    public String lastTestStatus;

    @Property(name = "lastTestMessage")
    public String lastTestMessage;

    @Property(name = "lastTestAt")
    public ZonedDateTime lastTestAt;
}
