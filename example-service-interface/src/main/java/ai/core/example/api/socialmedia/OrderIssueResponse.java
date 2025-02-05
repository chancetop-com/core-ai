package ai.core.example.api.socialmedia;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class OrderIssueResponse {
    @Property(name = "content")
    public String content;

    @Property(name = "id")
    public String id;

    @Property(name = "conversation")
    public List<String> conversation;
}
