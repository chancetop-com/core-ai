package ai.core.api.server.foryou;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ForYouReportView {
    @Property(name = "id")
    public String id;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "title")
    public String title;

    @Property(name = "content")
    public String content;

    @Property(name = "type")
    public String type;

    @Property(name = "tags")
    public List<String> tags;

    @Property(name = "created_at")
    public String createdAt;

    @Property(name = "updated_at")
    public String updatedAt;
}
