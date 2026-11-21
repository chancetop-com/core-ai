package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class StartRunRequest {
    @Property(name = "spec_id")
    public String specId;

    @Property(name = "source")
    public String source;

    @Property(name = "snapshot_id")
    public String snapshotId;

    @Property(name = "purpose")
    public String purpose;

    @Property(name = "experiment_label")
    public String experimentLabel;

    @Property(name = "request_key")
    public String requestKey;
}
