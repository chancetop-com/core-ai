package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.time.Instant;
import java.util.Map;

/**
 * @author stephen
 */
public class Message {
    @Property(name = "role")
    public String role;

    @Property(name = "content")
    public String content;

    @Property(name = "timestamp")
    public Instant timestamp;

    @Property(name = "metadata")
    public Map<String, String> metadata;
}
