package ai.core.server.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * API key record for API users. Covers both temporary call keys (ctk_, scope=call)
 * and management keys (cmk_, scope=manage).
 *
 * @author stephen
 */
@Collection(name = "api_keys")
public class ApiKey {
    @Id
    public String id;

    @Field(name = "key_hash")
    public String keyHash;

    @Field(name = "key_prefix")
    public String keyPrefix;

    @Field(name = "user_id")
    public String userId;

    @Field(name = "scope")
    public String scope;

    @Field(name = "metadata")
    public Map<String, String> metadata;

    @Field(name = "status")
    public String status;

    @Field(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "last_used_at")
    public ZonedDateTime lastUsedAt;

    @Field(name = "revoked_at")
    public ZonedDateTime revokedAt;
}
