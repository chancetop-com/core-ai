package ai.core.api.server.user;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

public class ApiKeyView {
    @Property(name = "api_key")
    public String apiKey;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
