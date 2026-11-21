package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgDatasetRevisionRow {
    @Column(name = "id")
    public String id;
    @Column(name = "evaluation_id")
    public String evaluationId;
    @Column(name = "revision")
    public Integer revision;
    @Column(name = "status")
    public String status;
    @Column(name = "manifest_json")
    public String manifestJson;
    @Column(name = "content_hash")
    public String contentHash;
    @Column(name = "case_count")
    public Integer caseCount;
    @Column(name = "created_by")
    public String createdBy;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "data")
    public String data;
}
