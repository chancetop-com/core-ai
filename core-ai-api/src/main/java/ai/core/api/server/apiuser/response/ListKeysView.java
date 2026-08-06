package ai.core.api.server.apiuser.response;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListKeysView {
    @Property(name = "keys")
    public List<KeyView> keys;

    public static class KeyView {
        @Property(name = "key_id")
        public String keyId;

        @Property(name = "key_prefix")
        public String keyPrefix;

        @Property(name = "scope")
        public String scope;

        @Property(name = "status")
        public String status;

        @Property(name = "expires_at")
        public String expiresAt;

        @Property(name = "last_used_at")
        public String lastUsedAt;

        @Property(name = "created_at")
        public String createdAt;
    }
}
