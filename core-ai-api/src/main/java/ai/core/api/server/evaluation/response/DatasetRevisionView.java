package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class DatasetRevisionView {
    @Property(name = "id")
    public String id;

    @Property(name = "evaluation_id")
    public String evaluationId;

    @Property(name = "revision")
    public Integer revision;

    @Property(name = "status")
    public String status;

    @Property(name = "case_count")
    public Integer caseCount;

    @Property(name = "content_hash")
    public String contentHash;

    @Property(name = "manifest_json")
    public String manifestJson;

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
