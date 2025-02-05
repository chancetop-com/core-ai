package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GetGenmoResponse {
    @Property(name = "url")
    public String url;

    @Property(name = "status")
    public String status;

    @Property(name = "progress")
    public Double progress;
}
