package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class SystemPromptRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "content")
    public String content;

    @Property(name = "tags")
    public List<String> tags;

    @Property(name = "changelog")
    public String changelog;
}
