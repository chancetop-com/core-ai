package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ApiUserQuotaView {
    @Property(name = "token_quota")
    public Long tokenQuota;

    @Property(name = "consumed_tokens")
    public Long consumedTokens;
}
