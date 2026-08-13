package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class SystemPromptView {
    @Property(name = "id")
    public String id;

    @Property(name = "promptId")
    public String promptId;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "content")
    public String content;

    @Property(name = "variables")
    public List<String> variables;

    @Property(name = "version")
    public Integer version;

    @Property(name = "changelog")
    public String changelog;

    @Property(name = "tags")
    public List<String> tags;

    @Property(name = "userId")
    public String userId;

    @Property(name = "createdAt")
    public ZonedDateTime createdAt;
}
