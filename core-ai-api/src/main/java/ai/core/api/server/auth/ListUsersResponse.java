package ai.core.api.server.auth;

import ai.core.api.server.apiuser.response.ResourcePermissionView;
import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class ListUsersResponse {
    @Property(name = "users")
    public List<UserStatusView> users;

    public static class UserStatusView {
        @Property(name = "email")
        public String email;

        @Property(name = "user_id")
        public String userId;

        @Property(name = "user_type")
        public String userType;

        @Property(name = "external_id")
        public String externalId;

        @Property(name = "name")
        public String name;

        @Property(name = "role")
        public String role;

        @Property(name = "status")
        public String status;

        @Property(name = "created_at")
        public ZonedDateTime createdAt;

        @Property(name = "has_api_key")
        public Boolean hasApiKey;

        @Property(name = "api_key_created_at")
        public ZonedDateTime apiKeyCreatedAt;

        @Property(name = "api_key")
        public String apiKey;

        @Property(name = "owner_id")
        public String ownerId;

        @Property(name = "owner_name")
        public String ownerName;

        @Property(name = "created_by")
        public String createdBy;

        @Property(name = "permissions")
        public List<ResourcePermissionView> permissions;

        @Property(name = "input_token_quota")
        public Long inputTokenQuota;

        @Property(name = "output_token_quota")
        public Long outputTokenQuota;

        @Property(name = "quota_consumed_input_tokens")
        public Long quotaConsumedInputTokens;

        @Property(name = "quota_consumed_output_tokens")
        public Long quotaConsumedOutputTokens;
    }
}
