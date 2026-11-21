package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class GraderSuiteEntity {
    public String id;
    public String evaluationId;
    public Integer revision;
    public String name;
    public String fingerprint;
    public String bodyJson;
    public String testReceiptJson;
    public String createdBy;
    public ZonedDateTime createdAt;
}
