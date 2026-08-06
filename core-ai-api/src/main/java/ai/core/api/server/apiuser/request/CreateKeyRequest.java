package ai.core.api.server.apiuser.request;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class CreateKeyRequest {
    @Property(name = "ttl_seconds")
    public Integer ttlSeconds;

    @Property(name = "metadata")
    public Map<String, String> metadata;
}
