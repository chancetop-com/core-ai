package ai.core.server.evaluation.domain;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvalSpecEntity {
    public String id;
    public String evaluationId;
    public Integer revision;
    public String datasetRevisionId;
    public String graderSuiteId;
    public String fingerprint;
    public String bodyJson;
    public String createdBy;
    public ZonedDateTime createdAt;
}
