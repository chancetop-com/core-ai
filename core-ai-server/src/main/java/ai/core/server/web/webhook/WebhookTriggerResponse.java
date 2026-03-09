package ai.core.server.web.webhook;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class WebhookTriggerResponse {
    @NotNull
    @Property(name = "run_id")
    public String runId;

    @NotNull
    @Property(name = "status")
    public String status;
}
