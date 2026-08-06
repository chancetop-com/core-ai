package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class RenewKeyRequest {
    @Property(name = "ttl_seconds")
    public Integer ttlSeconds;
}
