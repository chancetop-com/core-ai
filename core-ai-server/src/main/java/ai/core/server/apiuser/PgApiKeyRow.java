package ai.core.server.apiuser;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgApiKeyRow {
    @Column(name = "id")
    public String id;

    @Column(name = "key_hash")
    public String keyHash;

    @Column(name = "key_prefix")
    public String keyPrefix;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "scope")
    public String scope;

    @Column(name = "metadata")
    public String metadata;

    @Column(name = "status")
    public String status;

    @Column(name = "expires_at")
    public ZonedDateTime expiresAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "last_used_at")
    public ZonedDateTime lastUsedAt;

    @Column(name = "revoked_at")
    public ZonedDateTime revokedAt;
}
