package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgGraderSuiteRow {
    @Column(name = "id")
    public String id;
    @Column(name = "evaluation_id")
    public String evaluationId;
    @Column(name = "revision")
    public Integer revision;
    @Column(name = "name")
    public String name;
    @Column(name = "fingerprint")
    public String fingerprint;
    @Column(name = "body_json")
    public String bodyJson;
    @Column(name = "test_receipt_json")
    public String testReceiptJson;
    @Column(name = "created_by")
    public String createdBy;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "data")
    public String data;
}
