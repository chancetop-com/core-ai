package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

@Collection(name = "gateway_provider")
public class GatewayProviderConfig {
    @Id
    public String id;

    @Field(name = "name")
    public String name;

    @Field(name = "type")
    public String type;

    @Field(name = "base_url")
    public String baseUrl;

    @Field(name = "api_key")
    public String apiKey;

    @Field(name = "api_key_encrypted")
    public String apiKeyEncrypted;

    @Field(name = "api_version")
    public String apiVersion;

    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "allow_private_network")
    public Boolean allowPrivateNetwork;

    @Field(name = "model_prefix")
    public String modelPrefix;

    @Field(name = "default_chat_model")
    public String defaultChatModel;

    @Field(name = "default_responses_model")
    public String defaultResponsesModel;

    @Field(name = "default_image_model")
    public String defaultImageModel;

    @Field(name = "default_video_model")
    public String defaultVideoModel;

    @Field(name = "media_protocol")
    public String mediaProtocol;

    @Field(name = "media_auth_type")
    public String mediaAuthType;

    @Field(name = "google_credentials_encrypted")
    public String googleCredentialsEncrypted;

    @Field(name = "vertex_project_id")
    public String vertexProjectId;

    @Field(name = "vertex_location")
    public String vertexLocation;

    @Field(name = "request_extra_body")
    public String requestExtraBody;

    @Field(name = "timeout_seconds")
    public Long timeoutSeconds;

    @Field(name = "connect_timeout_seconds")
    public Long connectTimeoutSeconds;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "updated_by")
    public String updatedBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Field(name = "last_test_status")
    public String lastTestStatus;

    @Field(name = "last_test_message")
    public String lastTestMessage;

    @Field(name = "last_test_at")
    public ZonedDateTime lastTestAt;
}
