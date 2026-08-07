package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ApiUserQuotaView {
    @Property(name = "input_token_quota")
    public Long inputTokenQuota;

    @Property(name = "output_token_quota")
    public Long outputTokenQuota;

    @Property(name = "consumed_input_tokens")
    public Long consumedInputTokens;

    @Property(name = "consumed_output_tokens")
    public Long consumedOutputTokens;
}
