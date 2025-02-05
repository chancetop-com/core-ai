package ai.core.example.api;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class ChatRequest {
    @Property(name = "query")
    public String query;
}
