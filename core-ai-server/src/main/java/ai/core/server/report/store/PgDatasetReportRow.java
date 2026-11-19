package ai.core.server.report.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author core-ai
 */
public class PgDatasetReportRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "title")
    public String title;

    @Column(name = "description")
    public String description;

    @Column(name = "revision")
    public Long revision;

    @Column(name = "spec_version")
    public Integer specVersion;

    @Column(name = "engine_version")
    public Integer engineVersion;

    @Column(name = "spec_json")
    public String specJson;

    @Column(name = "dataset_id")
    public String datasetId;

    @Column(name = "dependency_json")
    public String dependencyJson;

    @Column(name = "origin_session_id")
    public String originSessionId;

    @Column(name = "origin_agent_id")
    public String originAgentId;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
}
