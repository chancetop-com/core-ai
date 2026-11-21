package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSpecRow {
    @Column(name = "id")
    public String id;
    @Column(name = "evaluation_id")
    public String evaluationId;
    @Column(name = "revision")
    public Integer revision;
    @Column(name = "dataset_revision_id")
    public String datasetRevisionId;
    @Column(name = "grader_suite_id")
    public String graderSuiteId;
    @Column(name = "fingerprint")
    public String fingerprint;
    @Column(name = "body_json")
    public String bodyJson;
    @Column(name = "created_by")
    public String createdBy;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "data")
    public String data;
}
