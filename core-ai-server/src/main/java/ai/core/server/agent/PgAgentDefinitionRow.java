package ai.core.server.agent;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAgentDefinitionRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "name")
    public String name;

    @Column(name = "name_key")
    public String nameKey;

    @Column(name = "type")
    public String type;

    @Column(name = "status")
    public String status;

    @Column(name = "system_default")
    public Boolean systemDefault;

    @Column(name = "forked_from")
    public String forkedFrom;

    @Column(name = "has_published_config")
    public Boolean hasPublishedConfig;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "data")
    public String data;
}
