package ai.core.api.server.foryou;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ForYouReportRequest {
    @Property(name = "title")
    public String title;

    @Property(name = "content")
    public String content;

    @Property(name = "type")
    public String type;

    @Property(name = "tags")
    public List<String> tags;
}
