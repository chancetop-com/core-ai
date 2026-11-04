package ai.core.server.dataset.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgDatasetRow {
    @Column(name = "id")
    public String id;

    @Column(name = "name")
    public String name;

    @Column(name = "description")
    public String description;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "type")
    public String type;

    @Column(name = "schema_json")
    public String schemaJson;

    @Column(name = "schema_version")
    public Integer schemaVersion;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
