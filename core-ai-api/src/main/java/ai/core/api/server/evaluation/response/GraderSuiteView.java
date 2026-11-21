package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class GraderSuiteView {
    @Property(name = "id")
    public String id;

    @Property(name = "revision")
    public Integer revision;

    @Property(name = "name")
    public String name;

    @Property(name = "fingerprint")
    public String fingerprint;

    @Property(name = "body_json")
    public String bodyJson;

    @Property(name = "test_receipt_json")
    public String testReceiptJson;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
