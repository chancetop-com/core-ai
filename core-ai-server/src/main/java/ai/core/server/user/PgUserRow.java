package ai.core.server.user;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgUserRow {
    @Column(name = "id")
    public String id;

    @Column(name = "name")
    public String name;

    @Column(name = "email")
    public String email;

    @Column(name = "api_key")
    public String apiKey;

    @Column(name = "role")
    public String role;

    @Column(name = "status")
    public String status;

    @Column(name = "user_type")
    public String userType;

    @Column(name = "owner_id")
    public String ownerId;

    @Column(name = "external_id")
    public String externalId;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "last_login_at")
    public ZonedDateTime lastLoginAt;

    @Column(name = "data")
    public String data;
}
