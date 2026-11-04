package ai.core.server.dataset.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgDatasetViewRow {
    @Column(name = "dataset_id")
    public String datasetId;

    @Column(name = "view_name")
    public String viewName;

    @Column(name = "column_map")
    public String columnMap;

    @Column(name = "schema_hash")
    public String schemaHash;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
